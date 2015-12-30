package org.ensime.intg

import akka.event.slf4j.SLF4JLogging
import org.apache.commons.io.FileUtils
import org.ensime.api._
import org.ensime.fixture._
import org.scalatest.{ Matchers, WordSpec }
import org.ensime.util.file._

/**
 * Tries to simulate SBT clean/compile to stress test timing issues.
 *
 * (which also tests the file watchers).
 */
class CompileTimingTest extends WordSpec with Matchers
    with IsolatedEnsimeConfigFixture
    with IsolatedTestKitFixture
    with IsolatedProjectFixture
    with SLF4JLogging {

  val original = EnsimeConfigFixture.TimingTestProject

  "ensime-server" should {
    "handle multiple sbt clean / compile" in {
      withEnsimeConfig { implicit config =>
        withTestKit { implicit testkit =>
          withProject { (project, asyncHelper) =>
            import testkit._

            val sourceRoot = scalaMain(config)

            val example = sourceRoot / "p1/Example.scala"

            val target = (mainTarget / "..").canon
            val targetBak = (target / "../scala-classes.bak").canon

            val exampleDiskInfo = SourceFileInfo(example, None, None)
            val exampleMemory = SourceFileInfo(example, None, Some(example))

            FileUtils.copyDirectory(target, targetBak)

            project ! TypecheckFileReq(exampleDiskInfo)
            expectMsg(VoidResponse)
            asyncHelper.expectMsg(FullTypeCheckCompleteEvent)

            // GUI usually responds to each typecheck by requesting symbols
            project ! SymbolDesignationsReq(Right(exampleDiskInfo), 0, 70, SourceSymbol.allSymbols)
            expectMsgType[SymbolDesignations]

            // typecheck an in-memory version of the file
            project ! TypecheckFileReq(exampleMemory)
            expectMsg(VoidResponse)

            asyncHelper.expectMsg(FullTypeCheckCompleteEvent)
            project ! SymbolDesignationsReq(Right(exampleMemory), 0, 70, SourceSymbol.allSymbols)
            expectMsgType[SymbolDesignations]

            // simulate sbt clean https://github.com/sbt/sbt/issues/106
            FileUtils.deleteDirectory(target)

            asyncHelper.receiveN(2) should contain theSameElementsAs (Seq(
              FullTypeCheckCompleteEvent,
              CompilerRestartedEvent
            ))

            project ! SymbolDesignationsReq(Right(exampleDiskInfo), 0, 70, SourceSymbol.allSymbols)
            expectMsgType[SymbolDesignations]

            // simulate sbt compile
            FileUtils.copyDirectory(targetBak, target)

            asyncHelper.receiveN(2) should contain theSameElementsAs (Seq(
              FullTypeCheckCompleteEvent,
              CompilerRestartedEvent
            ))

            project ! SymbolDesignationsReq(Right(exampleDiskInfo), 0, 70, SourceSymbol.allSymbols)
            expectMsgType[SymbolDesignations]
          }
        }
      }
    }
  }

}
