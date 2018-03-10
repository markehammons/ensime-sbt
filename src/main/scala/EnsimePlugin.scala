// Copyright (C) 2014 - 2016 ENSIME Contributors
// Licence: Apache-2.0
package org.ensime

import java.io.FileNotFoundException
import java.lang.management.ManagementFactory

import org.ensime.SExpFormatter._
import sbt.IO._
import sbt.Keys._
import sbt._
import sbt.internal.BuildStructure
import sbt.librarymanagement.ConfigurationFilter

import scala.collection.JavaConverters._
import scala.util._
import scala.util.control.NoStackTrace

/**
 * Conventional way to define importable keys for an AutoPlugin.
 */
object EnsimeKeys {
  val ensimeServerIndex = taskKey[Unit](
    "Start up the ENSIME server and index your project."
  )

  val ensimeConfig = inputKey[Unit](
    "Generate a .ensime for the project."
  )
  val ensimeConfigProject = taskKey[Unit](
    "Generate a project/.ensime for the project definition."
  )

  val ensimeServerVersion = taskKey[String](
    "The ensime server version"
  )
  val ensimeProjectServerVersion = settingKey[String](
    "The ensime server version for the build project"
  )

  val ensimeConfigLegacy = settingKey[Boolean](
    "Should legacy fields be contained in .ensime"
  )

  val ensimeName = settingKey[String](
    "Name of the ENSIME project"
  )
  val ensimeScalacOptions = taskKey[Seq[String]](
    "Arguments for the scala presentation compiler, extracted from the compiler flags."
  )
  val ensimeJavacOptions = taskKey[Seq[String]](
    "Arguments for the java presentation compiler, extracted from the compiler flags."
  )

  val ensimeIgnoreSourcesInBase = settingKey[Boolean](
    "ENSIME doesn't support sources in base, this tolerates their existence."
  )
  val ensimeIgnoreMissingDirectories = settingKey[Boolean](
    "ENSIME requires declared source directories to exist. If you find this noisy, ignore them."
  )
  val ensimeIgnoreScalaMismatch = settingKey[Boolean](
    "ENSIME can only use one version of scala for the entire build. If you have mixed scala versions (e.g. lib and sbt plugin), set this to acknowledge."
  )

  val ensimeJavaFlags = taskKey[Seq[String]](
    "Flags to be passed to ENSIME JVM process."
  )
  val ensimeJavaHome = settingKey[File](
    "The java home directory to be used by the ENSIME JVM process."
  )
  val ensimeScalaVersion = taskKey[String](
    "Version of scala for the ENSIME JVM process."
  )

  val ensimeScalaJars = taskKey[Seq[File]](
    "The scala compiler's jars."
  )
  val ensimeScalaProjectJars = taskKey[Seq[File]](
    "The build's scala compiler's jars."
  )

  val ensimeServerJars = taskKey[Seq[File]](
    "The ensime-server's jars."
  )
  val ensimeServerProjectJars = taskKey[Seq[File]](
    "The build's ensime-server's jars."
  )

  val ensimeUseTarget = taskKey[Option[File]](
    "Use a calculated jar instead of the class directory. " +
      "Note that `proj/compile` does not produce the jar, change your workflow to use `proj/packageBin`."
  )

  // used to start the REPL and assembly jar bundles of ensime-server.
  // intransitive because we don't need parser combinators, scala.xml or jline
  val ensimeScalaCompilerJarModuleIDs = settingKey[Seq[ModuleID]](
    "The artefacts to resolve for :scala-compiler-jars in ensimeConfig."
  )

  val ensimeProjectScalacOptions = taskKey[Seq[String]](
    "Arguments for the project definition presentation compiler (not possible to extract)."
  )

  val ensimeUnmanagedSourceArchives = taskKey[Seq[File]](
    "Source jars (and zips) to complement unmanagedClasspath. May be set for the project and its submodules."
  )
  val ensimeUnmanagedJavadocArchives = taskKey[Seq[File]](
    "Documentation jars (and zips) to complement unmanagedClasspath. May only be set for submodules."
  )

  val ensimeMegaUpdate = taskKey[Map[ProjectRef, (UpdateReport, UpdateReport)]](
    "Runs the aggregated UpdateReport for `update' and `updateClassifiers' respectively."
  )
  val ensimeConfigTransformer = settingKey[EnsimeConfig => EnsimeConfig](
    "A function that is applied to a generated ENSIME configuration. This transformer function " +
      "can be used to add or filter any resulting config and can serve as a hook for other plugins."
  )
  val ensimeConfigTransformerProject = settingKey[EnsimeConfig => EnsimeConfig](
    "A function that is applied to a generated ENSIME project config. Equivalent of 'configTransformer' task, " +
      "on the build level."
  )

