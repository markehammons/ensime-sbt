package org.ensime.server

import akka.actor._
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.Timeout
import org.ensime.api._
import org.ensime.core._
import scala.concurrent.Future

class WebServerImpl(
    project: ActorRef,
    broadcaster: ActorRef
)(
    implicit
    val config: EnsimeConfig,
    val system: ActorSystem,
    val mat: Materializer,
    val timeout: Timeout
) extends WebServer with DocJarReading {
  import system.dispatcher

  def restHandler(in: RpcRequest): Future[EnsimeServerMessage] = {
    (project ? Canonised(in)).mapTo[EnsimeServerMessage].map(Canonised(_))
  }
  def websocketHandler(target: ActorRef): ActorRef = {
    system.actorOf(ConnectionHandler(project, broadcaster, target))
  }

}
