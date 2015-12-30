package org.ensime.core.javac

import akka.event.slf4j.SLF4JLogging
import com.sun.source.tree.Scope
import com.sun.source.util.{ JavacTask, TreePath, Trees }
import java.io.{ File, FileInputStream, InputStream }
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import javax.lang.model.`type`.TypeMirror
import javax.tools._
import org.ensime.api._
import org.ensime.core.DocSigPair
import org.ensime.util.ReportHandler
import org.ensime.util.file._
import scala.collection.JavaConverters._
import scala.reflect.internal.util.{ BatchSourceFile, RangePosition, SourceFile }
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.CompilerControl
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.reporters.Reporter
import scala.tools.refactoring.analysis.GlobalIndexes

class JavaCompiler(
    val config: EnsimeConfig,
    val reportHandler: ReportHandler
) extends JavaDocFinding with JavaCompletion with SLF4JLogging {

  private val listener = new JavaDiagnosticListener()
  private val silencer = new SilencedDiagnosticListener()
  private val cp = (config.allJars ++ config.targetClasspath).mkString(File.pathSeparator)

  // needs to be recreated in JDK6. JDK7 seems more capable of reuse.
  def getTask(
    lint: String,
    listener: DiagnosticListener[JavaFileObject],
    files: List[JavaFileObject]
  ): JavacTask = {
    // TODO: take a charset for each invocation
    val compiler = ToolProvider.getSystemJavaCompiler()
    val fileManager = compiler.getStandardFileManager(listener, null, DefaultCharset)
    compiler.getTask(null, fileManager, listener, List(
      "-cp", cp, "-Xlint:" + lint, "-proc:none"
    ).asJava, null, files.asJava).asInstanceOf[JavacTask]
  }

  def askTypecheckFiles(files: List[SourceFileInfo]): Unit = {
    reportHandler.clearAllJavaNotes()
    typecheckAll(files.map(getJavaFileObject))
  }

  def askTypeAtPoint(file: SourceFileInfo, offset: Int): Option[TypeInfo] = {
    pathToPoint(file, offset) flatMap {
      case (info: CompilationInfo, path: TreePath) =>
        getTypeMirror(info, offset).map(typeMirrorToTypeInfo)
    }
  }

  def askDocSignatureAtPoint(file: SourceFileInfo, offset: Int): Option[DocSigPair] = {
    pathToPoint(file, offset) flatMap {
      case (info: CompilationInfo, path: TreePath) =>
        docSignature(info, path)
    }
  }

  def askCompletionsAtPoint(
    file: SourceFileInfo, offset: Int, maxResults: Int, caseSens: Boolean
  ): CompletionInfoList = {
    completionsAt(file, offset, maxResults, caseSens)
  }

  protected def pathToPoint(file: SourceFileInfo, offset: Int): Option[(CompilationInfo, TreePath)] = {
    val infos = typecheckForUnits(List(getJavaFileObject(file)))
    infos.headOption.flatMap { info =>
      val path = Option(new TreeUtilities(info).pathFor(offset))
      path.map { p => (info, p) }
    }
  }

  protected def scopeForPoint(file: SourceFileInfo, offset: Int): Option[(CompilationInfo, Scope)] = {
    val infos = typecheckForUnits(List(getJavaFileObject(file)))
    infos.headOption.flatMap { info =>
      val path = Option(new TreeUtilities(info).scopeFor(offset))
      path.map { p => (info, p) }
    }
  }

  private def typeMirrorToTypeInfo(tm: TypeMirror): TypeInfo = {
    BasicTypeInfo(tm.toString, -1, DeclaredAs.Class, tm.toString, List(), List(), Some(EmptySourcePosition()), None)
  }

  private def getTypeMirror(info: CompilationInfo, offset: Int): Option[TypeMirror] = {
    val path = Option(new TreeUtilities(info).pathFor(offset))
    // Uncomment to debug the AST path.
    //for (p <- path) { for (t <- p) { System.err.println(t.toString()) } }
    path.flatMap { p => Option(info.getTrees().getTypeMirror(p)) }
  }

  private def typecheckAll(files: List[JavaFileObject]): Unit = {
    val task = getTask("all", listener, files)
    val t = System.currentTimeMillis()
    task.parse()
    task.analyze()
    log.info("Parsed and analyzed: " + (System.currentTimeMillis() - t) + "ms")
  }

  private def typecheckForUnits(files: List[JavaFileObject]): Vector[CompilationInfo] = {
    val task = getTask("none", silencer, files)
    val t = System.currentTimeMillis()
    val units = task.parse().asScala.map(new CompilationInfo(task, _)).toVector
    task.analyze()
    log.info("Parsed and analyzed for trees: " + (System.currentTimeMillis() - t) + "ms")
    units
  }

  private class JavaObjectWithContents(val f: File, val contents: String)
      extends SimpleJavaFileObject(f.toURI, JavaFileObject.Kind.SOURCE) {
    override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = contents
  }

  private class JavaObjectFromFile(val f: File)
      extends SimpleJavaFileObject(f.toURI, JavaFileObject.Kind.SOURCE) {
    override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = f.readString
    override def openInputStream(): InputStream = new FileInputStream(f)
  }

  private def getJavaFileObject(sf: SourceFileInfo): JavaFileObject = sf match {
    case SourceFileInfo(f, None, None) => new JavaObjectFromFile(f)
    case SourceFileInfo(f, Some(contents), None) => new JavaObjectWithContents(f, contents)
    case SourceFileInfo(f, None, Some(contentsIn)) => new JavaObjectWithContents(f, f.readString)
  }

  private class JavaDiagnosticListener extends DiagnosticListener[JavaFileObject] with ReportHandler {
    def report(diag: Diagnostic[_ <: JavaFileObject]): Unit = {
      reportHandler.reportJavaNotes(List(
        Note(
          diag.getSource().getName(),
          diag.getMessage(Locale.ENGLISH),
          diag.getKind() match {
            case Diagnostic.Kind.ERROR => NoteError
            case Diagnostic.Kind.WARNING => NoteWarn
            case Diagnostic.Kind.MANDATORY_WARNING => NoteWarn
            case _ => NoteInfo
          },
          diag.getStartPosition() match {
            case x if x > -1 => x.toInt
            case _ => diag.getPosition().toInt
          },
          diag.getEndPosition().toInt,
          diag.getLineNumber().toInt,
          diag.getColumnNumber().toInt
        )
      ))
    }
  }

  private class SilencedDiagnosticListener extends DiagnosticListener[JavaFileObject] with ReportHandler {
    def report(diag: Diagnostic[_ <: JavaFileObject]): Unit = {}
  }

}

