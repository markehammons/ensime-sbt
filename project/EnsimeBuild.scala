import SonatypeSupport._
import com.typesafe.sbt.SbtScalariform._
import java.io._
import java.lang.Runtime
import org.ensime.Imports.EnsimeKeys
import sbt.{ IntegrationTest => It, _ }
import sbt.Keys._
import sbtassembly.{ AssemblyKeys, MergeStrategy, PathList }
import sbtassembly.AssemblyKeys._
import scala.util.{ Properties, Try }
import org.ensime.EnsimePlugin.JdkDir

object EnsimeBuild extends Build {
  ////////////////////////////////////////////////
  // common
  override lazy val settings = super.settings ++ Seq(
    organization := "org.ensime",
    scalaVersion := "2.11.7",
    version := "0.9.10-SNAPSHOT",

    // sbt, STFU...
    ivyLoggingLevel := UpdateLogging.Quiet,

    dependencyOverrides ++= Set(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.scala-lang" % "scala-library" % scalaVersion.value,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "org.scala-lang" % "scalap" % scalaVersion.value,
      "org.scala-lang.modules" %% "scala-xml" % "1.0.4",
      "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4",
      "org.scalamacros" %% "quasiquotes" % "2.0.1",
      "org.slf4j" % "slf4j-api" % "1.7.13",
      "org.apache.lucene" % "lucene-core" % "4.7.2",
      shapeless
    ),

    libraryDependencies ++= {
      if (scalaVersion.value.startsWith("2.10."))
        Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full))
      else Nil
    },

    scalacOptions in Compile ++= Seq(
      "-encoding", "UTF-8",
      "-target:jvm-1.6",
      "-feature",
      "-deprecation",
      "-language:postfixOps",
      "-language:implicitConversions",
      "-Xlint",
      "-Yinline-warnings",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      //"-Ywarn-numeric-widen", // bad implicit widening somewhere
      //"-Ywarn-value-discard", // will require a lot of work
      "-Xfuture"
    ) ++ {
        if (scalaVersion.value.startsWith("2.11")) Seq("-Ywarn-unused-import")
        else Nil
      } ++ {
        // fatal warnings can get in the way during the DEV cycle
        if (sys.env.contains("CI")) Seq("-Xfatal-warnings")
        else Nil
      },
    javacOptions in (Compile, compile) ++= Seq(
      "-source", "1.6", "-target", "1.6", "-Xlint:all", "-Werror",
      "-Xlint:-options", "-Xlint:-path", "-Xlint:-processing"
    ),
    javacOptions in doc ++= Seq("-source", "1.6"),

    maxErrors := 1,
    fork := true,

    // 4 x 1GB = 4GB
    concurrentRestrictions in Global := Seq(Tags.limitAll(4))
  )

  // e.g. YOURKIT_AGENT=/opt/yourkit/bin/linux-x86-64/libyjpagent.so
  val yourkitAgent = Properties.envOrNone("YOURKIT_AGENT").map { name =>
    val agent = file(name)
    require(agent.exists(), s"Yourkit agent specified ($agent) does not exist")
    Seq(s"-agentpath:${agent.getCanonicalPath}")
  }.getOrElse(Nil)

  lazy val commonSettings = scalariformSettings ++ Seq(
    //resolvers += Resolver.sonatypeRepo("snapshots"),
    // RELATED https://github.com/sbt/sbt/issues/2253
    // WORKAROUND https://github.com/ensime/ensime-emacs/issues/327
    fullResolvers += Resolver.jcenterRepo,
    //resolvers += "NetBeans" at "http://bits.netbeans.org/nexus/content/groups/netbeans",
    testForkedParallel in Test := true,
    javaOptions := Seq("-Xss2m", "-XX:MaxPermSize=256m", "-Xms1g", "-Xmx1g"),
    // disabling shared memory gives a small performance boost to tests
    javaOptions ++= Seq("-XX:+PerfDisableSharedMem"),
    javaOptions ++= Seq("-XX:+UseConcMarkSweepGC", "-XX:+CMSIncrementalMode"),
    javaOptions in run ++= yourkitAgent,
    javaOptions in Test += "-Dlogback.configurationFile=../logback-test.xml",
    testOptions in Test ++= noColorIfEmacs,
    testFrameworks in Test := Seq(TestFrameworks.ScalaTest, TestFrameworks.JUnit),
    EnsimeKeys.scalariform := ScalariformKeys.preferences.value
  // updateCaching is still missing things --- e.g. shapeless in core/it:test
  //updateOptions := updateOptions.value.withCachedResolution(true)
  ) ++ sonatype("ensime", "ensime-server", GPL3)

  lazy val commonItSettings = scalariformSettingsWithIt ++ Seq(
    // AppVeyor struggles with parallel integration tests
    parallelExecution in It := !sys.env.contains("APPVEYOR"),

    // Test forking is about running each group in a separate JVM.
    // We want each IntegrationTest to be its own group, hence one JVM per Test.
    // boilerplate-tastic.
    fork in It := true,
    testForkedParallel in It := true,
    testGrouping in It <<= (
      definedTests in It,
      baseDirectory in It,
      javaOptions in It,
      outputStrategy in It,
      envVars in It,
      javaHome in It,
      connectInput in It
    ).map { (tests, base, options, strategy, env, javaHomeDir, connectIn) =>
        val opts = ForkOptions(
          bootJars = Nil,
          javaHome = javaHomeDir,
          connectInput = connectIn,
          outputStrategy = strategy,
          runJVMOptions = options,
          workingDirectory = Some(base),
          envVars = env
        )
        tests.map { test =>
          Tests.Group(test.name, Seq(test), Tests.SubProcess(opts))
        }
      },

    javaOptions in It += "-Dfile.encoding=UTF8", // for file cloning
    testOptions in It ++= noColorIfEmacs,
    testFrameworks in It := Seq(TestFrameworks.ScalaTest, TestFrameworks.JUnit),

    javaOptions in It ++= Seq(
      "-Dlogback.configurationFile=../logback-it.xml"
    )
  )

  ////////////////////////////////////////////////
  // common dependencies
  lazy val JavaTools: File = JdkDir / "lib/tools.jar"
  lazy val shapeless = "com.chuusai" %% "shapeless" % "2.2.5"
  lazy val logback = Seq(
    "ch.qos.logback" % "logback-classic" % "1.1.3",
    "org.slf4j" % "jul-to-slf4j" % "1.7.13",
    "org.slf4j" % "jcl-over-slf4j" % "1.7.13"
  )
  val akkaVersion = "2.3.14"
  val streamsVersion = "1.0"
  val scalatestVersion = "2.2.6"

  ////////////////////////////////////////////////
  // utils
  def testLibs(scalaV: String, config: String = "test") = Seq(
    "org.scalatest" %% "scalatest" % scalatestVersion % config,
    "org.scalamock" %% "scalamock-scalatest-support" % "3.2.2" % config,
    "org.scalacheck" %% "scalacheck" % "1.12.5" % config,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % config,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion % config
  ) ++ logback.map(_ % config)

  // WORKAROUND: https://github.com/scalatest/scalatest/issues/511
  def noColorIfEmacs =
    if (sys.env.get("INSIDE_EMACS").isDefined)
      Seq(Tests.Argument(TestFrameworks.ScalaTest, "-oWF"))
    else
      Seq(Tests.Argument(TestFrameworks.ScalaTest, "-oF"))
  ////////////////////////////////////////////////

  ////////////////////////////////////////////////
  // modules
  lazy val monkeys = Project("monkeys", file("monkeys")) settings(commonSettings) settings (
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.apache.commons" % "commons-vfs2" % "2.0" intransitive() exclude("commons-logging", "commons-logging")
    ) ++ logback
  )

  lazy val util = Project("util", file("util")) settings(commonSettings) settings (
    libraryDependencies ++= List(
      "org.apache.commons" % "commons-vfs2" % "2.0" intransitive() exclude("commons-logging", "commons-logging"),
      "com.google.guava" % "guava" % "18.0",
      "com.google.code.findbugs" % "jsr305" % "3.0.1" % "provided"
    ) ++ testLibs(scalaVersion.value) ++ logback
  )

  lazy val testutil = Project("testutil", file("testutil")) settings(commonSettings) dependsOn(
    util, api
  ) settings (
    libraryDependencies += "commons-io" % "commons-io" % "2.4",
    libraryDependencies ++= testLibs(scalaVersion.value, "compile")
  )

  lazy val s_express = Project("s-express", file("s-express")) settings(commonSettings) dependsOn (
    util
  ) settings (
      libraryDependencies ++= Seq(
        "org.parboiled" %% "parboiled" % "2.1.0" intransitive() exclude("com.chuusai", "shapeless_2.10.4"),
        shapeless
      ) ++ testLibs(scalaVersion.value)
    )

  lazy val api = Project("api", file("api")) settings(commonSettings) settings (
    libraryDependencies ++= Seq(
      "org.scalariform" %% "scalariform" % "0.1.8"
    ) ++ testLibs(scalaVersion.value),
      licenses := Seq(Apache2)
  )

  // the JSON protocol
  lazy val jerky = Project("jerky", file("protocol-jerky")) settings(commonSettings) dependsOn (
    util,
    api,
    testutil % "test",
    api % "test->test" // for the test data
  ) settings (
      libraryDependencies ++= Seq(
        "com.github.fommil" %% "spray-json-shapeless" % "1.1.0",
        "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
      ) ++ testLibs(scalaVersion.value)
    )

  // the S-Exp protocol
  lazy val swanky = Project("swanky", file("protocol-swanky")) settings(commonSettings) dependsOn (
    api,
    testutil % "test",
    api % "test->test", // for the test data
    s_express
  ) settings (
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
      ) ++ testLibs(scalaVersion.value)
    )

  lazy val testingEmpty = Project("testingEmpty", file("testing/empty"))

  lazy val testingSimple = Project("testingSimple", file("testing/simple")) settings (
    scalacOptions in Compile := Seq(),
    libraryDependencies += "org.scalatest" %% "scalatest" % scalatestVersion % "test" intransitive ()
  )

  lazy val testingSimpleJar = Project("testingSimpleJar", file("testing/simpleJar")).settings(
    exportJars := true,
    EnsimeKeys.useJar := true
  )

  lazy val testingImplicits = Project("testingImplicits", file("testing/implicits")) settings (
    libraryDependencies += "org.scalatest" %% "scalatest" % scalatestVersion % "test" intransitive ()
  )

  lazy val testingTiming = Project("testingTiming", file("testing/timing"))

  lazy val testingDebug = Project("testingDebug", file("testing/debug")).settings(
    scalacOptions in Compile := Seq()
  )

  lazy val testingDocs = Project("testingDocs", file("testing/docs")).settings(
    libraryDependencies ++= Seq(
      // specifically using ForecastIOLib version 1.5.1 for javadoc 1.8 output
      "com.github.dvdme" % "ForecastIOLib" % "1.5.1" intransitive (),
      "com.google.guava" % "guava" % "18.0" intransitive (),
      "commons-io" % "commons-io" % "2.4" intransitive ()
    )
  )

  // java project with no scala-library
  lazy val testingJava = Project("testingJava", file("testing/java")).settings(
    crossPaths := false,
    autoScalaLibrary := false
  )

  lazy val core = Project("core", file("core")).dependsOn(
    api, s_express, monkeys,
    api % "test->test", // for the interpolator
    testutil % "test,it",
    // depend on "it" dependencies in "test" or sbt adds them to the release deps!
    // https://github.com/sbt/sbt/issues/1888
    testingEmpty % "test,it",
    testingSimple % "test,it",
    // test config needed to get the test jar
    testingSimpleJar % "test,it->test",
    testingTiming % "test,it",
    testingDebug % "test,it",
    testingJava % "test,it"
  ).configs(It).settings(
      commonSettings
    ).settings(
      inConfig(It)(Defaults.testSettings)
    ).settings(
        commonItSettings
      ).settings(
        unmanagedJars in Compile += JavaTools,
        EnsimeKeys.unmanagedSourceArchives += file(".").getCanonicalFile / "openjdk-langtools/openjdk6-langtools-src.zip",
        libraryDependencies ++= Seq(
          "com.h2database" % "h2" % "1.4.190",
          "com.typesafe.slick" %% "slick" % "3.1.1",
          "com.zaxxer" % "HikariCP-java6" % "2.3.12",
          // Netbeans 7.4+ needs Java 7 (7.3 only needs it at runtime)
          "org.netbeans.api" % "org-netbeans-api-java" % "RELEASE731",
          "org.netbeans.api" % "org-netbeans-modules-java-source" % "RELEASE731",
          // lucene 4.8+ needs Java 7: http://www.gossamer-threads.com/lists/lucene/general/225300
          "org.apache.lucene" % "lucene-core" % "4.7.2",
          "org.apache.lucene" % "lucene-analyzers-common" % "4.7.2",
          "org.ow2.asm" % "asm-commons" % "5.0.4",
          "org.ow2.asm" % "asm-util" % "5.0.4",
          "org.scala-lang" % "scala-compiler" % scalaVersion.value,
          "org.scala-lang" % "scalap" % scalaVersion.value,
          "com.typesafe.akka" %% "akka-actor" % akkaVersion,
          "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
          "org.scala-refactoring" %% "org.scala-refactoring.library" % "0.8.0",
          "commons-lang" % "commons-lang" % "2.6",
          "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0"
        ) ++ logback ++ testLibs(scalaVersion.value, "it,test")
      )

  lazy val server = Project("server", file("server")).dependsOn(
    core, swanky, jerky,
    s_express % "test->test",
    swanky % "test->test",
    // depend on "it" dependencies in "test" or sbt adds them to the release deps!
    // https://github.com/sbt/sbt/issues/1888
    core % "test->test",
    core % "it->it",
    testingDocs % "test,it"
  ).configs(It).settings(
      commonSettings
    ).settings(
      inConfig(It)(Defaults.testSettings)
    ).settings(
        commonItSettings
      ).settings(
        libraryDependencies ++= Seq(
          "com.typesafe.akka" %% "akka-stream-experimental" % streamsVersion,
          "com.typesafe.akka" %% "akka-http-core-experimental" % streamsVersion,
          "com.typesafe.akka" %% "akka-http-experimental" % streamsVersion,
          "com.typesafe.akka" %% "akka-http-spray-json-experimental" % streamsVersion,
          "com.typesafe.akka" %% "akka-http-xml-experimental" % streamsVersion,
          "com.typesafe.akka" %% "akka-http-testkit-experimental" % streamsVersion % "test,it"
        ) ++ testLibs(scalaVersion.value, "it,test")
      )

  // manual root project so we can exclude the testing projects from publication
  lazy val root = Project(id = "ensime", base = file(".")) settings(commonSettings) aggregate (
    api, monkeys, util, testutil, s_express, jerky, swanky, core, server
  ) dependsOn (server) settings (
      // e.g. `sbt ++2.11.7 ensime/assembly`
      test in assembly := {},
      aggregate in assembly := false,
      assemblyMergeStrategy in assembly := {
        case PathList("META-INF", "namedservices", xs @ _*) => MergeStrategy.filterDistinctLines
        case "META-INF/netbeans/translate.names" => MergeStrategy.filterDistinctLines
        case "META-INF/namedservices.index" => MergeStrategy.filterDistinctLines
        case "META-INF/generated-layer.xml" => MergeStrategy.rename
        case PathList("org", "apache", "commons", "vfs2", xs @ _*) => MergeStrategy.first // assumes our classpath is setup correctly
        case other => MergeStrategy.defaultMergeStrategy(other)
      },
      assemblyExcludedJars in assembly <<= (fullClasspath in assembly).map { everything =>
        everything.filter { attr =>
          val n = attr.data.getName
          n.startsWith("scala-library") | n.startsWith("scala-compiler") |
          n.startsWith("scala-reflect") | n.startsWith("scalap")
        } :+ Attributed.blank(JavaTools)
      },
      assemblyJarName in assembly := s"ensime_${scalaBinaryVersion.value}-${version.value}-assembly.jar"
    )
}
