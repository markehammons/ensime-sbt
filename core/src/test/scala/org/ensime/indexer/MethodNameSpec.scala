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
