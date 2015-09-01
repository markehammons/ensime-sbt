package org.ensime

import sbt._
import Keys._
import IO._
import complete.Parsers._
import collection.immutable.SortedMap
import collection.JavaConverters._
import java.lang.management.ManagementFactory
import scala.util._
import java.io.FileNotFoundException
import SExpFormatter._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import scalariform.formatter.preferences.IFormattingPreferences

/**
 * Conventional way to define importable keys for an AutoPlugin.
 * Note that EnsimePlugin.autoImport == Imports
 */
object Imports {
  object EnsimeKeys {
    val name = SettingKey[String]("name of the ENSIME project")
    val debuggingFlag = SettingKey[String]("JVM flags to enable remote debugging of forked tasks")
    val debuggingPort = SettingKey[Int]("port for remote debugging of forked tasks")
    val compilerArgs = TaskKey[Seq[String]]("arguments for the presentation compiler, extracted from the compiler flags.")
    val additionalCompilerArgs = SettingKey[Seq[String]]("additional arguments for the presentation compiler, e.g. for additional warnings.")
    val additionalSExp = TaskKey[String]("raw SExp to include in the output")
  }
}

object EnsimePlugin extends AutoPlugin with CommandSupport {

  val autoImport = Imports

  // Ensures the underlying base SBT plugin settings are loaded prior to Ensime.
  // This is important otherwise the `compilerArgs` would not be able to
  // depend on `scalacOptions in Compile` (becuase they wouldn't be set yet)
  override def requires = plugins.JvmPlugin

  // Automatically enable the plugin so the user doesn't have to `enablePlugins`
  // in their projects' build.sbt
  override def trigger = allRequirements

  import autoImport._

  lazy val ensimeCommand = Command.command("gen-ensime")(genEnsime)

  lazy val debuggingOnCommand = Command.command("debugging-on")(toggleDebugging(true))
  lazy val debuggingOffCommand = Command.command("debugging-off")(toggleDebugging(false))

  override lazy val projectSettings = Seq(
    commands ++= Seq(ensimeCommand, debuggingOnCommand, debuggingOffCommand),
    EnsimeKeys.debuggingFlag := "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=",
    EnsimeKeys.debuggingPort := 5005,
    EnsimeKeys.compilerArgs := (scalacOptions in Compile).value,
    EnsimeKeys.additionalCompilerArgs := Seq(
      "-feature",
      "-deprecation",
      "-Xlint",
      "-Yinline-warnings",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-Ywarn-value-discard",
      "-Xfuture"
    ) ++ {
        if (scalaVersion.value.startsWith("2.11")) Seq("-Ywarn-unused-import")
        else Nil
      },
    EnsimeKeys.additionalSExp := ""
  )

  def toggleDebugging(enable: Boolean): State => State = { implicit state: State =>
    val extracted = Project.extract(state)

    implicit val pr = extracted.currentRef
    implicit val bs = extracted.structure

    if (enable) {
      val port = EnsimeKeys.debuggingPort.gimme
      log.warn("Enabling debugging for all forked processes")
      log.info("Only one JVM can use the port and it will await a connection before proceeding.")
    }

    val newSettings = extracted.structure.allProjectRefs map { proj =>
      val orig = (javaOptions in proj).run
      val debugging = ((EnsimeKeys.debuggingFlag in proj).gimme + (EnsimeKeys.debuggingPort in proj).gimme)
      val rewritten =
        if (enable) { orig :+ debugging }
        else { orig.diff(List(debugging)) }

      (javaOptions in proj) := rewritten
    }
    extracted.append(newSettings, state)
  }

  def genEnsime: State => State = { implicit state: State =>
    val extracted = Project.extract(state)
    implicit val pr = extracted.currentRef
    implicit val bs = extracted.structure

    val projects = bs.allProjectRefs.flatMap { ref =>
      Project.getProjectForReference(ref, bs).map((ref, _))
    }.toMap

    implicit val rawModules = projects.collect {
      case (ref, proj) =>
        val module = projectData(proj)(ref, bs, state)
        (module.name, module)
    }.toMap

    val modules: Map[String, EnsimeModule] = rawModules.mapValues { m =>
      val deps = m.dependencies
      // restrict jars to immediate deps at each module
      m.copy(
        compileJars = m.compileJars -- deps.flatMap(_.compileJars),
        testJars = m.testJars -- deps.flatMap(_.testJars),
        runtimeJars = m.runtimeJars -- deps.flatMap(_.runtimeJars),
        sourceJars = m.sourceJars -- deps.flatMap(_.sourceJars),
        docJars = m.docJars -- deps.flatMap(_.docJars)
      )
    }

    val root = file(Properties.userDir)
    val out = file(".ensime")
    val cacheDir = file(".ensime_cache")
    val name = EnsimeKeys.name.gimmeOpt.getOrElse {
      if (modules.size == 1) modules.head._2.name
      else root.getAbsoluteFile.getName
    }
    val compilerArgs = {
      (EnsimeKeys.compilerArgs in Compile).run.toList ++
        (EnsimeKeys.additionalCompilerArgs in Compile).gimme
    }.distinct
    val scalaV = (scalaVersion in Compile).gimme
    val javaH = (javaHome in Compile).gimme.getOrElse(JdkDir)
    val javaSrc = file(javaH.getAbsolutePath + "/src.zip") match {
      case f if f.exists => Some(f)
      case _ =>
        log.warn(s"No Java sources detected in $javaH (your ENSIME experience will not be as good as it could be.)")
        None
    }
    val javaFlags = {
      // WORKAROUND https://github.com/ensime/ensime-sbt/issues/91
      val raw = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toList.map {
        case "-Xss1M" => "-Xss2m"
        case flag     => flag
      }
      raw.find { flag => flag.startsWith("-Xss") } match {
        case Some(has) => raw
        case None      => "-Xss2m" :: raw
      }
    }
    val raw = (EnsimeKeys.additionalSExp in Compile).run

    val formatting = (ScalariformKeys.preferences in Compile).gimmeOpt

    val config = EnsimeConfig(
      root, cacheDir, name, scalaV, compilerArgs,
      modules, javaH, javaFlags, javaSrc, formatting, raw
    )

    // workaround for Windows
    write(out, toSExp(config).replaceAll("\r\n", "\n") + "\n")

    if (ignoringSourceDirs.nonEmpty) {
      log.warn(
        s"""Some source directories do not exist and will be ignored by ENSIME.
           |If this is not what you want, create empty directories and re-run this command.
           |For example (only showing 5): ${ignoringSourceDirs.take(5).mkString(",")} """.stripMargin
      )
    }

    state
  }