  val ensimeCachePrefix = settingKey[Option[File]](
    "Instead of putting the cache directory in the base directory, put it under this directory. " +
      "Useful if you want to put on a faster (local or RAM). " +
      "Will break clients that expect the cache to be in the project root."
  )

  val ensimeSnapshot = taskKey[Unit]("Copy the cache to a snapshot directory for later recovery.")
  val ensimeRestore = taskKey[Unit]("Replace the cache with the snapshot.")
  val ensimeClearCache = taskKey[Unit]("Clear the contents of cache.")
}

object EnsimePlugin extends AutoPlugin {
  import CommandSupport._

  // ensures compiler settings are loaded before us
  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements
  val autoImport = EnsimeKeys
  import autoImport._

  override lazy val buildSettings = Seq(
    // WORKAROUND: https://github.com/sbt/sbt/issues/2814
    scalaOrganization in updateSbtClassifiers := (scalaOrganization in Global).value,

    ensimeConfigLegacy := true,

    ensimeScalaVersion := {
      // infer the scalaVersion by majority vote, because many badly
      // written builds will forget to set the scalaVersion for the
      // root project. And we can't ask for (scalaVersion in
      // ThisBuild) because very few projects do it The Right Way.
      implicit val struct = Project.structure(state.value)

      val scalaVersions = struct.allProjectRefs.map { implicit p =>
        scalaVersion.value
      }.groupBy(identity).map { case (sv, svs) => sv -> svs.size }.toList

      scalaVersions.sortWith { case ((_, c1), (_, c2)) => c1 < c2 }.head._1
    },

    ensimeServerVersion := {
      CrossVersion.partialVersion(ensimeScalaVersion.value) match {
        case Some((2, 10)) => "2.0.0" // 3.0 drops scala 2.10 support
        case _             => "2.0.0"
      }
    },
    ensimeProjectServerVersion := "2.0.0", // should really filter on sbtVersion

    ensimeIgnoreSourcesInBase := false,
    ensimeIgnoreMissingDirectories := false,
    ensimeIgnoreScalaMismatch := false,

    ensimeCachePrefix := None,
    ensimeSnapshot := ensimeSnapshotTask.value,
    ensimeRestore := ensimeRestoreTask.value,
    ensimeClearCache := ensimeClearCacheTask.value,

    // WORKAROUND: https://github.com/scala/scala/pull/5592
    ensimeJavaFlags := baseJavaFlags(ensimeServerVersion.value) :+ "-Dscala.classpath.closeZip=true",
    ensimeJavaHome := javaHome.value.getOrElse(JdkDir),
    // unable to infer the user's scalac options: https://github.com/ensime/ensime-sbt/issues/98
    ensimeProjectScalacOptions := ensimeSuggestedScalacOptions(Properties.versionNumberString),
    ensimeMegaUpdate := Keys.state.flatMap { implicit s =>

      def checkCoursier(): Unit = {
        val structure = Project.extract(s).structure
        val plugins = structure.allProjects.flatMap(_.autoPlugins).map(_.getClass.getName)
        val usesCoursier = plugins.exists(_.contains("CoursierPlugin"))
        if (!usesCoursier) {
          log.warn(
            "SBT is using ivy to resolve dependencies which is known to be slow. " +
              "Coursier is recommended: http://get-coursier.io"
          )
        }
      }

      val projs = Project.structure(s).allProjectRefs
      log.info("ENSIME update.")
      for {
        updateReport <- update.forAllProjects(s, projs)
        _ = checkCoursier()
        updateClassifiersReport <- updateClassifiers.forAllProjects(s, projs)
      } yield {
        projs.map { p =>
          (p, (updateReport(p), updateClassifiersReport(p)))
        }.toMap
      }
    }.value
  )

  override lazy val projectSettings = Seq(
    aggregate in ensimeServerIndex := false,
    ensimeServerIndex := ensimeServerIndexTask.value,

    ensimeUnmanagedSourceArchives := Nil,
    ensimeUnmanagedJavadocArchives := Nil,
    ensimeConfigTransformer := identity,
    ensimeConfigTransformerProject := identity,
    ensimeUseTarget := None,

    ensimeScalacOptions := Nil,
    ensimeJavacOptions := (javacOptions in Compile).value,

    ensimeConfig := ensimeConfigTask.evaluated,
    ensimeConfigProject := ensimeConfigProjectTask.value,

    aggregate in ensimeConfig := false,
    aggregate in ensimeConfigProject := false
  )

