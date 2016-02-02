// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.config

import org.scalatest.{ FunSpec, Matchers }
import org.ensime.util.file._
import org.ensime.util.EscapingStringInterpolation

import org.ensime.api._

import scala.util.Properties
import scala.util.Try

class EnsimeConfigSpec extends FunSpec with Matchers {

  import EscapingStringInterpolation._

  def test(dir: File, contents: String, testFn: (EnsimeConfig) => Unit): Unit = {
    testFn(EnsimeConfigProtocol.parse(contents))
  }

  describe("ProjectConfigSpec") {

    it("should parse a simple config") {
      withTempDir { dir =>
        val abc = dir / "abc"
        val cache = dir / ".ensime_cache"
        val javaHome = File(Properties.javaHome)

        abc.mkdirs()
        cache.mkdirs()

        test(dir, s"""
(:name "project"
 :scala-version "2.10.4"
 :java-home "$javaHome"
 :root-dir "$dir"
 :cache-dir "$cache"
 :reference-source-roots ()
 :debug-args ("-Dthis=that")
 :subprojects ((:name "module1"
                :scala-version "2.10.4"
                :depends-on-modules ()
                :target "$abc"
                :test-target "$abc"
                :source-roots ()
                :reference-source-roots ()
                :compiler-args ()
                :runtime-deps ()
                :test-deps ())))""", { implicit config =>

          assert(config.name == "project")
          assert(config.scalaVersion == "2.10.4")
          val module1 = config.modules("module1")
          assert(module1.name == "module1")
          assert(module1.dependencies.isEmpty)
          assert(!config.sourceMode)
          assert(config.debugVMArgs === List("-Dthis=that"))
        })
      }
    }

    it("should parse a minimal config for a binary only project") {
      withTempDir { dir =>
        val abc = dir / "abc"
        val cache = dir / ".ensime_cache"
        val javaHome = File(Properties.javaHome)

        abc.mkdirs()
        cache.mkdirs()

        test(dir, s"""
(:name "project"
 :scala-version "2.10.4"
 :java-home "$javaHome"
 :root-dir "$dir"
 :cache-dir "$cache"
 :subprojects ((:name "module1"
                :scala-version "2.10.4"
                :targets ("$abc"))))""", { implicit config =>

          assert(config.name == "project")
          assert(config.scalaVersion == "2.10.4")
          val module1 = config.modules("module1")
          assert(module1.name == "module1")
          assert(module1.dependencies.isEmpty)
          assert(module1.targetDirs.size === 1)
        })
      }
    }

    it("should base class paths on source-mode value") {
      List(true, false) foreach { (sourceMode: Boolean) =>
        withTempDir { dir =>
          val abc = dir / "abc"
          val cache = dir / ".ensime_cache"
          val javaHome = File(Properties.javaHome)

          abc.mkdirs()
          cache.mkdirs()

          test(dir, s"""
(:name "project"
 :scala-version "2.10.4"
 :java-home "$javaHome"
 :root-dir "$dir"
 :cache-dir "$cache"
 :source-mode ${if (sourceMode) "t" else "nil"}
 :subprojects ((:name "module1"
                :scala-version "2.10.4"
                :targets ("$abc"))))""", { implicit config =>
            assert(config.sourceMode == sourceMode)
            assert(config.runtimeClasspath == Set(abc), config)
            assert(config.compileClasspath == (
              if (sourceMode) Set.empty else Set(abc)
            ))
          })
        }
      }
    }
  }
}
