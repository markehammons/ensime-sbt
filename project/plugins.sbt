addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.5.1")

// really to test the gen-ensime-project code
scalacOptions ++= Seq("-unchecked", "-deprecation")