  private def cacheAndSnapshot(prefix: Option[File]) = {
    val cache = cacheDir(prefix, file("."))
    val snapshot = file(".") / ".ensime_snapshot"
    (cache, snapshot)
  }

  def ensimeSnapshotTask: Def.Initialize[Task[Unit]] = Def.task {
    val (cache, snapshot) = cacheAndSnapshot(ensimeCachePrefix.value)
    if (!cache.isDirectory)
      throw new IllegalStateException(s"$cache is not valid")
    IO.delete(snapshot)
    IO.copyDirectory(cache, snapshot)
  }

  def ensimeRestoreTask: Def.Initialize[Task[Unit]] = Def.task {
    val (cache, snapshot) = cacheAndSnapshot(ensimeCachePrefix.value)
    if (!snapshot.isDirectory)
      throw new IllegalStateException(s"$snapshot is not valid")
    IO.delete(cache)
    IO.copyDirectory(snapshot, cache)
  }

  def ensimeClearCacheTask: Def.Initialize[Task[Unit]] = Def.task {
    val (cache, snapshot) = cacheAndSnapshot(ensimeCachePrefix.value)
    if (!cache.isDirectory)
      throw new IllegalStateException(s"$cache is not valid")
    IO.delete(cache)
  }

  // exposed for users to use
  def ensimeSuggestedScalacOptions(scalaVersion: String): Seq[String] = Seq(
    "-feature",
    "-deprecation",
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Xfuture"
  ) ++ {
      CrossVersion.partialVersion(scalaVersion) match {
        case Some((2, 10))           => Seq("-Ymacro-no-expand")
        case Some((2, v)) if v >= 11 => Seq("-Ymacro-expand:discard")
        case _                       => Nil
      }
    }

  def ensimeServerIndexTask: Def.Initialize[Task[Unit]] = Def.task {
    val javaH = ensimeJavaHome.value
    val java = javaH / "bin/java"
    val scalaCompilerJars = ensimeScalaJars.value.toSet
    val jars = ensimeServerJars.value.toSet ++ scalaCompilerJars + javaH / "lib/tools.jar"
    val jvmFlags = ensimeJavaFlags.value ++ Seq("-Densime.config=.ensime", "-Densime.exitAfterIndex=true")
    val cache = cacheDir(ensimeCachePrefix.value, file("."))

    cache.mkdirs()

    val options = ForkOptions(
      Some(javaH),
      None,
      Vector.empty,
      None,
      runJVMOptions = jvmFlags.toVector,
      false,
      Map.empty[String, String]
    )
    SbtHelper.reportError(new ForkRun(options).run(
      "org.ensime.server.Server",
      orderFiles(jars),
      Nil,
      streams.value.log
    ))
  }

