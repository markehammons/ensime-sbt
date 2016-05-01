addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.6.0")

libraryDependencies += "org.scala-sbt" % "scripted-plugin" % sbtVersion.value

// really to test the ensimeConfigProject code
scalacOptions ++= Seq("-unchecked", "-deprecation")

ivyLoggingLevel := UpdateLogging.Quiet
