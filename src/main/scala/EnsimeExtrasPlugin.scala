// Copyright (C) 2014 - 2016 ENSIME Contributors
// Licence: Apache-2.0
package org.ensime

import sbt._
import Keys._
import complete.{DefaultParsers, Parser}

object EnsimeExtrasKeys {

  case class JavaArgs(mainClass: String, envArgs: Map[String, String], jvmArgs: Seq[String], classArgs: Seq[String])
  case class LaunchConfig(name: String, javaArgs: JavaArgs)

  val ensimeCompileOnly = InputKey[Unit](
    "ensimeCompileOnly",
    "Compiles a single scala file"
  )

  val ensimeRunMain = InputKey[Unit](
    "ensimeRunMain",
    "Run user specified env/args/class/params (e.g. `ensimeRunMain FOO=BAR -Xmx2g foo.Bar baz')"
  )

  val ensimeRunDebug = InputKey[Unit](
    "ensimeRunDebug",
    "Run user specified env/args/class/params with debugging flags added"
  )

  val ensimeLaunchConfigurations = SettingKey[Seq[LaunchConfig]](
    "ensimeLaunchConfigurations",
    "Named applications with canned env/args/class/params"
  )

  val ensimeLaunch = InputKey[Unit](
    "ensimeLaunch",
    "Launch a named application in ensimeLaunchConfigurations"
  )

}

object EnsimeExtrasPlugin extends AutoPlugin {
  import EnsimeExtrasKeys._

  override def requires = EnsimePlugin
  override def trigger = allRequirements
  val autoImport = EnsimeExtrasKeys

  override lazy val projectSettings = Seq(
    ensimeRunMain <<= parseAndRunMainWithSettings(),
    ensimeRunDebug <<= parseAndRunMainWithSettings(
      // it would be good if this could reference Settings...
      extraArgs = Seq(s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
    ),
    ensimeLaunchConfigurations := Nil,
    ensimeLaunch <<= launchTask,
    aggregate in ensimeCompileOnly := false
  ) ++ Seq(Compile, Test).flatMap { config =>
      // WORKAROUND https://github.com/sbt/sbt/issues/2580
      inConfig(config) {
        Seq(
          ensimeCompileOnly <<= compileOnlyTask,
          scalacOptions in ensimeCompileOnly := scalacOptions.value
        )
      }
    }

  val ensimeRunMainTaskParser: Parser[JavaArgs] = {
    import DefaultParsers._

    val envArg = ScalaID ~ "=" ~ NotSpace <~ Space map {
      case key ~ _ ~ value => (key, value)
    }
    val jvmArg = "-" ~ NotSpace <~ Space map {
      case a ~ b => a + b
    }
    val mainClassPart = identifier(ScalaIDChar, charClass(isIDChar))
    val mainClass = mainClassPart ~ ("." ~ mainClassPart).* map {
      case first ~ seq => first + seq.map(t => t._1 + t._2).mkString
    }
    (Space ~> envArg.*.map(_.toMap) ~ jvmArg.* ~ mainClass ~ spaceDelimited("<arg>")) map {
      case envArgs ~ jvmArgs ~ mainClass ~ classArgs => JavaArgs(mainClass, envArgs, jvmArgs, classArgs)
    }
  }

  def parseAndRunMainWithSettings(
    extraEnv: Map[String, String] = Map.empty,
    extraArgs: Seq[String] = Seq.empty
  ): Def.Initialize[InputTask[Unit]] = {
    val parser = (s: State) => ensimeRunMainTaskParser
    Def.inputTask {
      val givenArgs = parser.parsed
      val newJvmArgs = (javaOptions.value ++ extraArgs ++ givenArgs.jvmArgs).distinct
      val newEnvArgs = envVars.value ++ extraEnv ++ givenArgs.envArgs
      toError(new ForkRun(ForkOptions(runJVMOptions = newJvmArgs, envVars = newEnvArgs)).run(
        givenArgs.mainClass,
        Attributed.data((fullClasspath in Compile).value),
        givenArgs.classArgs,
        streams.value.log
      ))
    }
  }

  def launchTask: Def.Initialize[InputTask[Unit]] = {
    Def.inputTask {
      val configs = Def.spaceDelimited().parsed
      configs.foreach(name => {
        val configByName = ensimeLaunchConfigurations.value.find(_.name == name)
        configByName.fold(
          sLog.value.warn(s"No launch configuration '$name'")
        )(config => {
            sLog.value.info(s"Running $name")
            val args = config.javaArgs
            toError(new ForkRun(ForkOptions(runJVMOptions = args.jvmArgs, envVars = args.envArgs)).run(
              args.mainClass,
              Attributed.data((fullClasspath in Compile).value),
              args.classArgs,
              streams.value.log
            ))
          })
      })
    }
  }

  private val noChanges = new xsbti.compile.DependencyChanges {
    def isEmpty = true
    def modifiedBinaries = Array()
    def modifiedClasses = Array()
  }
  private object noopCallback extends xsbti.AnalysisCallback {
    val includeSynthToNameHashing: Boolean = true
    override val nameHashing: Boolean = true
    def beginSource(source: File): Unit = {}
    def generatedClass(source: File, module: File, name: String): Unit = {}
    def api(sourceFile: File, source: xsbti.api.SourceAPI): Unit = {}
    def sourceDependency(dependsOn: File, source: File, publicInherited: Boolean): Unit = {}
    def binaryDependency(binary: File, name: String, source: File, publicInherited: Boolean): Unit = {}
    def endSource(sourcePath: File): Unit = {}
    def problem(what: String, pos: xsbti.Position, msg: String, severity: xsbti.Severity, reported: Boolean): Unit = {}
    def usedName(sourceFile: File, names: String): Unit = {}
    override def binaryDependency(file: File, s: String, file1: File, dependencyContext: xsbti.DependencyContext): Unit = {}
    override def sourceDependency(file: File, file1: File, dependencyContext: xsbti.DependencyContext): Unit = {}
  }

  // DEPRECATED with no alternative https://github.com/sbt/sbt/issues/2417
  def compileOnlyTask: Def.Initialize[InputTask[Unit]] = InputTask(
    (s: State) => Def.spaceDelimited()
  ) { (argTask: TaskKey[Seq[String]]) =>
      (
        argTask,
        sourceDirectories,
        dependencyClasspath,
        classDirectory,
        scalacOptions in ensimeCompileOnly,
        maxErrors,
        compileInputs in compile,
        compilers,
        streams
      ).map { (args, dirs, cp, out, opts, merrs, in, cs, s) =>
          if (args.isEmpty) throw new IllegalArgumentException("needs a file")
          args.foreach { arg =>
            val input: File = file(arg).getCanonicalFile
            val sourceDirs = dirs.map(_.getCanonicalFile)

            val here = sourceDirs.exists { dir => input.getPath.startsWith(dir.getPath) }
            if (!here || !input.exists())
              throw new IllegalArgumentException(s"$arg not associated to $sourceDirs")

            // there is no reason why we couldn't compileOnly other
            // languages, but they would require explicit support.
            // Java shouldn't be too hard if somebody wants it.
            if (!input.getName.endsWith(".scala"))
              throw new IllegalArgumentException(s"only .scala files are supported: $arg")

            if (!out.exists()) IO.createDirectory(out)
            s.log.info(s"""Compiling $input with ${opts.mkString(" ")}""")

            cs.scalac(
              Seq(input), noChanges, cp.map(_.data) :+ out, out, opts,
              noopCallback, merrs, in.incSetup.cache, s.log
            )
          }
        }
    }
}
