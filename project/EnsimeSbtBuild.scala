// Copyright (C) 2015 ENSIME Authors
// License: Apache-2.0

import SonatypeSupport._
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import sbt._
import sbt.Keys._
import sbt.ScriptedPlugin._
import scalariform.formatter.preferences._

object EnsimeSbtBuild extends Build {

  override val settings = super.settings ++ Seq(
    name := "ensime-sbt",
    organization := "org.ensime",
    version := "0.3.0-SNAPSHOT",
    scalaVersion := "2.10.6",
    ivyLoggingLevel := UpdateLogging.Quiet,
    scalacOptions in Compile ++= Seq(
      "-encoding", "UTF-8", "-target:jvm-1.6", "-feature", "-deprecation",
      "-Xfatal-warnings",
      "-language:postfixOps", "-language:implicitConversions"
    ),
    ScalariformKeys.preferences := FormattingPreferences().setPreference(AlignSingleLineCaseStatements, true)
  ) ++ sonatype("ensime", "ensime-sbt", BSD3)

  lazy val root = (project in file(".")).
    enablePlugins(SbtScalariform).
    settings(scriptedSettings).
    settings(
      sbtPlugin := true,
      // intentionally old version of scalariform: do not force an upgrade upon users
      libraryDependencies += "org.scalariform" %% "scalariform" % "0.1.4",
      scriptedLaunchOpts := Seq(
        "-Dplugin.version=" + version.value,
        "-Dsbt.task.timings=true"
      ),
      scriptedBufferLog := false
    )

}
