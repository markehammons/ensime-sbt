// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.sexp.formats

import org.scalatest.FunSpec
import org.ensime.sexp._

trait FormatSpec extends FunSpec {
  val foo = SexpString("foo")
  val bar = SexpSymbol("bar")

  def assertFormat[T: SexpFormat](start: T, expect: Sexp): Unit = {
    assert(start.toSexp === expect)
    assert(expect.convertTo[T] === start)
  }
}
