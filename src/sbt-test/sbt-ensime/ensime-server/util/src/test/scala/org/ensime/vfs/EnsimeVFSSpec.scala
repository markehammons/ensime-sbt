package org.ensime.vfs

import java.io.File
import org.scalatest._

class EnsimeVFSSpec extends FlatSpec with Matchers {
  "RichVFS.vjar" should "return jar files with URI components in their file name" in {
    val jar = EnsimeVFS().vjar(new File("src/test/fixtures/oh%3ahai+there.jar"))
    jar.isReadable shouldBe true
  }
}
