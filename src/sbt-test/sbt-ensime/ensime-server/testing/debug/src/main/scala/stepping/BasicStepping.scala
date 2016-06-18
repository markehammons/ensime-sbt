package stepping

/**
 * Tests that basic step operations can be performed.
 */
object BasicStepping {
  def main(args: Array[String]): Unit = {
    threeNoops() // Start here

    threeNoops() // Step over to here, then step in

    noop() // Reach here after stepping out
  }

  def threeNoops(): Unit = {
    noop() // Reach here from stepping in
    noop() // Don't get here because we do a step out
    noop()
  }

  def noop(value: Any): Unit = {}
}
