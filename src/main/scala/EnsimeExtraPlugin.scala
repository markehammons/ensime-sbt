// Copyright (C) 2014 - 2016 ENSIME Contributors
// Licence: Apache-2.0
package org.ensime

import sbt._
import Keys._
import sbt.complete.{DefaultParsers, Parser}

object EnsimeExtraPluginKeys {

  case class JavaArgs(mainClass: String, envArgs: Map[String, String], jvmArgs: Seq[String], classArgs: Seq[String])
  case class LaunchConfig(name: String, javaArgs: JavaArgs)

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

object EnsimeExtraPlugin extends AutoPlugin {
  import EnsimeExtraPluginKeys._

  override def requires = EnsimePlugin
  override def trigger = allRequirements
  val autoImport = EnsimeExtraPluginKeys

  override lazy val projectSettings = Seq(
    ensimeRunMain <<= parseAndRunMainWithSettings(),
    ensimeRunDebug <<= parseAndRunMainWithSettings(
      // it would be good if this could reference Settings...
      extraArgs = Seq(s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
    ),
    ensimeLaunch <<= launchTask
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
}
