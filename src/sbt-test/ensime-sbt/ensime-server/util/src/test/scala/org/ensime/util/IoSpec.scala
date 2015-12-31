// Copyright (C) 2015 ENSIME Authors
// License: GPL 3.0
package org.ensime.util

import Predef.{ any2stringadd => _ }
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

import org.scalatest._

class IoSpec extends FlatSpec with Matchers {
  import io._

  val bytes = Array[Byte](0, 1, -2, 127, -128)

  "io._" should "convert to a byte array" in {
    val in = new ByteArrayInputStream(bytes)
    in.toByteArray() shouldEqual bytes
  }

  it should "drain an output stream to an input stream" in {
    val in = new ByteArrayInputStream(bytes)
    val out = new ByteArrayOutputStream()

    out.drain(in)
    out.toByteArray() shouldEqual bytes

    // no way to confirm that the streams are closed, thanks for that J2SE
  }

}