  // sbt reports a lot of source directories that the user never
  // intends to use we want to create a report
  var ignoringSourceDirs = Set.empty[File]
  def filteredSources(sources: Set[File]): Set[File] = synchronized {
    ignoringSourceDirs ++= sources.filterNot(_.exists())
    sources.filter(_.exists())
  }

  def projectData(project: ResolvedProject)(
    implicit
    projectRef: ProjectRef,
    buildStruct: BuildStructure,
    state: State
  ): EnsimeModule = {
    log.info(s"ENSIME processing ${project.id} (${name.gimme})")

    val builtInTestPhases = Set(Test, IntegrationTest)
    val testPhases = {
      for {
        phase <- ivyConfigurations.gimme
        if !phase.name.toLowerCase.contains("internal")
        if builtInTestPhases(phase) | builtInTestPhases.intersect(phase.extendsConfigs.toSet).nonEmpty
      } yield phase
    }.toSet

    def sourcesFor(config: Configuration) = {
      // invoke source generation so we can filter on existing directories
      (managedSources in config).runOpt
      (managedSourceDirectories in config).gimmeOpt.map(_.toSet).getOrElse(Set()) ++
        (unmanagedSourceDirectories in config).gimmeOpt.getOrElse(Set())
    }

    def targetFor(config: Configuration) =
      (classDirectory in config).gimme

    def targetForOpt(config: Configuration) =
      (classDirectory in config).gimmeOpt

    // run these once to preserve any shred of performance
    val updateReports = testPhases.flatMap {
      phase => (update in phase).runOpt
    }
    val updateClassifiersReports = testPhases.flatMap {
      phase => (updateClassifiers in phase).runOpt
    }

    val myDoc = (artifactPath in (Compile, packageDoc)).gimmeOpt

    val filter = if (sbtPlugin.gimme) "provided" else ""

    def jarsFor(config: Configuration) = updateReports.flatMap(_.select(
      configuration = configurationFilter(filter | config.name.toLowerCase),
      artifact = artifactFilter(extension = "jar")
    )).toSet

    def unmanagedJarsFor(config: Configuration) =
      (unmanagedJars in config).runOpt.map(_.map(_.data).toSet).getOrElse(Set())

    def jarSrcsFor(config: Configuration) = updateClassifiersReports.flatMap(_.select(
      configuration = configurationFilter(filter | config.name.toLowerCase),
      artifact = artifactFilter(classifier = "sources")
    )).toSet

    def jarDocsFor(config: Configuration) = updateClassifiersReports.flatMap(_.select(
      configuration = configurationFilter(filter | config.name.toLowerCase),
      artifact = artifactFilter(classifier = "javadoc")
    )).toSet

    val mainSources = filteredSources(sourcesFor(Compile) ++ sourcesFor(Provided) ++ sourcesFor(Optional))
    val testSources = filteredSources(testPhases.flatMap(sourcesFor))
    val mainTarget = targetFor(Compile)
    val testTargets = testPhases.flatMap(targetForOpt).toSet
    val deps = project.dependencies.map(_.project.project).toSet
    val mainJars = jarsFor(Compile) ++ unmanagedJarsFor(Compile) ++ jarsFor(Provided) ++ jarsFor(Optional)
    val runtimeJars = jarsFor(Runtime) ++ unmanagedJarsFor(Runtime) -- mainJars
    val testJars = {
      testPhases.flatMap {
        phase => jarsFor(phase) ++ unmanagedJarsFor(phase)
      }
    } -- mainJars
    val jarSrcs = testPhases.flatMap(jarSrcsFor)
    val jarDocs = testPhases.flatMap(jarDocsFor) ++ myDoc

    EnsimeModule(
      project.id, mainSources, testSources, mainTarget, testTargets, deps,
      mainJars, runtimeJars, testJars, jarSrcs, jarDocs
    )
  }

  // WORKAROUND: https://github.com/typelevel/scala/issues/75
  lazy val JdkDir: File = List(
    // manual
    sys.env.get("JDK_HOME"),
    sys.env.get("JAVA_HOME"),
    // osx
    Try("/usr/libexec/java_home".!!.trim).toOption,
    // fallback
    sys.props.get("java.home").map(new File(_).getParent),
    sys.props.get("java.home")
  ).flatten.filter { n =>
      new File(n + "/lib/tools.jar").exists
    }.headOption.map(new File(_)).getOrElse(
      throw new FileNotFoundException(
        """Could not automatically find the JDK/lib/tools.jar.
      |You must explicitly set JDK_HOME or JAVA_HOME.""".stripMargin
      )
    )
}
