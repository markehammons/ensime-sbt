// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.indexer

import java.io.File

import org.apache.commons.vfs2._
import org.apache.commons.vfs2.impl._

private[indexer] abstract class ExtSelector extends FileSelector {
  def includeFile(f: FileObject): Boolean = include(f.getName.getExtension)
  def includeFile(info: FileSelectInfo): Boolean = includeFile(info.getFile)
  def includeFile(f: File): Boolean = include.exists(f.getName.endsWith(_))
  def traverseDescendents(info: FileSelectInfo) = true
  def include: Set[String]
}

object EnsimeVFS {
  def apply(): EnsimeVFS = {
    val vfsInst = new StandardFileSystemManager()
    vfsInst.init()
    new EnsimeVFS(vfsInst)
  }

  private[indexer] object JarSelector extends ExtSelector {
    val include = Set("jar")
  }

  private[indexer] object ClassfileSelector extends ExtSelector {
    val include = Set("class")
  }

  private[indexer] object SourceSelector extends ExtSelector {
    val include = Set("scala", "java")
  }
}

class EnsimeVFS(val vfs: DefaultFileSystemManager) {

  private[indexer] implicit def toFileObject(f: File): FileObject = vfile(f)

  private def withContext[T](msg: String)(t: => T): T = try { t } catch {
    case e: FileSystemException => throw new FileSystemException(e.getMessage + " in " + msg, e)
  }

  private[indexer] def vfile(name: String) = withContext(s"$name =>")(
    vfs.resolveFile(name.intern)
  )
  private[indexer] def vfile(file: File) = withContext(s"$file =>")(
    vfs.toFileObject(file)
  )
  private[indexer] def vres(path: String) = withContext(s"$path =>")(
    vfs.resolveFile(("res:" + path).intern)
  )
  private[indexer] def vjar(jar: File) = withContext(s"$jar =>")(
    vfs.resolveFile(("jar:" + jar.getAbsolutePath).intern)
  )
  private[indexer] def vjar(jar: FileObject) = withContext(s"$jar =>")(
    vfs.resolveFile(("jar:" + jar.getName.getURI).intern)
  )

  def close(): Unit = {
    vfs.close()
  }
}
