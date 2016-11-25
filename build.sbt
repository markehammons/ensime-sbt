organization := "org.ensime"
name := "sbt-ensime"

sbtPlugin := true

sonatypeGithub := ("ensime", "ensime-sbt")
licenses := Seq(Apache2)

// BLOCKED https://github.com/ensime/ensime-sbt/issues/270
scalacOptions -= "-Xfatal-warnings"

// intentionally old version of scalariform: do not force an upgrade upon users
libraryDependencies += "org.scalariform" %% "scalariform" % "0.1.4"

libraryDependencies ++= Seq(
  "io.get-coursier" %% "coursier-java-6" % "1.0.0-M12-1",
  "io.get-coursier" %% "coursier-cache-java-6" % "1.0.0-M12-1"
)

scriptedSettings
scriptedBufferLog := false
scriptedLaunchOpts := Seq(
  "-Dplugin.src=" + sys.props("user.dir"),
  "-Dplugin.version=" + version.value,
  // .jvmopts is ignored, simulate here
  "-XX:MaxPermSize=256m", "-Xmx2g", "-Xss2m"
)
