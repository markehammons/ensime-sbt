/*
 * Copyright (c) 2015, ENSIME Contributors
 *
 * All rights reserved. Redistribution and use in source and binary
 * forms, with or without modification, are permitted provided that
 * the following conditions are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   - Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *
 *   - Neither the name of ENSIME nor the names of its contributors
 *     may be used to endorse or promote products derived from this
 *     software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL AEMON
 * CANNON OR THE ENSIME CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ensime

import Imports._
import SExpFormatter._
import java.io.FileNotFoundException
import java.lang.management.ManagementFactory
import sbt._
import sbt.IO._
import sbt.Keys._
import sbt.complete.Parsers._
import scala.collection.JavaConverters._
import scala.util._
import scalariform.formatter.preferences._

/**
 * Conventional way to define importable keys for an AutoPlugin.
 * Note that EnsimePlugin.autoImport == Imports
 */
object Imports {
  object EnsimeKeys {
    // for gen-ensime
    val name = SettingKey[String](
      "Name of the ENSIME project"
    )
    val compilerArgs = TaskKey[Seq[String]](
      "Arguments for the presentation compiler, extracted from the compiler flags."
    )
    val additionalCompilerArgs = TaskKey[Seq[String]](
      "Additional arguments for the presentation compiler."
    )
    val includeSourceJars = settingKey[Boolean](
      "Should source jars be included in the .ensime file."
    )
    val includeDocJars = settingKey[Boolean](
      "Should doc jars be included in the .ensime file."
    )
    val scalariform = settingKey[IFormattingPreferences](
      "Scalariform formatting preferences to use in ENSIME."
    )

    // for gen-ensime-meta
    val compilerMetaArgs = TaskKey[Seq[String]](
      "Arguments for the meta-project presentation compiler (not possible to extract)."
    )
    val additionalMetaCompilerArgs = TaskKey[Seq[String]](
      "Additional arguments for the meta-project presentation compiler."
    )

    // for debugging
    val debuggingFlag = SettingKey[String](
      "JVM flag to enable remote debugging of forked tasks."
    )
    val debuggingPort = SettingKey[Int](
      "Port for remote debugging of forked tasks."
    )

    val unmanagedSourceArchives = SettingKey[Seq[File]](
      "Source jars (and zips) to complement unmanagedClasspath. May be set for the project and its submodules."
    )
    val unmanagedJavadocArchives = SettingKey[Seq[File]](
      "Documentation jars (and zips) to complement unmanagedClasspath. May only be set for submodules."
    )

    val megaUpdate = TaskKey[Map[ProjectRef, (UpdateReport, UpdateReport)]](
      "Runs the aggregated UpdateReport for `update' and `updateClassifiers' respectively."
    )

  }
}

object EnsimePlugin extends AutoPlugin with CommandSupport {
  // ensures compiler settings are loaded before us
  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  val autoImport = Imports

  override lazy val buildSettings = Seq(
    commands += Command.command("gen-ensime", "Generate a .ensime for the project.", "")(genEnsime),
    commands += Command.command("gen-ensime-meta", "Generate a project/.ensime for the meta-project.", "")(genEnsimeMeta),
    commands += Command.command("debugging", "Add debugging flags to all forked JVM processes.", "")(toggleDebugging(true)),
    commands += Command.command("debugging-off", "Remove debugging flags from all forked JVM processes.", "")(toggleDebugging(false)),
    EnsimeKeys.debuggingFlag := "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=",
    EnsimeKeys.debuggingPort := 5005,
    EnsimeKeys.compilerArgs := (scalacOptions in Compile).value,
    EnsimeKeys.additionalCompilerArgs := defaultCompilerFlags(scalaVersion.value),
    EnsimeKeys.compilerMetaArgs := Seq(), // https://github.com/ensime/ensime-sbt/issues/98
    EnsimeKeys.additionalMetaCompilerArgs := defaultCompilerFlags(Properties.versionNumberString),
    EnsimeKeys.scalariform := FormattingPreferences(),
    EnsimeKeys.megaUpdate <<= Keys.state.flatMap { implicit s =>
      val projs = Project.structure(s).allProjectRefs
      log.info("ENSIME update. Please vote for https://github.com/sbt/sbt/issues/2266")
      for {
        updateReport <- update.forAllProjects(s, projs)
        _ = log.info("ENSIME updateClassifiers. Please vote for https://github.com/sbt/sbt/issues/1930")
        updateClassifiersReport <- updateClassifiers.forAllProjects(s, projs)
      } yield {
        projs.map { p =>
          (p, (updateReport(p), updateClassifiersReport(p)))
        }.toMap
      }
    }
  )

