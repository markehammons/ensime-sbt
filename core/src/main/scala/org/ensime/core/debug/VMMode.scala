package org.ensime.core.debug

sealed abstract class VmMode {
  /**
   * @return True if the vm should be existed for this mode
   */
  def shouldExit: Boolean
}

private case class VmAttach(hostname: String, port: String) extends VmMode {
  override def shouldExit: Boolean = false
}
private case class VmStart(commandLine: String) extends VmMode {
  override def shouldExit: Boolean = true
}

