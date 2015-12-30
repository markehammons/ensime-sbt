package org.ensime.fixture

import java.io.File

import java.nio.charset.Charset
import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.ensime.api._
import org.ensime.core.javac.JavaCompiler
import org.ensime.indexer._
import org.ensime.util._
import org.ensime.util.file._
import org.slf4j.LoggerFactory
import scala.collection.immutable.Queue
import scala.concurrent.Await
import scala.concurrent.duration.Duration

trait JavaCompilerFixture {
  def withJavaCompiler(
    testCode: (TestKitFix, EnsimeConfig, JavaCompiler, JavaStoreReporter) => Any
  ): Any

  def runForPositionInCompiledSource(config: EnsimeConfig, cc: JavaCompiler, lines: String*)(testCode: (SourceFileInfo, Int, String, JavaCompiler) => Any): Any = {
    val contents = lines.mkString("\n")
    var offset = 0
    var points = Queue.empty[(Int, String)]
    val re = """@([a-z0-9\.]*)@"""
    re.r.findAllMatchIn(contents).foreach { m =>
      points :+= ((m.start - offset, m.group(1)))
      offset += ((m.end - m.start))
    }
    val f = new File(config.rootDir, "testing/simple/src/main/java/org/example/Test1.java")
    val file = SourceFileInfo(f, Some(contents.replaceAll(re, "")), None)
    cc.askTypecheckFiles(List(file))
    assert(points.nonEmpty)
    for (pt <- points) {
      testCode(file, pt._1, pt._2, cc)
    }
  }
}

object JavaCompilerFixture {
  private[fixture] def create(
    config: EnsimeConfig,
    reportHandler: ReportHandler
  )(
    implicit
    system: ActorSystem,
    vfs: EnsimeVFS
  ): JavaCompiler = {
    val indexer = TestProbe()
    val parent = TestProbe()
    new JavaCompiler(config, reportHandler)
  }
}

class JavaStoreReporter extends ReportHandler {
  var notes = scala.collection.mutable.HashSet[Note]()
  override def messageUser(str: String): Unit = {}
  override def clearAllJavaNotes(): Unit = { this.notes.clear() }
  override def reportJavaNotes(notes: List[Note]): Unit = { this.notes ++= notes }
}

trait IsolatedJavaCompilerFixture
    extends JavaCompilerFixture
    with IsolatedEnsimeVFSFixture
    with IsolatedTestKitFixture
    with IsolatedSearchServiceFixture {

  override def withJavaCompiler(
    testCode: (TestKitFix, EnsimeConfig, JavaCompiler, JavaStoreReporter) => Any
  ): Any = {
    withVFS { implicit vfs =>
      withTestKit { testkit =>
        import testkit._
        withEnsimeConfig { config =>
          import org.ensime.fixture.JavaCompilerFixture._
          val reportHandler = new JavaStoreReporter
          val cc = create(config, reportHandler)
          testCode(testkit, config, cc, reportHandler)
        }
      }
    }
  }
}