  def ensimeConfigTask = Def.inputTask {
    val args = Def.spaceDelimited().parsed

    val extracted = Project.extract(state.value)
    implicit val st = state.value
    implicit val bs = extracted.structure

    val allProjects = thisProject.all(ScopeFilter(inAnyProject, inAnyConfiguration)).value
    val allProjectRefs = thisProjectRef.all(ScopeFilter(inAnyProject, inAnyConfiguration)).value

    var transitiveCache = Map.empty[ProjectRef, Set[ProjectRef]]
    def transitiveProjects(ref: ProjectRef): Set[ProjectRef] = {
      if (transitiveCache.contains(ref))
        transitiveCache(ref)
      else {
        val proj = allProjects.find(_.id == ref.project).get
        val deps = Set(ref) ++ proj.dependencies.flatMap { dep =>
          transitiveProjects(dep.project)
        }
        transitiveCache += ref -> deps
        deps
      }
    }

    val active =
      if (args.isEmpty) allProjectRefs
      else args.flatMap { name =>
        val ref = allProjectRefs.find(_.project == name).getOrElse {
          throw new IllegalArgumentException(s"$name is not a valid project id")
        }
        transitiveProjects(ref)
      }

    val projects = active.flatMap { ref =>
      allProjects.find(_.id == ref.project).map((ref, _))
    }.toMap

    val updateReports = ensimeMegaUpdate.value

    val javaH = ensimeJavaHome.value
    val scalaCompilerJars = ensimeScalaJars.value.toSet
    val serverJars = ensimeServerJars.value.toSet -- scalaCompilerJars + javaH / "lib/tools.jar"
    val serverVersion = ensimeServerVersion.value

    // for some reason this gives the wrong number in projectData
    val ensimeScalaV = (ensimeScalaVersion in ThisBuild).value

    implicit val rawProjects = projects.flatMap {
      case (ref, proj) =>
        val (updateReport, updateClassifiersReport) = updateReports(ref)
        val projects = projectData(ensimeScalaV, proj, updateReport, updateClassifiersReport)(ref, bs, st)
        projects.map { p => (p.id, p) }
    }.toMap

    val subProjects: Seq[EnsimeProject] = rawProjects.toSeq.map {
      case (_, p) =>
        val deps = p.depends
        // restrict jars to immediate deps at each module
        p.copy(
          libraryJars = (p.libraryJars -- deps.flatMap(_.libraryJars)).filter(jarOrZipFile),
          librarySources = (p.librarySources -- deps.flatMap(_.librarySources)).filter(jarOrZipFile),
          libraryDocs = (p.libraryDocs -- deps.flatMap(_.libraryDocs)).filter(jarOrZipFile)
        )
    }

    val root = file(Properties.userDir)
    val out = file(".ensime")
    val name = (ensimeName).?.value.getOrElse {
      if (subProjects.size == 1) subProjects.head.id.project
      else root.getAbsoluteFile.getName
    }
    val compilerArgs = ((scalacOptions in Compile).value ++
      ensimeSuggestedScalacOptions(ensimeScalaV) ++
      (ensimeScalacOptions in Compile).value).toList
    val javaCompilerArgs = (ensimeJavacOptions).value.toList
    val javaSrc = {
      file(javaH.getAbsolutePath + "/src.zip") match {
        case f if f.exists => Set(f)
        case _ =>
          state.value.log.warn(s"No Java sources detected in $javaH (your ENSIME experience will not be as good as it could be.)")
          Set.empty
      }
    } ++ ensimeUnmanagedSourceArchives.value

    val javaFlags = ensimeJavaFlags.value.toList

    val scalaVersion = (ensimeScalaVersion in ThisBuild).value

    val modules = subProjects.groupBy(_.id.project).mapValues(ensimeProjectsToModule)

    val config = EnsimeConfig(
      root, cacheDir(ensimeCachePrefix.value, root),
      scalaCompilerJars, serverJars, serverVersion,
      name, scalaVersion, compilerArgs,
      modules, javaH, javaFlags, javaCompilerArgs, javaSrc, subProjects
    )

    val transformedConfig = ensimeConfigTransformer.value.apply(config)
    val legacy = ensimeConfigLegacy.value

    // workaround for Windows
    write(out, toSExp(transformedConfig, legacy).replaceAll("\r\n", "\n") + "\n")
  }

  // WORKAROUND: https://github.com/ensime/ensime-sbt/issues/334 when
  // users have the coursier plugin (1.0.0-RC2) the list contains
  // signatures and maybe other files we don't want
  private def jarOrZipFile(file: sbt.File): Boolean = {
    val (_, ext) = file.baseAndExt
    ext == "zip" || ext == "jar"
  }

  private def ensureCreatedOrIgnore(ignore: Boolean, log: Logger)(sources: Set[File]): Set[File] = {
    sources.collect {
      case dir if dir.isDirectory() => dir
      case dir if !ignore =>
        log.info(s"""Creating $dir. Read about `ensimeIgnoreMissingDirectories`""")
        dir.mkdirs()
        dir
    }
  }

