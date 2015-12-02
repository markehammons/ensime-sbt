import scalariform.formatter.preferences._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

name := "ensime-sbt"

organization := "org.ensime"

version := "0.2.3-SNAPSHOT"

scalaVersion := "2.10.6"

sbtPlugin := true

ivyLoggingLevel := UpdateLogging.Quiet

fork := true

scalacOptions in Compile ++= Seq(
  "-encoding", "UTF-8", "-target:jvm-1.6", "-feature", "-deprecation",
  "-Xfatal-warnings",
  "-language:postfixOps", "-language:implicitConversions"
  //"-P:wartremover:only-warn-traverser:org.brianmckenna.wartremover.warts.Unsafe"
  //"-P:wartremover:traverser:org.brianmckenna.wartremover.warts.Unsafe"
)

// intentionally old version of scalariform: do not force an upgrade upon users
libraryDependencies += "org.scalariform" %% "scalariform" % "0.1.4"

scalariformSettings

ScalariformKeys.preferences := FormattingPreferences().setPreference(AlignSingleLineCaseStatements, true)

publishMavenStyle := true

publishArtifact in Test := false


lazy val prepareTesting = taskKey[Unit]("Installs this version of ensime-sbt globally.")
prepareTesting := {
  val v = version.value
  IO.write(file("ensime-sbt-install"), "\n" + s"""addSbtPlugin("org.ensime" % "ensime-sbt" % "$v")""" + "\n\n")
}

pomIncludeRepository := { _ => false }

homepage := Some(url("http://github.com/ensime/ensime-server"))

licenses := Seq("BSD 3 Clause" -> url("http://opensource.org/licenses/BSD-3-Clause"))

publishTo <<= version { v: String =>
  val nexus = "https://oss.sonatype.org/"
  if (v.contains("SNAP")) Some("snapshots" at nexus + "content/repositories/snapshots")
  else                    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

credentials ++= {
  for {
    username <- sys.env.get("SONATYPE_USERNAME")
    password <- sys.env.get("SONATYPE_PASSWORD")
  } yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)
}.toSeq

pomExtra := (
  <scm>
    <url>git@github.com:ensime/ensime-sbt.git</url>
    <connection>scm:git:git@github.com:ensime/ensime-sbt.git</connection>
  </scm>
  <developers>
    <developer>
      <id>fommil</id>
      <name>Sam Halliday</name>
    </developer>
  </developers>
)