  override lazy val projectSettings = Seq(
    EnsimeKeys.unmanagedSourceArchives := Nil,
    EnsimeKeys.unmanagedJavadocArchives := Nil,
    EnsimeKeys.includeSourceJars := true,
    EnsimeKeys.includeDocJars := true
  )

  def defaultCompilerFlags(scalaVersion: String): Seq[String] = Seq(
    "-feature",
    "-deprecation",
    "-Xlint",
    "-Yinline-warnings",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    //"-Ywarn-value-discard", // more annoying than useful
    "-Xfuture"
  ) ++ {
      CrossVersion.partialVersion(scalaVersion) match {
        case Some((2, v)) if v >= 11 => Seq("-Ywarn-unused-import")
        case _                       => Nil
      }
    }

  // it would be good if debugging-off was automatically triggered
  // https://stackoverflow.com/questions/32350617
  def toggleDebugging(enable: Boolean): State => State = { implicit state: State =>
    val extracted = Project.extract(state)

    implicit val pr = extracted.currentRef
    implicit val bs = extracted.structure

    if (enable) {
      val port = EnsimeKeys.debuggingPort.gimme
      log.warn(s"Enabling debugging for all forked processes on port $port")
      log.info("Only one process can use the port and it will await a connection before proceeding.")
    }

    val newSettings = extracted.structure.allProjectRefs map { proj =>
      val orig = (javaOptions in proj).run
      val debugFlags = ((EnsimeKeys.debuggingFlag in proj).gimme + (EnsimeKeys.debuggingPort in proj).gimme)
      val withoutDebug = orig.diff(List(debugFlags))
      val withDebug = withoutDebug :+ debugFlags
      val rewritten = if (enable) withDebug else withoutDebug

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

    val updateReports = EnsimeKeys.megaUpdate.run

    implicit val rawModules = projects.collect {
      case (ref, proj) =>
        val (updateReport, updateClassifiersReport) = updateReports(ref)
        val module = projectData(proj, updateReport, updateClassifiersReport)(ref, bs, state)
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
      EnsimeKeys.compilerArgs.run.toList ++
        EnsimeKeys.additionalCompilerArgs.run
    }.distinct
    val scalaV = scalaVersion.gimme
    val javaH = javaHome.gimme.getOrElse(JdkDir)
    val javaSrc = {
      file(javaH.getAbsolutePath + "/src.zip") match {
        case f if f.exists => List(f)
        case _ =>
          log.warn(s"No Java sources detected in $javaH (your ENSIME experience will not be as good as it could be.)")
          Nil
      }
    } ++ EnsimeKeys.unmanagedSourceArchives.gimme

    val formatting = EnsimeKeys.scalariform.gimmeOpt

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

  def projectData(
    project: ResolvedProject,
    updateReport: UpdateReport,
    updateClassifiersReport: UpdateReport
  )(
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

    val myDoc = (artifactPath in (Compile, packageDoc)).gimmeOpt

    val filter = if (sbtPlugin.gimme) "provided" else ""

    def jarsFor(config: Configuration) = updateReport.select(
      configuration = configurationFilter(filter | config.name.toLowerCase),
      artifact = artifactFilter(extension = Artifact.DefaultExtension)
    ).toSet

    def unmanagedJarsFor(config: Configuration) =
      (unmanagedJars in config).runOpt.map(_.map(_.data).toSet).getOrElse(Set())

    def jarSrcsFor(config: Configuration) = if (EnsimeKeys.includeSourceJars.gimme) {
      updateClassifiersReport.select(
        configuration = configurationFilter(filter | config.name.toLowerCase),
        artifact = artifactFilter(classifier = Artifact.SourceClassifier)
      ).toSet ++ (EnsimeKeys.unmanagedSourceArchives in projectRef).gimme
    } else {
      (EnsimeKeys.unmanagedSourceArchives in projectRef).gimme
    }

    def jarDocsFor(config: Configuration) = if (EnsimeKeys.includeDocJars.gimme) {
      updateClassifiersReport.select(
        configuration = configurationFilter(filter | config.name.toLowerCase),
        artifact = artifactFilter(classifier = Artifact.DocClassifier)
      ).toSet ++ (EnsimeKeys.unmanagedJavadocArchives in projectRef).gimme
    } else {
      (EnsimeKeys.unmanagedJavadocArchives in projectRef).gimme
    }
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

  def genEnsimeMeta: State => State = { implicit state: State =>
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
      file(Properties.userDir).getName + "-meta"
    }

    val compilerArgs = {
      EnsimeKeys.compilerMetaArgs.run.toList ++
        EnsimeKeys.additionalMetaCompilerArgs.run
    }.distinct
    val scalaV = Properties.versionNumberString
    val javaSrc = JdkDir / "src.zip" match {
      case f if f.exists => List(f)
      case _             => Nil
    }

    val formatting = EnsimeKeys.scalariform.gimmeOpt

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

case class EnsimeConfig(
  root: File,
  cacheDir: File,
  name: String,
  scalaVersion: String,
  compilerArgs: List[String],
  modules: Map[String, EnsimeModule],
  javaHome: File,
  javaFlags: List[String],
  javaSrc: List[File],
  formatting: Option[IFormattingPreferences]
)

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

trait CommandSupport {
  this: AutoPlugin =>

  import sbt._
  import IO._

  protected def fail(errorMessage: String)(implicit state: State): Nothing = {
    state.log.error(errorMessage)
    throw new IllegalArgumentException()
  }

  protected def log(implicit state: State) = state.log

  // our version of http://stackoverflow.com/questions/25246920
  protected implicit class RichSettingKey[A](key: SettingKey[A]) {
    def gimme(implicit pr: ProjectRef, bs: BuildStructure, s: State): A =
      gimmeOpt getOrElse { fail(s"Missing setting: ${key.key.label}") }
    def gimmeOpt(implicit pr: ProjectRef, bs: BuildStructure, s: State): Option[A] =
      key in pr get bs.data
  }

  protected implicit class RichTaskKey[A](key: TaskKey[A]) {
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
    else ss.toSeq.sortBy(_.getPath).map(toSExp).mkString("(", " ", ")")

  def ssToSExp(ss: Iterable[String]): String =
    if (ss.isEmpty) "nil"
    else ss.toSeq.sorted.map(toSExp).mkString("(", " ", ")")

  def msToSExp(ss: Iterable[EnsimeModule]): String =
    if (ss.isEmpty) "nil"
    else ss.toSeq.sortBy(_.name).map(toSExp).mkString("(", " ", ")")

  def fToSExp(key: String, op: Option[File]): String =
    op.map { f => s":$key ${toSExp(f)}" }.getOrElse("")

  def sToSExp(key: String, op: Option[String]): String =
    op.map { f => s":$key ${toSExp(f)}" }.getOrElse("")

  def toSExp(b: Boolean): String = if (b) "t" else "nil"

  def toSExp(o: Option[IFormattingPreferences]): String = o match {
    case None                                => "nil"
    case Some(f) if f.preferencesMap.isEmpty => "nil"
    case Some(f) => f.preferencesMap.map {
      case (desc, v: Boolean) =>
        s":${desc.key} ${toSExp(v)}"
      case (desc, v: Int) =>
        s":${desc.key} $v"
    }.mkString("(", " ", ")")
  }

  // a lot of legacy key names and conventions
  def toSExp(c: EnsimeConfig): String = s"""(
 :root-dir ${toSExp(c.root)}
 :cache-dir ${toSExp(c.cacheDir)}
 :name "${c.name}"
 :java-home ${toSExp(c.javaHome)}
 :java-flags ${ssToSExp(c.javaFlags)}
 :reference-source-roots ${fsToSExp(c.javaSrc)}
 :scala-version ${toSExp(c.scalaVersion)}
 :compiler-args ${ssToSExp(c.compilerArgs)}
 :formatting-prefs ${toSExp(c.formatting)}
 :subprojects ${msToSExp(c.modules.values)}
)"""

  // a lot of legacy key names and conventions
  def toSExp(m: EnsimeModule): String = s"""(
   :name ${toSExp(m.name)}
   :source-roots ${fsToSExp((m.mainRoots ++ m.testRoots))}
   :targets ${fsToSExp(m.targets)}
   :test-targets ${fsToSExp(m.testTargets)}
   :depends-on-modules ${ssToSExp(m.dependsOnNames)}
   :compile-deps ${fsToSExp(m.compileJars)}
   :runtime-deps ${fsToSExp(m.runtimeJars)}
   :test-deps ${fsToSExp(m.testJars)}
   :doc-jars ${fsToSExp(m.docJars)}
   :reference-source-roots ${fsToSExp(m.sourceJars)})"""
}