  def projectData(
    ensimeScalaV: String,
    project: ResolvedProject,
    updateReport: UpdateReport,
    updateClassifiersReport: UpdateReport
  )(
    implicit
    projectRef: ProjectRef,
    buildStruct: BuildStructure,
    state: State
  ): Seq[EnsimeProject] = {
    log.info(s"ENSIME processing ${project.id} (${name.gimme})")

    val builtInTestPhases = Set(Test, IntegrationTest)
    val testPhases = {
      for {
        phase <- ivyConfigurations.gimme
        if phase.isPublic
        if builtInTestPhases(phase) || builtInTestPhases.intersect(phase.extendsConfigs.toSet).nonEmpty
      } yield phase
    }.toSet

    def sourcesFor(config: Configuration) = {
      // invoke source generation so we can filter on existing directories
      (managedSources in config).runOpt
      (managedSourceDirectories in config).gimmeOpt.map(_.toSet).getOrElse(Set()) ++
        (unmanagedSourceDirectories in config).gimmeOpt.getOrElse(Set())
    }

    def targetForOpt(config: Configuration): Option[File] =
      (ensimeUseTarget in config).runOpt match {
        case Some(Some(jar)) => Some(jar)
        case _               => (classDirectory in config).gimmeOpt
      }

    def configFilter(config: Configuration): ConfigurationFilter = {
      val c = config.name.toLowerCase
      if (sbtPlugin.gimme) configurationFilter("provided" | c)
      else configurationFilter(c)
    }

    def jarsFor(config: Configuration) = updateReport.select(
      configuration = configFilter(config),
      moduleFilter(),
      artifact = artifactFilter(extension = Artifact.DefaultExtension)
    ).toSet

    def unmanagedJarsFor(config: Configuration) =
      (unmanagedJars in config).runOpt.map(_.map(_.data).toSet).getOrElse(Set())

    def jarSrcsFor(config: Configuration) = updateClassifiersReport.select(
      configuration = configFilter(config),
      moduleFilter(),
      artifact = artifactFilter(classifier = Artifact.SourceClassifier)
    ).toSet ++ (ensimeUnmanagedSourceArchives in config in projectRef).run

    def jarDocsFor(config: Configuration) = updateClassifiersReport.select(
      configuration = configFilter(config),
      moduleFilter(),
      artifact = artifactFilter(classifier = Artifact.DocClassifier)
    ).toSet ++ (ensimeUnmanagedJavadocArchives in config in projectRef).run

    val IvyConfig = "([A-Za-z]+)->([A-Za-z]+)".r

    def depsFor(config: Configuration): Seq[EnsimeProjectId] = project.dependencies.flatMap { d =>
      lazy val name = d.project.project
      d.configuration match {
        case Some(a) => a.split(";|,").collect {
          case IvyConfig(from, to) if (from == config.name) => EnsimeProjectId(name, to)
          case b if (b == config.name) => EnsimeProjectId(name, "compile")
        }
        case None => if (config.name == "compile") Array(EnsimeProjectId(name, "compile")) else Nil
      }
    }

    def configDataFor(config: Configuration): EnsimeProject = {
      val sbv = scalaBinaryVersion.gimme
      val sources = ensureCreatedOrIgnore(ensimeIgnoreMissingDirectories.gimme, log) {
        config match {
          case Compile => sourcesFor(Compile) ++ sourcesFor(Provided) ++ sourcesFor(Optional)
          case _       => sourcesFor(config)
        }
      }

      val target = targetForOpt(config).get
      val scalaCompilerArgs = ((scalacOptions in config).run ++
        ensimeSuggestedScalacOptions(ensimeScalaV) ++
        (ensimeScalacOptions in config).run).toList
      val javaCompilerArgs = (javacOptions in config).run.toList
      val jars = config match {
        case Compile => jarsFor(Compile) ++ unmanagedJarsFor(Compile) ++ jarsFor(Provided) ++ jarsFor(Optional)
        case _       => jarsFor(config) ++ unmanagedJarsFor(config)
      }

      val jarSrcs = config match {
        case Compile => Seq(Provided, Compile).flatMap(jarSrcsFor)
        case _       => jarSrcsFor(config)
      }

      val jarDocs = config match {
        case Compile =>
          Seq(Provided, Compile).flatMap(jarDocsFor) ++
            (artifactPath in (Compile, packageDoc)).gimmeOpt.toList
        case _ => jarDocsFor(config)
      }

      val id = EnsimeProjectId(project.id, config.name)
      val allDeps = depsFor(config) ++ config.extendsConfigs.collect {
        case parent if parent.isPublic =>
          val name = if (parent.name == "runtime") "compile" else parent.name
          EnsimeProjectId(project.id, name)
      }

      EnsimeProject(id, allDeps, sources, Set(target), scalaCompilerArgs, javaCompilerArgs, jars, jarSrcs.toSet, jarDocs.toSet)
    }

    if (scalaVersion.gimme != ensimeScalaV) {
      log.warn(
        s"""You have a different version of scala for ENSIME ($ensimeScalaV) and ${project.id} (${scalaVersion.gimme}).
           |If this is not what you intended, try fixing your build with:
           |  scalaVersion in ThisBuild := "${scalaVersion.gimme}"
           |in your build.sbt, or override the ENSIME scala version with:
           |  ensimeScalaVersion in ThisBuild := "${scalaVersion.gimme}"
           |in a ensime.sbt: http://ensime.org/build_tools/sbt/#customise""".stripMargin
      )
      if (!ensimeIgnoreScalaMismatch.gimme)
        throw new IllegalStateException(
          s"""To ignore this error (i.e. you have multiple scala versions), add `ensimeIgnoreScalaMismatch in ThisBuild := true` or `ensimeIgnoreScalaMismatch in LocalProject("${project.id}") := true` to your ensime.sbt"""
        ) with NoStackTrace
    }

    if (sourcesInBase.gimme) {
      val sources = baseDirectory.gimme.list().filter(_.endsWith(".scala"))
      if (sources.nonEmpty) {
        log.warn(
          s"""You have .scala files in the base of your project. Such "script style" projects
             |are not supported by ENSIME. Move them into src/main/scala to get support.
             |Please read https://github.com/ensime/ensime-server/issues/1432""".stripMargin
        )
        if (!ensimeIgnoreSourcesInBase.gimme) {
          throw new IllegalStateException("To ignore this error, customise `ensimeIgnoreSourcesInBase`") with NoStackTrace
        }
      }
    }

    (testPhases + Compile).map(configDataFor).toSeq
  }

