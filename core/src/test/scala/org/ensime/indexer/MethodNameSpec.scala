// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.indexer

import org.scalatest._

class MemberNameSpec extends WordSpec with Matchers {
  "MemberName" should {
    "remove \"package\" from FQNs" in {
      MemberName(ClassName(PackageName(List("org", "example")), "package"), "member").fqnString shouldBe "org.example.member"
      MemberName(ClassName(PackageName(List("org", "example")), "package$"), "member").fqnString shouldBe "org.example.member"
    }
  }
}
