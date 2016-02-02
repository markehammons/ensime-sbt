// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.util

import java.io.File

import org.scalatest._

class EscapingStringInterpolationSpec extends FlatSpec with Matchers {

  import EscapingStringInterpolation._

  "EscapingStringInterpolation" should "hijack File" in {
    val f = new File("""C:\""")
    s"$f" shouldBe """C:\\"""
  }

  it should "not affect normal interpolation" in {
    s"nothing here" shouldBe "nothing here"

    val thing = "foo"
    s"${1 + 2} $thing" shouldBe "3 foo"
  }

}
