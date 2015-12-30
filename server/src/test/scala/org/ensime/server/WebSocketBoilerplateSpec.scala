package org.ensime.server

import akka.actor._
import akka.http.scaladsl.model.ws._
import akka.stream._
import akka.stream.scaladsl._
import akka.testkit._
import akka.util.ByteString
import org.ensime.core.AkkaFlatSpec
import org.scalatest._
import spray.json._

class WebSocketBoilerplateSpec extends AkkaFlatSpec {
  import WebSocketBoilerplate._

  implicit val mat = ActorMaterializer()

  import system.dispatcher
  import akka.pattern.pipe

  "WebSocketBoilerplate" should "produce Flow[In, Out]" in {
    val service = TestProbe()

    var target: ActorRef = null
    val flow = actorRefAsFlow[String, Long] { t =>
      target = t
      service.ref
    }

    val client = TestProbe()

    Source.single("hello").via(flow).runWith(Sink.head).pipeTo(client.ref)

    service.expectMsg("hello")
    service.send(target, 13L)
    service.expectNoMsg()
    client.expectMsg(13L)

    // it would be good to check that errors / closing will stop the
    // actor but that's perhaps testing the framework.

    // TODO: use Streams TestKit as much as possible here
  }

  case class Foo(a: String)
  case class Bar(b: Long)
  import DefaultJsonProtocol._
  implicit def FooFormat = jsonFormat1(Foo)
  implicit def BarFormat = jsonFormat1(Bar)
  val foo = Foo("hello")
  val bar = Bar(13L)

  it should "produce a marshalled Flow that accepts valid messages" in {
    // This is quite horrible and really highlights why a BidiFlow
    // model would be better. WebSockets are *not* request / response
    // (like this).
    val user = Flow[Foo].map { f =>
      f shouldBe foo
      bar
    }
    val endpoints = jsonMarshalledMessageFlow(user)

    val input = TextMessage(foo.toJson.compactPrint)
    val client = TestProbe()

    Source.single(input).via(endpoints).runWith(Sink.head).pipeTo(client.ref)

    client.expectMsg(TextMessage(bar.toJson.prettyPrint))
  }

  it should "produce a marshalled Flow that errors on bad message" in {
    val user = Flow[Foo].map { f =>
      f shouldBe foo
      bar
    }
    val endpoints = jsonMarshalledMessageFlow(user)

    val input = BinaryMessage(ByteString(0, 1, 2))
    val client = TestProbe()

    Source.single(input).via(endpoints).runWith(Sink.head).pipeTo(client.ref)

    client.expectMsgPF() {
      case Status.Failure(_) =>
    }
  }

  it should "produce a marshalled Flow that errors on bad inbound JSON" in {
    val user = Flow[Foo].map { _ => bar }
    val endpoints = jsonMarshalledMessageFlow(user)

    val input = TextMessage.Strict("""{}""")
    val client = TestProbe()

    Source.single(input).via(endpoints).runWith(Sink.head).pipeTo(client.ref)

    client.expectMsgPF() {
      case Status.Failure(e: DeserializationException) =>
    }
  }

}
