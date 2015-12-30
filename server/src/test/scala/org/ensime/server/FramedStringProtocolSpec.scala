package org.ensime.server

import akka.util.ByteString
import org.ensime.api._
import org.scalatest._

class FramedStringProtocolSpec extends FlatSpec with Matchers
    with FramedStringProtocol {
  // subclassed FramedStringProtocol so we can get access we want to test

  override def decode(bytes: ByteString): (Option[RpcRequestEnvelope], ByteString) = ???
  override def encode(msg: RpcResponseEnvelope): ByteString = ???

  "FramedStringProtocol" should "write framed strings" in {
    val buffer = writeString("foobar")
    val written = buffer.utf8String

    written shouldBe "000006foobar"
  }

  it should "write multi-byte UTF-8 strings" in {
    val buffer = writeString("€")
    val written = buffer.utf8String

    written shouldBe "000003€"
  }

  it should "read framed strings" in {
    val read = tryReadString(ByteString("000006foobar", "UTF-8"))

    read shouldBe ((Some("foobar"), ByteString()))
  }

  it should "read multi-byte UTF-8 strings" in {
    val read = tryReadString(ByteString("000003€000003€", "UTF-8"))

    read shouldBe ((Some("€"), ByteString("000003€", "UTF-8")))
  }
}
