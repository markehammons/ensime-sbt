// Copyright (C) 2014 - 2016 ENSIME Contributors
// Licence: Apache-2.0
package org.ensime

import sbt._
import Keys._
import sbt.complete.{DefaultParsers, Parser}

object EnsimeExtraPluginKeys {

  case class JavaArgs(mainClass: String, envArgs: Map[String, String], jvmArgs: Seq[String], classArgs: Seq[String])
  case class AdditionalArgs(envArgs: Map[String, String], jvmArgs: Seq[String])
  case class LaunchConfig(name: String, javaArgs: JavaArgs)

  val ensimeRunMain = InputKey[Unit](
    "ensimeRunMain",
    "Run user specified class with given jvm args and environment args that overrides system ones"
  )

  val ensimeRunDebug = InputKey[Unit](
    "ensimeRunDebug",
    "Run user specified class with given jvm args, environment args and debug flags"
  )

  val launchConfigurations = SettingKey[Seq[LaunchConfig]](
    "launchConfigurations",
    "Configurations with special arguments and main classes for launch command"
  )

  val launch = InputKey[Unit](
    "launch",
    "Launch specific configurations described in launchConfigurations"
  )
}

object EnsimeExtraPlugin extends AutoPlugin {
  import EnsimeExtraPluginKeys._

  override lazy val projectSettings = Seq(
    ensimeRunMain <<= ensimeRunMainTask,
    ensimeRunDebug <<= ensimeRunDebugTask,
    launch <<= launchTask
  )

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

  private def parseAndRunMainWithSettings(additionalArgs: AdditionalArgs): Def.Initialize[InputTask[Unit]] = {
    val parser = (s: State) => ensimeRunMainTaskParser
    Def.inputTask {
      val givenArgs = parser.parsed
      val newJvmArgs = (javaOptions.value ++ additionalArgs.jvmArgs ++ givenArgs.jvmArgs).distinct
      val newEnvArgs = envVars.value ++ additionalArgs.envArgs ++ givenArgs.envArgs
      toError(new ForkRun(ForkOptions(runJVMOptions = newJvmArgs, envVars = newEnvArgs)).run(
        givenArgs.mainClass,
        Attributed.data((fullClasspath in Compile).value),
        givenArgs.classArgs,
        streams.value.log
      ))
    }
  }

  def ensimeRunMainTask: Def.Initialize[InputTask[Unit]] = {
    parseAndRunMainWithSettings(AdditionalArgs(Map.empty, Seq.empty))
  }

  def ensimeRunDebugTask: Def.Initialize[InputTask[Unit]] = {
    parseAndRunMainWithSettings(AdditionalArgs(Map.empty, Seq("-Xdebug")))
  }

  def launchTask: Def.Initialize[InputTask[Unit]] = {
    Def.inputTask {
      val configs = Def.spaceDelimited().parsed
      configs.foreach(name => {
        val configByName = launchConfigurations.value.find(_.name == name)
        configByName.fold(
          sLog.value.info(s"There isn't such configuration: $name")
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
}
