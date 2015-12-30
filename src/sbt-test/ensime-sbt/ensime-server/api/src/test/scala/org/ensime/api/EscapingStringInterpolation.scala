package org.ensime.api

import java.io.File

/**
 * String interpolation that automatically escapes known "bad" types
 * (such as `File` on Windows) and *ONLY* for use in ENSIME tests when
 * asserting on wire formats.
 *
 * Import this to hijack the default string interpolator, muahahaha!
 * Also, don't be evil.
 */
object EscapingStringInterpolation {
  case class StringContext(parts: String*) {
    private val delegate = new scala.StringContext(parts: _*)
    def s(args: Any*): String = {
      val hijacked = args.map {
        case f: File => f.toString.replace("""\""", """\\""")
        case other => other
      }
      delegate.s(hijacked: _*)
    }
  }
}
