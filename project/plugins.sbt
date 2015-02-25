// org.scalariform is old, use com.danieltrinh's version
// this only affects the formatting of sbt itself
addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.3.0" excludeAll ExclusionRule("org.scalariform"))

libraryDependencies += "com.danieltrinh" %% "scalariform" % "0.1.5"
