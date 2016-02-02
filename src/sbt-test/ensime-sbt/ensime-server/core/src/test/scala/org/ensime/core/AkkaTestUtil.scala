// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.core

import akka.actor._
import akka.event.slf4j.SLF4JLogging
import akka.testkit._
import org.scalatest._
import scala.concurrent.duration.Duration
import org.scalatest.Assertions
import java.util.UUID
import scala.concurrent.{ Await, Future }
import scala.reflect.ClassTag

/**
 * This should be deprecated, prefer TestkitFixture
 */
abstract class AkkaFlatSpec
  extends HasSystem with TestKitBase
  with DefaultTimeout with ImplicitSender
  with FlatSpecLike with SetupAndTearDownSystem
  with Matchers with SLF4JLogging

// for when `system` must be defined ahead of a mixin
trait HasSystem {
  implicit val system = ActorSystem()
}

trait SetupAndTearDownSystem extends BeforeAndAfterAll {
  this: Suite with TestKitBase =>
  // can't mix in SetupAndTearDownSystem because fixture._ is different API
  override protected def beforeAll(): Unit = {
    super.beforeAll()
  }
  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }
}
