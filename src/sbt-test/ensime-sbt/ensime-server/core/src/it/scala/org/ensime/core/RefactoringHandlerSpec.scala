package org.ensime.core

import java.io.File
import org.scalatest._

import org.ensime.api._
import org.ensime.fixture._

class RefactoringHandlerSpec extends WordSpec with Matchers
    with IsolatedAnalyzerFixture with RichPresentationCompilerTestUtils
    with GivenWhenThen {

  val encoding = "UTF-16"
  def original = EnsimeConfigFixture.EmptyTestProject.copy(
    compilerArgs = List("-encoding", encoding)
  )

  // transitionary methods
  def ContentsSourceFileInfo(file: File, contents: String) =
    SourceFileInfo(file, Some(contents))
  def ContentsInSourceFileInfo(file: File, contentsIn: File) =
    SourceFileInfo(file, contentsIn = Some(contentsIn))

  "RefactoringHandler" should {
    "format files and preserve encoding" in withAnalyzer { (config, analyzerRef) =>
      val file = srcFile(config, "abc.scala", contents(
        "package blah",
        "   class Something {",
        "def f(i:   Int) =1",
        " val x = (1\u21922)",
        "   }"
      ), write = true, encoding = encoding)

      val analyzer = analyzerRef.underlyingActor

      analyzer.handleFormatFiles(List(new File(file.path)))
      val fileContents = readSrcFile(file, encoding)

      val expectedContents = contents(
        "package blah",
        "class Something {",
        "  def f(i: Int) = 1",
        "  val x = (1 \u2192 2)",
        "}"
      )
      assert(fileContents === expectedContents)
    }

    "format files from ContentsSourceFileInfo with handleFormatFile" in withAnalyzer { (dir, analyzerRef) =>
      val content = contents(
        "package blah",
        "   class  Something   {}"
      )
      val analyzer = analyzerRef.underlyingActor

      val formatted = analyzer.handleFormatFile(ContentsSourceFileInfo(new File("abc.scala"), content))
      val expectedContents = contents(
        "package blah",
        "class Something {}",
        ""
      )
      assert(formatted === expectedContents)
    }

    "format files from ContentsInSourceFileInfo with handleFormatFile and handle encoding" in withAnalyzer { (dir, analyzerRef) =>
      val file = srcFile(dir, "tmp-contents", contents(
        "package blah",
        "   class  Something   {}"
      ), write = true, encoding = encoding)

      val analyzer = analyzerRef.underlyingActor

      val formatted = analyzer.handleFormatFile(ContentsInSourceFileInfo(new File("abc.scala"), new File(file.path)))
      val expectedContents = contents(
        "package blah",
        "class Something {}",
        ""
      )
      assert(formatted === expectedContents)
    }

    "format files from FileSourceFileInfo with handleFormatFile and handle encoding" in withAnalyzer { (dir, analyzerRef) =>
      val file = srcFile(dir, "abc.scala", contents(
        "package blah",
        "   class  Something   {}"
      ), write = true, encoding = encoding)

      val analyzer = analyzerRef.underlyingActor

      val formatted = analyzer.handleFormatFile(SourceFileInfo(new File(file.path)))
      val expectedContents = contents(
        "package blah",
        "class Something {}",
        ""
      )
      assert(formatted === expectedContents)
    }

    "not format invalid files" in withAnalyzer { (config, analyzerRef) =>
      val file = srcFile(config, "abc.scala", contents(
        "package blah",
        "invalid scala syntax"
      ), write = true, encoding = encoding)

      val analyzer = analyzerRef.underlyingActor

      analyzer.handleFormatFiles(List(new File(file.path)))
      val fileContents = readSrcFile(file, encoding)

      val expectedContents = contents(
        "package blah",
        "invalid scala syntax"
      )
      assert(fileContents === expectedContents)
    }

    //
    // core/src/main/scala/org/ensime/core/Refactoring.scala#L.239
    //
    "organize imports when 3 imports exist" in withAnalyzer { (dir, analyzerRef) =>

      // Please refer Scala IDE
      // scala-refactoring/src/test/scala/scala/tools/refactoring/tests/implementations/imports/
      // --> OrganizeImportsWildcardsTest.scala

      // OrganizeImports need some paren --> "{...}"
      val file = srcFile(dir, "tmp-contents", contents(
        "import java.lang.Integer.{valueOf => vo}",
        "import java.lang.Integer.toBinaryString",
        "import java.lang.String.valueOf",
        " ",
        "trait Temp {",
        "  valueOf(5)",
        "  vo(\"5\")",
        "  toBinaryString(27)",
        "}"
      ), write = true, encoding = encoding)

      val analyzer = analyzerRef.underlyingActor

      val procId = 1
      analyzer.handleRefactorPrepareRequest(
        new PrepareRefactorReq(
          procId, 'Ignored, OrganiseImportsRefactorDesc(new File(file.path)), false
        )
      )
      analyzer.handleRefactorExec(
        new ExecRefactorReq(procId, RefactorType.OrganizeImports)
      )

      val formatted = readSrcFile(file, encoding)
      val expectedContents = contents(
        "import java.lang.Integer.{toBinaryString, valueOf => vo}",
        "import java.lang.String.valueOf",
        " ",
        "trait Temp {",
        "  valueOf(5)",
        "  vo(\"5\")",
        "  toBinaryString(27)",
        "}"
      )
      assert(formatted === expectedContents)
    }

    "add imports on the first line" in withAnalyzer { (dir, analyzerRef) =>
      val file = srcFile(dir, "tmp-contents", contents(
        "import java.lang.Integer.toBinaryString",
        "import java.lang.String.valueOf",
        "",
        "trait Temp {",
        "  valueOf(5)",
        "  vo(\"5\")",
        "  toBinaryString(27)",
        "}"
      ), write = true, encoding = encoding)

      val analyzer = analyzerRef.underlyingActor

      val procId = 1
      analyzer.handleRefactorPrepareRequest(
        new PrepareRefactorReq(
          procId, 'Ignored, AddImportRefactorDesc("java.lang.Integer.{valueOf => vo}", new File(file.path)), false
        )
      )
      analyzer.handleRefactorExec(
        new ExecRefactorReq(procId, RefactorType.AddImport)
      )

      val formatted = readSrcFile(file, encoding)

      val expectedContents = contents(
        "import java.lang.Integer.toBinaryString",
        "import java.lang.String.valueOf",
        "import java.lang.Integer.{valueOf => vo}",
        "",
        "trait Temp {",
        "  valueOf(5)",
        "  vo(\"5\")",
        "  toBinaryString(27)",
        "}"
      )

      assert(formatted === expectedContents)
    }

    "add imports on the first line when other examples come" in withAnalyzer { (dir, analyzerRef) =>
      val file = srcFile(dir, "tmp-contents", contents(
        "package org.ensime.testing",
        "",
        "trait Temp {",
        "  valueOf(5)",
        "  vo(\"5\")",
        "  toBinaryString(27)",
        "}"
      ), write = true, encoding = encoding)

      val analyzer = analyzerRef.underlyingActor

      val procId = 1
      analyzer.handleRefactorPrepareRequest(
        new PrepareRefactorReq(
          procId, 'Ignored, AddImportRefactorDesc("java.lang.Integer", new File(file.path)), false
        )
      )
      analyzer.handleRefactorExec(
        new ExecRefactorReq(procId, RefactorType.AddImport)
      )

      val formatted = readSrcFile(file, encoding)

      val expectedContents = contents(
        "package org.ensime.testing",
        "",
        "import java.lang.Integer",
        "",
        "trait Temp {",
        "  valueOf(5)",
        "  vo(\"5\")",
        "  toBinaryString(27)",
        "}"
      )

      assert(formatted === expectedContents)
    }

  }
}
