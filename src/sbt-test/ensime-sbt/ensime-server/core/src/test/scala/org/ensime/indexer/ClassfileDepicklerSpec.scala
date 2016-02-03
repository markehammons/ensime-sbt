// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.indexer

import org.scalatest.{ BeforeAndAfterAll, FunSpec, Matchers }

class ClassfileDepicklerSpec extends FunSpec with Matchers with BeforeAndAfterAll {

  var vfs: EnsimeVFS = _

  override def beforeAll(): Unit = {
    vfs = EnsimeVFS()
  }

  override def afterAll(): Unit = {
    vfs.close()
  }

  describe("ClassfileDepickler") {
    it("don't depickle J2SE classes") {
      assert(new ClassfileDepickler(vfs.vres("java/lang/String.class")).scalasig === None)
    }

    it("support typical Scala classes") {
      assert(new ClassfileDepickler(vfs.vres("scala/collection/immutable/List.class")).scalasig.nonEmpty)
    }

    it("don't expect anything in companions") {
      assert(new ClassfileDepickler(vfs.vres("scala/collection/immutable/List$.class")).scalasig === None)
    }

    it("don't expect anything in closures") {
      assert(new ClassfileDepickler(vfs.vres("scala/io/Source$$anonfun$1.class")).scalasig === None)
    }

    it("can find type aliases") {
      assert(new ClassfileDepickler(vfs.vres("scala/Predef.class")).getTypeAliases.contains(
        RawType(s"scala.Predef$$String", Public)
      ))
    }
  }
}
