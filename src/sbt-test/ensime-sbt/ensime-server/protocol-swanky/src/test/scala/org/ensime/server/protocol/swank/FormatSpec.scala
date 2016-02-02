// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.server.protocol.swank

import org.scalatest.FunSpec
import org.ensime.sexp._

// copied from S-Express to avoid a dependency on sexp:test
trait FormatSpec extends FunSpec {
  def assertFormat[T: SexpFormat](start: T, expect: Sexp): Unit = {
    assert(start.toSexp === expect)
    assert(expect.convertTo[T] === start)
  }
}
