package com.example.shoppingcart.impl

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit.SECONDS

import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.persistence.jdbc.testkit.scaladsl.SchemaUtils
import akka.projection.slick.SlickProjection
import akka.util.Timeout
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.server.LagomApplication
import com.lightbend.lagom.scaladsl.testkit.ServiceTest
import com.lightbend.lagom.scaladsl.testkit.TestTopicComponents
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.ExecutionContext

class ShoppingCartReportSpec
    extends AnyWordSpec
    with BeforeAndAfterAll
    with Matchers
    with ScalaFutures
    with OptionValues
    with IntegrationPatience
    with Eventually {

  import ShoppingCart._

  private val server = {
    ServiceTest.startServer(ServiceTest.defaultSetup.withJdbc()) { ctx =>
      new LagomApplication(ctx) with ShoppingCartComponents with TestTopicComponents {
        override def serviceLocator: ServiceLocator = NoServiceLocator

        {
          //TODO move to trait
          implicit val as = actorSystem.toTyped
          Await.result(SchemaUtils.createIfNotExists(), 10.seconds)
          Await.result(SlickProjection.createTablesIfNotExists(databaseConfig), 10.seconds)
          Await.result(databaseConfig.db.run(reportRepository.createTable()), 10.seconds)
        }

      }
    }
  }

  override def afterAll(): Unit = server.stop()

  private val clusterSharding                   = server.application.clusterSharding
  private val reportRepository                  = server.application.reportRepository
  private implicit val exeCxt: ExecutionContext = server.actorSystem.dispatcher

  implicit val timeout = Timeout(5.seconds)

  "The shopping cart report processor" should {

    "create a report on first event" in {
      val cartId = UUID.randomUUID().toString

      withClue("Cart report is not expected to exist") {
        reportRepository.findById(cartId).futureValue shouldBe None
      }

      entityRef(cartId).ask(AddItem("test1", 1, _)).futureValue

      withClue("Cart report is created on first event") {
        eventually {
          whenReady(reportRepository.findById(cartId)) { result =>
            val report = result.value
            report.creationDate should not be null
            report.checkoutDate shouldBe None
          }
        }
      }
    }

    "NOT update a report on subsequent ItemUpdated events" in {
      val cartId = UUID.randomUUID().toString

      withClue("Cart report is not expected to exist") {
        reportRepository.findById(cartId).futureValue shouldBe None
      }

      // Create a report to check against it later
      var reportCreatedDate: Instant = Instant.now()
      entityRef(cartId).ask(AddItem("test2", 1, _)).futureValue

      withClue("Cart report created on first event") {
        eventually {
          whenReady(reportRepository.findById(cartId)) { r =>
            reportCreatedDate = r.value.creationDate
          }
        }
      }

      // To ensure that events have a different instant
      SECONDS.sleep(2);

      entityRef(cartId).ask(AddItem("test2", 2, _)).futureValue
      entityRef(cartId).ask(AddItem("test2", 3, _)).futureValue

      withClue("Cart report's creationDate should not change") {
        eventually {
          whenReady(reportRepository.findById(cartId)) { result =>
            val report = result.value
            report.creationDate shouldBe reportCreatedDate
            report.checkoutDate shouldBe None
          }
        }
      }
    }

    "produce a checked-out report on check-out event" in {
      val cartId = UUID.randomUUID().toString

      withClue("Cart report is not expected to exist") {
        reportRepository.findById(cartId).futureValue shouldBe None
      }

      // Create a report to check against it later
      var reportCreatedDate: Instant = Instant.now()
      entityRef(cartId).ask(AddItem("test2", 1, _)).futureValue

      withClue("Cart report created on first event") {
        eventually {
          whenReady(reportRepository.findById(cartId)) { r =>
            reportCreatedDate = r.value.creationDate
          }
        }
      }

      // To ensure that events have a different instant
      SECONDS.sleep(2);

      val checkedOutTime = reportCreatedDate.plusSeconds(30)

      entityRef(cartId).ask(AddItem("test3", 1, _)).futureValue
      entityRef(cartId).ask(Checkout(checkedOutTime, _)).futureValue

      withClue("Cart report is marked as checked-out") {
        eventually {
          whenReady(reportRepository.findById(cartId)) { result =>
            val report = result.value
            report.creationDate shouldBe reportCreatedDate
            report.checkoutDate shouldBe Some(checkedOutTime)
          }
        }
      }
    }

  }

  private def entityRef(id: String): EntityRef[Command] = {
    clusterSharding.entityRefFor(ShoppingCart.typeKey, id)
  }
}