  private def ensimeProjectsToModule(p: Iterable[EnsimeProject]): EnsimeModule = {
    val name = p.head.id.project
    val deps = for {
      s <- p
      d <- s.depends
      if d.project != name
    } yield d.project
    val (mains, tests) = p.toSet.partition(_.id.config == "compile")
    val mainSources = mains.flatMap(_.sources)
    val mainTargets = mains.flatMap(_.targets)
    val mainJars = mains.flatMap(_.libraryJars)
    val testSources = tests.flatMap(_.sources)
    val testTargets = tests.flatMap(_.targets)
    val testJars = tests.flatMap(_.libraryJars).toSet -- mainJars
    val sourceJars = p.flatMap(_.librarySources).toSet
    val docJars = p.flatMap(_.libraryDocs).toSet
    EnsimeModule(
      name, mainSources, testSources, mainTargets, testTargets, deps.toSet,
      mainJars, Set.empty, testJars, sourceJars, docJars
    )
  }

  def ensimeConfigProjectTask = Def.task {
    val extracted = Project.extract(state.value)

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
      config <- updateSbtClassifiers.value.configurations
      module <- config.modules
      artefact <- module.artifacts
      if jarOrZipFile(artefact._2)
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
    val name = ensimeName.?.value.getOrElse {
      file(Properties.userDir).getName
    } + "-project"

    val compilerArgs = ensimeProjectScalacOptions.value.toList
    val scalaV = Properties.versionNumberString
    val javaH = ensimeJavaHome.value
    val javaSrc = javaH / "src.zip" match {
      case f if f.exists => Set(f)
      case _             => Set.empty[File]
    }
    val javaFlags = ensimeJavaFlags.value.toList

    val id = EnsimeProjectId(name, "compile")
    val proj = EnsimeProject(id, Nil, Set(root), targets.toSet, Nil, Nil, jars.toSet, srcs.toSet, docs.toSet)
    val module = ensimeProjectsToModule(Set(proj))

    val scalaCompilerJars = ensimeScalaProjectJars.value.toSet
    val serverJars = ensimeServerProjectJars.value.toSet -- scalaCompilerJars + javaH / "lib/tools.jar"
    val serverVersion = ensimeProjectServerVersion.value

    val config = EnsimeConfig(
      root, cacheDir(ensimeCachePrefix.value, root),
      scalaCompilerJars, serverJars, serverVersion,
      name, scalaV, compilerArgs,
      Map(module.name -> module), javaH, javaFlags, Nil, javaSrc,
      Seq(proj)
    )

    val transformedConfig = ensimeConfigTransformerProject.value.apply(config)
    val legacy = ensimeConfigLegacy.value

    write(out, toSExp(transformedConfig, legacy).replaceAll("\r\n", "\n") + "\n")
  }

  // WORKAROUND: https://github.com/typelevel/scala/issues/75
  lazy val JdkDir: File = List(
    // manual
    sys.env.get("JDK_HOME"),
    sys.env.get("JAVA_HOME"),
    // fallback
    sys.props.get("java.home").map(new File(_).getParent),
    sys.props.get("java.home"),
    // osx
    Try(sys.process.Process("/usr/libexec/java_home").!!.trim).toOption
  ).flatten/*.filter { n =>
      new File(n + "/lib/tools.jar").exists
    }*/.headOption.map(new File(_).getCanonicalFile).getOrElse(
      throw new FileNotFoundException(
        """Could not automatically find the JDK/lib/tools.jar.
      |You must explicitly set JDK_HOME or JAVA_HOME.""".stripMargin
      )
    )

