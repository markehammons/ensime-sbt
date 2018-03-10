libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.25"
excludeDependencies += SbtExclusionRule("org.slf4j", "slf4j-simple")
ivyLoggingLevel := UpdateLogging.Quiet

scalacOptions ++= Seq("-unchecked", "-deprecation")

addSbtPlugin("com.fommil" % "sbt-sensible" % "2.1.0")
addSbtPlugin("io.get-coursier" % "sbt-shading" % "1.0.0-RC13")

libraryDependencies += "org.scala-sbt" % "scripted-plugin" % sbtVersion.value
