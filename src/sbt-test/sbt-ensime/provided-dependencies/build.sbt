ivyLoggingLevel := UpdateLogging.Quiet

// unless "in ThisBuild" is appended, the correct base scalac flags are not detected
scalaVersion := "2.11.8"
scalacOptions in Compile := Seq("-Xlog-reflective-calls")

libraryDependencies += "commons-io" % "commons-io" % "2.5"
libraryDependencies += "com.google.guava" % "guava" % "19.0" % "provided"
