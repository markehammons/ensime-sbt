scalacOptions ++= Seq("-unchecked", "-deprecation")
ivyLoggingLevel := UpdateLogging.Quiet
addSbtPlugin("com.fommil" % "sbt-sensible" % "1.1.5")

libraryDependencies += "org.scala-sbt" % "scripted-plugin" % sbtVersion.value

