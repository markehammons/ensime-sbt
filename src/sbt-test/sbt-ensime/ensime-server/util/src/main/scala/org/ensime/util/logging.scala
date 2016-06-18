// Copyright (C) 2015 ENSIME Authors
// License: GPL 3.0
package org.ensime.util

import org.slf4j.bridge.SLF4JBridgeHandler

object Slf4jSetup {
  SLF4JBridgeHandler.removeHandlersForRootLogger()
  SLF4JBridgeHandler.install()

  /**
   * Call for the side effect of setting up SLF4J's bridge.
   */
  def init(): Unit = ()
}
