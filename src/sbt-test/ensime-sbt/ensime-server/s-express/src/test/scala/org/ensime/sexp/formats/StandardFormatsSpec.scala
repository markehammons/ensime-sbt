// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.sexp.formats

import java.io.File
import java.net.URI
import java.util.Date
import java.util.UUID

import org.ensime.sexp._

class StandardFormatsSpec extends FormatSpec with StandardFormats with BasicFormats {
  describe("StandardFormats") {
    it("should support Option") {
      val some = Some("thing")
      assertFormat(some: Option[String], SexpList(SexpString("thing")))
      assertFormat(None: Option[String], SexpNil)
    }

    it("should support Either") {
      val left = Left(13)
      val right = Right("thirteen")
      assertFormat(
        left: Either[Int, String],
        SexpNumber(13)
      )
      assertFormat(
        right: Either[Int, String],
        SexpString("thirteen")
      )
    }

    it("should support UUID") {
      val uuid = UUID.randomUUID()
      assertFormat(uuid, SexpString(uuid.toString))
    }

    // it("should support URL") {
    //   val github = "http://github.com/ensime/"
    //   val url = new URL(github)
    //   // hack to avoid calling URL.equals, which talks to the interwebz
    //   assert(url.toSexp === SexpString(github))
    //   assert(SexpString(github).convertTo[URL].toExternalForm === github)
    // }

    it("should support URI") {
      val github = "http://github.com/ensime/"
      val url = new URI(github)
      assertFormat(url, SexpString(github))
    }

    it("should support File") {
      val file = new File("foo")
      assertFormat(file, SexpString("foo"))
    }

    it("should support Date") {
      val date = new Date(1414326493000L)
      assertFormat(date, SexpString("2014-10-26T12:28:13+00:00"))

      val unix = new Date(0L)
      assertFormat(unix, SexpString("1970-01-01T00:00:00+00:00"))
    }
  }
}
