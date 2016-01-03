import sbt._
import Keys._

object SimpleBuild extends Build {
  override val settings = super.settings ++ Seq(
    ivyLoggingLevel := UpdateLogging.Quiet,
    scalaVersion := "2.11.7",
    scalacOptions in Compile := Seq("-Xlog-reflective-calls")
  )
}
