package runEnsimeMain

import java.io.{File, PrintWriter}

object printHello extends App {
  val output = new PrintWriter(new File(args(0)))
  output.write("Hello!")
  output.close
}