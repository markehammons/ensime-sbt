// Copyright (C) 2015 ENSIME Authors
// License: GPL 3.0
package org.ensime

import java.io.File
import Predef.{ any2stringadd => _, _ => _ }
import org.ensime.api._
import org.ensime.util.file._

package object config {

  implicit class RichEnsimeConfig(val c: EnsimeConfig) extends AnyVal {
    def scalaSourceFiles: Set[File] = for {
      module: EnsimeModule <- c.modules.values.toSet
      root <- module.sourceRoots
      file <- root.tree
      if file.isFile & file.getName.endsWith(".scala")
    } yield file
  }

}
