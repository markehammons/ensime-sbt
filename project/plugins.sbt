addSbtPlugin("org.ensime" % "ensime-sbt" % "0.2.3")

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.5.1")

libraryDependencies += "org.scala-sbt" % "scripted-plugin" % sbtVersion.value

// really to test the gen-ensime-project code
scalacOptions ++= Seq("-unchecked", "-deprecation")

ivyLoggingLevel := UpdateLogging.Quiet
