package org.ensime.core

import akka.util.ByteString
import org.ensime.api._

/**
 * Provides a generic message encoding/dblocking I/O API for reading and writing to the wire.
 */
trait Protocol {

  /**
   * Attempt to read a single message from the given ByteString
   * @param bytes The input bytes
   * @return a tuple containing a optional read message and the remaining bytes after message is removed.
   */
  def decode(bytes: ByteString): (Option[RpcRequestEnvelope], ByteString)
  def encode(msg: RpcResponseEnvelope): ByteString
}
