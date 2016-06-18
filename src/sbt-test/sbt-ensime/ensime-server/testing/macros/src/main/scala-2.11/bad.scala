package org.ensime.testing.macros.bad

import scala.language.experimental.macros
import scala.reflect.macros.whitebox.Context

class Impl(val c: Context) {
  import c.universe._

  // return something nonsensical for the context and cause the
  // compiler to "fail in a background thread"
  def bad[T: WeakTypeTag]: Tree = c.parse("trait Foo")
}

object BadMacro {
  def bad[T]: T = macro Impl.bad[T]
}
