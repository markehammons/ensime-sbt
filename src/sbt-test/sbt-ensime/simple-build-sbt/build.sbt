ivyLoggingLevel := UpdateLogging.Quiet

scalaVersion in ThisBuild := "2.11.8"
scalacOptions in Compile := Seq("-Xlog-reflective-calls")

ensimeIgnoreMissingDirectories := true