  def baseJavaFlags(serverVersion: String) = {
    val raw = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toList

    // WORKAROUND https://github.com/ensime/ensime-sbt/issues/91
    // WORKAROUND https://github.com/ensime/ensime-server/issues/1756
    val StackSize = "-Xss[^ ]+".r
    val MinHeap = "-Xms[^ ]+".r
    val MaxHeap = "-Xmx[^ ]+".r
    val MaxPerm = "-XX:MaxPermSize=[^ ]+".r
    val corrected = raw.filter {
      case StackSize() => false
      case MinHeap()   => false
      case MaxHeap()   => false
      case MaxPerm()   => false
      case other       => true
    }
    val memory = Seq(
      "-Xss2m",
      "-Xms512m",
      "-Xmx4g"
    )

    val server = serverVersion.substring(0, 3)
    val java = sys.props("java.version").substring(0, 3)
    val versioned = (java, server) match {
      case ("1.6" | "1.7", _) => Seq(
        "-XX:MaxPermSize=256m"
      )
      case _ => List(
        "-XX:MaxMetaspaceSize=256m",
        // these improve ensime-server performance
        "-XX:StringTableSize=1000003",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:SymbolTableSize=1000003"
      )
    }

    corrected ++ memory ++ versioned
  }

  private def cacheDir(prefix: Option[File], base: File): File = prefix match {
    case None      => base / ".ensime_cache"
    case Some(pre) => pre / s"${base.getCanonicalPath}/.ensime_cache"
  }

  // normalise and ensure monkeys go first
  // (bit of a hack to do it here, maybe best when creating)
  private[ensime] def orderFiles(ss: Iterable[File]): List[File] = {
    val (monkeys, humans) = ss.toList.distinct.sortBy { f =>
      f.getName + f.getPath
    }.partition(_.getName.contains("monkey"))
    monkeys ::: humans
  }

}

case class EnsimeConfig(
  root: File,
  cacheDir: File,
  scalaCompilerJars: Set[File],
  ensimeServerJars: Set[File],
  ensimeServerVersion: String,
  name: String,
  scalaVersion: String,
  scalacOptions: List[String], // 1.0
  modules: Map[String, EnsimeModule], // 1.0
  javaHome: File,
  javaFlags: List[String],
  javacOptions: List[String], // 1.0
  javaSrc: Set[File],
  projects: Seq[EnsimeProject]
)

// 1.0
case class EnsimeModule(
  name: String,
  mainRoots: Set[File],
  testRoots: Set[File],
  targets: Set[File],
  testTargets: Set[File],
  dependsOnNames: Set[String],
  compileJars: Set[File],
  runtimeJars: Set[File],
  testJars: Set[File],
  sourceJars: Set[File],
  docJars: Set[File]
) {

  def dependencies(implicit lookup: String => EnsimeModule): Set[EnsimeModule] =
    dependsOnNames map lookup

}

case class EnsimeProjectId(
  project: String,
  config: String
)

case class EnsimeProject(
  id: EnsimeProjectId,
  depends: Seq[EnsimeProjectId],
  sources: Set[File],
  targets: Set[File],
  scalacOptions: List[String],
  javacOptions: List[String],
  libraryJars: Set[File],
  librarySources: Set[File],
  libraryDocs: Set[File]
)

object CommandSupport {
  private def fail(errorMessage: String)(implicit state: State): Nothing = {
    state.log.error(errorMessage)
    throw new IllegalArgumentException()
  }

  def log(implicit state: State) = state.log

  // our version of http://stackoverflow.com/questions/25246920
  implicit class RichSettingKey[A](key: SettingKey[A]) {
    def gimme(implicit pr: ProjectRef, bs: BuildStructure, s: State): A =
      gimmeOpt getOrElse { fail(s"Missing setting: ${key.key.label}") }
    def gimmeOpt(implicit pr: ProjectRef, bs: BuildStructure): Option[A] =
      key in pr get bs.data
  }

  implicit class RichTaskKey[A](key: TaskKey[A]) {
    def run(implicit pr: ProjectRef, bs: BuildStructure, s: State): A =
      runOpt.getOrElse { fail(s"Missing task key: ${key.key.label}") }
    def runOpt(implicit pr: ProjectRef, bs: BuildStructure, s: State): Option[A] =
      EvaluateTask(bs, key, s, pr).map(_._2) match {
        case Some(Value(v)) => Some(v)
        case _              => None
      }

    def forAllProjects(state: State, projects: Seq[ProjectRef]): Task[Map[ProjectRef, A]] = {
      val tasks = projects.flatMap(p => key.in(p).get(Project.structure(state).data).map(_.map(it => (p, it))))
      std.TaskExtra.joinTasks(tasks).join.map(_.toMap)
    }
  }

}

