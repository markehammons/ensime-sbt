// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.server

import java.io.{ PrintWriter, IOException, File }

import akka.event.slf4j.SLF4JLogging

object PortUtil extends SLF4JLogging {

  def writePort(cacheDir: File, port: Int, name: String): Unit = {
    val portfile = new File(cacheDir, name)
    if (!portfile.exists()) {
      log.info("creating portfile " + portfile)
      portfile.createNewFile()
    } else throw new IOException(
      "An ENSIME server might be open already for this project. " +
        "If you are sure this is not the case, please delete " +
        portfile.getAbsolutePath + " and try again"
    )

    portfile.deleteOnExit()
    val out = new PrintWriter(portfile)
    try out.println(port)
    finally out.close()
  }
}
