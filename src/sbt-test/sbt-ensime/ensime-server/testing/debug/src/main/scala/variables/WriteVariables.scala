package variables

import debug.Helper._

object WriteVariables {

  def main(args: Array[String]) {
    var a = true
    var b = 'c'
    var c = 3.asInstanceOf[Short]
    var d = 4
    var e = 5L
    var f = 1.0f
    var g = 2.0
    var h = "test"
    var i = Array(1, 2, 3)
    var j = List(4, 5, 6)
    var k = Array(One("one"), 1, true)
    var l = NullToString

    noop(None)
  }

}
