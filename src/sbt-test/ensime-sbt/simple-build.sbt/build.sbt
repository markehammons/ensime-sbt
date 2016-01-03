ivyLoggingLevel := UpdateLogging.Quiet

scalaVersion in ThisBuild := "2.11.7"
scalacOptions in Compile in ThisBuild := Seq("-Xlog-reflective-calls")
