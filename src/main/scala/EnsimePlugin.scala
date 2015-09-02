package org.ensime

import Imports._
import SExpFormatter._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import java.io.FileNotFoundException
import java.lang.management.ManagementFactory
import sbt._
import sbt.IO._
import sbt.Keys._
import sbt.complete.Parsers._
import scala.collection.JavaConverters._
import scala.util._
import scalariform.formatter.preferences.IFormattingPreferences

/**
 * Conventional way to define importable keys for an AutoPlugin.
 * Note that EnsimePlugin.autoImport == Imports
 */
object Imports {
  object EnsimeKeys {
    val name = SettingKey[String]("Name of the ENSIME project")
    val debuggingFlag = SettingKey[String]("JVM flag to enable remote debugging of forked tasks.")
    val debuggingPort = SettingKey[Int]("Port for remote debugging of forked tasks.")
    val compilerArgs = TaskKey[Seq[String]]("Arguments for the presentation compiler, extracted from the compiler flags.")
    val additionalCompilerArgs = SettingKey[Seq[String]]("Additional arguments for the presentation compiler.")
  }
}

object EnsimePlugin extends AutoPlugin with CommandSupport {

  lazy val ensimeCommand = Command.command(
    "gen-ensime",
    "Generate a .ensime for the project.", ""
  )(genEnsime)
  lazy val ensimeProjectCommand = Command.command(
    "gen-ensime-project",
    "Generate a project/.ensime for the build definition.", ""
  )(genEnsimeProject)

  lazy val debuggingOnCommand = Command.command(
    "debugging",
    "Add debugging flags to all forked JVM processes.", ""
  )(toggleDebugging(true))
  // it would be good if debugging-off was automatically triggered
  // https://stackoverflow.com/questions/32350617
  lazy val debuggingOffCommand = Command.command(
    "debugging-off",
    "Remove debugging flags from all forked JVM processes.", ""
  )(toggleDebugging(false))

  // ensures compiler settings are loaded before us
  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  val autoImport = Imports

  override lazy val projectSettings = Seq(
    // people expect C-c to Do The Right Thing in debugging
    // http://stackoverflow.com/questions/5137460/
    cancelable in Global := true,
    commands ++= Seq(
      ensimeCommand, ensimeProjectCommand,
      debuggingOnCommand, debuggingOffCommand
    ),
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
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, v)) if v >= 11 => Seq("-Ywarn-unused-import")
          case _                       => Nil
        }
      }
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

    val formatting = (ScalariformKeys.preferences in Compile).gimmeOpt

    val config = EnsimeConfig(
      root, cacheDir, name, scalaV, compilerArgs,
      modules, javaH, JavaFlags, javaSrc, formatting
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

    // these are really slow to run, so try to minimise their invocations
    val updateReport = testPhases.flatMap { phase =>
      // the test reports include the "main" report
      // optimisation: don't run extended phases if we don't have a source root
      if (phase == Test || sourcesFor(phase).nonEmpty) (update in phase).runOpt
      else Set.empty
    }
    val updateClassifiersReports = {
      testPhases.flatMap { phase =>
        if (phase == Test || sourcesFor(phase).nonEmpty) (updateClassifiers in phase).runOpt
        else Set.empty
      }
    }

    val myDoc = (artifactPath in (Compile, packageDoc)).gimmeOpt

    val filter = if (sbtPlugin.gimme) "provided" else ""

    def jarsFor(config: Configuration) = updateReport.flatMap(_.select(
      configuration = configurationFilter(filter | config.name.toLowerCase),
      artifact = artifactFilter(classifier = "")
    )).toSet

    def unmanagedJarsFor(config: Configuration) =
      (unmanagedJars in config).runOpt.map(_.map(_.data).toSet).getOrElse(Set())

    def jarSrcsFor(config: Configuration) = updateClassifiersReports.flatMap(_.select(
      configuration = configurationFilter(filter | config.name.toLowerCase),
      artifact = artifactFilter(classifier = Artifact.SourceClassifier)
    )).toSet

    def jarDocsFor(config: Configuration) = updateClassifiersReports.flatMap(_.select(
      configuration = configurationFilter(filter | config.name.toLowerCase),
      artifact = artifactFilter(classifier = Artifact.DocClassifier)
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
      project.id, mainSources, testSources, Set(mainTarget), testTargets, deps,
      mainJars, runtimeJars, testJars, jarSrcs, jarDocs
    )
  }

  def genEnsimeProject: State => State = { implicit state: State =>
    val extracted = Project.extract(state)

    implicit val pr = extracted.currentRef
    implicit val bs = extracted.structure

    val jars = for {
      unit <- bs.units
      file <- unit._2.classpath
      if !file.isDirectory() & file.getName.endsWith(Artifact.DefaultExtension)
    } yield file

    val targets = for {
      unit <- bs.units
      dir <- unit._2.classpath
      if dir.isDirectory()
    } yield dir

    val classifiers = for {
      config <- updateSbtClassifiers.run.configurations
      module <- config.modules
      artefact <- module.artifacts
    } yield artefact

    val srcs = classifiers.collect {
      case (artefact, file) if artefact.classifier == Some(Artifact.SourceClassifier) => file
    }
    // they don't seem to publish docs...
    val docs = classifiers.collect {
      case (artefact, file) if artefact.classifier == Some(Artifact.DocClassifier) => file
    }

    val root = file(Properties.userDir) / "project"
    val out = root / ".ensime"
    val cacheDir = root / ".ensime_cache"
    val name = EnsimeKeys.name.gimmeOpt.getOrElse {
      file(Properties.userDir).getName + "-project"
    }
    val compilerArgs = {
      (EnsimeKeys.compilerArgs in Compile).run.toList ++
        (EnsimeKeys.additionalCompilerArgs in Compile).gimme
    }.distinct
    val scalaV = Properties.versionNumberString
    val javaSrc = JdkDir / "src.zip" match {
      case f if f.exists => Some(f)
      case _             => None
    }

    val formatting = (ScalariformKeys.preferences in Compile).gimmeOpt

    val module = EnsimeModule(
      name, Set(root), Set.empty, targets.toSet, Set.empty, Set.empty,
      jars.toSet, Set.empty, Set.empty, srcs.toSet, docs.toSet
    )

    val config = EnsimeConfig(
      root, cacheDir, name, scalaV, compilerArgs,
      Map(module.name -> module), JdkDir, JavaFlags, javaSrc, formatting
    )

    write(out, toSExp(config).replaceAll("\r\n", "\n") + "\n")

    state
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

  lazy val JavaFlags = {
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

}
