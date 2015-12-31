// Copyright (C) 2015 Sam Halliday
// License: http://www.gnu.org/licenses/gpl.html

import difflib.DiffUtils
import sbt._
import Keys._
import collection.JavaConverters._
import util.Properties
import org.ensime.CommandSupport

object EnsimeSbtTestSupport extends AutoPlugin with CommandSupport {

  override def trigger = allRequirements

  override lazy val buildSettings = Seq(
    commands += Command.args("ensimeExpect", "<args>")(ensimeExpect)
  )

  // must be a Command to avoid recursing into aggregate projects
  def ensimeExpect: (State, Seq[String]) => State = { (state, args) =>
    val extracted = Project.extract(state)
    implicit val s = state
    implicit val pr = extracted.currentRef
    implicit val bs = extracted.structure

    val baseDir = baseDirectory.gimme
    val log = state.log

    val jdkHome = javaHome.gimme.getOrElse(file(Properties.jdkHome)).getAbsolutePath

    val List(orig, expect) = args.map { filename =>
      log.info(s"parsing ${file(filename).getCanonicalPath}")
      // not windows friendly
      IO.readLines(file(filename)).map {
        line =>
          line.
            replace(baseDir.getCanonicalPath, "BASE_DIR").
            replace(Properties.userHome + "/.ivy2/", "IVY_DIR/").
            replace(sys.env("JDK_LANGTOOLS_SRC"), "JDK_LANGTOOLS_SRC").
            replace("/usr/lib/jvm/java-6-oracle", "JDK_HOME").
            replace(jdkHome, "JDK_HOME").
            replaceAll(raw""""-Dplugin\.version=[.\d]++(-SNAPSHOT)?"""", "").
            replace(s""" "-Dsbt.global.base=BASE_DIR/global" """, "")
      }
    }.toList

    val deltas = DiffUtils.diff(orig.asJava, expect.asJava).getDeltas.asScala
    if (!deltas.isEmpty) {
      // for local debugging
      IO.write(file(Properties.userHome + "/ensime-got"), expect.mkString("\n"))
      throw new MessageOnlyException(s".ensime diff: ${deltas.mkString("\n")}")
    }

    state
  }

}
