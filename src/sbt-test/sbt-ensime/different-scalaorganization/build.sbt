ivyLoggingLevel := UpdateLogging.Quiet

scalaVersion := "2.11.7"
scalaOrganization := "org.typelevel"
scalacOptions in Compile := Seq("-Xlog-reflective-calls")

// piggybacking this little feature onto this test
ensimeSourceMode := true