// direct formatter to deal with a small number of domain objects
// if we had to do this for general objects, it would make sense
// to create a series of implicit convertors to an SExp hierarchy
object SExpFormatter {

  def toSExp(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  def toSExp(f: File): String = toSExp(f.getAbsolutePath)

  def fsToSExp(ss: Iterable[File]): String =
    if (ss.isEmpty) "nil"
    else EnsimePlugin.orderFiles(ss).map(toSExp).mkString("(", " ", ")")

  def ssToSExp(ss: Iterable[String]): String =
    if (ss.isEmpty) "nil"
    else ss.toSeq.map(toSExp).mkString("(", " ", ")")

  def msToSExp(ss: Iterable[EnsimeModule]): String =
    if (ss.isEmpty) "nil"
    else ss.toSeq.sortBy(_.name).map(toSExp).mkString("(", " ", ")")

  def psToSExp(ss: Iterable[EnsimeProject]): String =
    if (ss.isEmpty) "nil"
    else ss.toSeq.sortBy(_.id.toString).map(toSExp).mkString("(", " ", ")")

  def fToSExp(key: String, op: Option[File]): String =
    op.map { f => s":$key ${toSExp(f)}" }.getOrElse("")

  def sToSExp(key: String, op: Option[String]): String =
    op.map { f => s":$key ${toSExp(f)}" }.getOrElse("")

  def toSExp(b: Boolean): String = if (b) "t" else "nil"

  // a lot of legacy key names and conventions
  def toSExp(c: EnsimeConfig, includeLegacy: Boolean): String = s"""(
 :root-dir ${toSExp(c.root)}
 :cache-dir ${toSExp(c.cacheDir)}
 :scala-compiler-jars ${fsToSExp(c.scalaCompilerJars)}
 :ensime-server-jars ${fsToSExp(c.ensimeServerJars)}
 :ensime-server-version ${toSExp(c.ensimeServerVersion)}
 :name "${c.name}"
 :java-home ${toSExp(c.javaHome)}
 :java-flags ${ssToSExp(c.javaFlags)}
 :java-sources ${fsToSExp(c.javaSrc)}
 :java-compiler-args ${if (!includeLegacy) "nil" else ssToSExp(c.javacOptions)}
 :reference-source-roots ${if (!includeLegacy) "nil" else fsToSExp(c.javaSrc)}
 :scala-version ${toSExp(c.scalaVersion)}
 :compiler-args ${ssToSExp(c.scalacOptions)}
 :subprojects ${if (!includeLegacy) "nil" else msToSExp(c.modules.values)}
 :projects ${psToSExp(c.projects)}
)"""

  // a lot of legacy key names and conventions
  def toSExp(m: EnsimeModule): String = s"""(
   :name ${toSExp(m.name)}
   :source-roots ${fsToSExp((m.mainRoots ++ m.testRoots))}
   :targets ${fsToSExp(m.targets)}
   :test-targets ${fsToSExp(m.testTargets)}
   :depends-on-modules ${ssToSExp(m.dependsOnNames.toList.sorted)}
   :compile-deps ${fsToSExp(m.compileJars)}
   :runtime-deps ${fsToSExp(m.runtimeJars)}
   :test-deps ${fsToSExp(m.testJars)}
   :doc-jars ${fsToSExp(m.docJars)}
   :reference-source-roots ${fsToSExp(m.sourceJars)})"""

  def toSExp(p: EnsimeProject): String = s"""(
    :id ${toSExp(p.id)}
    :depends ${idsToSExp(p.depends)}
    :sources ${fsToSExp(p.sources)}
    :targets ${fsToSExp(p.targets)}
    :scalac-options ${ssToSExp(p.scalacOptions)}
    :javac-options ${ssToSExp(p.javacOptions)}
    :library-jars ${fsToSExp(p.libraryJars)}
    :library-sources ${fsToSExp(p.librarySources)}
    :library-docs ${fsToSExp(p.libraryDocs)})"""

  def toSExp(id: EnsimeProjectId): String =
    s"""(:project ${toSExp(id.project)} :config ${toSExp(id.config)})"""

  def idsToSExp(ids: Iterable[EnsimeProjectId]): String =
    if (ids.isEmpty) "nil"
    else ids.toSeq.sortBy(_.toString).map(toSExp).mkString("(", " ", ")")

}
