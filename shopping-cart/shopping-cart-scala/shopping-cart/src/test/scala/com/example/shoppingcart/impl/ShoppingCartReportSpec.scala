package com.example.shoppingcart.impl

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.persistence.query.Sequence
import com.dimafeng.testcontainers.CassandraContainer
import com.dimafeng.testcontainers.ElasticsearchContainer
import com.dimafeng.testcontainers.lifecycle.and
import com.dimafeng.testcontainers.scalatest.TestContainersForAll
import com.lightbend.lagom.scaladsl.api.AdditionalConfiguration
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.server.LagomApplication
import com.lightbend.lagom.scaladsl.server.LagomApplicationContext
import com.lightbend.lagom.scaladsl.testkit.ReadSideTestDriver
import com.lightbend.lagom.scaladsl.testkit.ServiceTest
import com.lightbend.lagom.scaladsl.testkit.TestTopicComponents
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory
import play.api.Configuration

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ShoppingCartReportSpec
    extends AnyWordSpec
    with TestContainersForAll
    with Matchers
    with ScalaFutures
    with OptionValues
    with Eventually {

  import ShoppingCart._

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit val patience: PatienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Seconds))

  override type Containers = CassandraContainer and ElasticsearchContainer

  override def startContainers(): Containers = {
    val cassandra = CassandraContainer
      .Def("cassandra:3.11.2")
      .start()
    val elasticsearch = ElasticsearchContainer
      .Def("docker.elastic.co/elasticsearch/elasticsearch:7.8.1")
      .start()
    cassandra.and(elasticsearch)
  }

  class ShoppingCartApplication(
      context: LagomApplicationContext,
      configurationOverrides: Configuration
  ) extends LagomApplication(context)
      with ShoppingCartComponents
      with TestTopicComponents {

    override def additionalConfiguration: AdditionalConfiguration =
      super.additionalConfiguration ++ configurationOverrides.underlying

    override def serviceLocator: ServiceLocator    = NoServiceLocator
    override lazy val readSide: ReadSideTestDriver = new ReadSideTestDriver()(materializer, executionContext)
  }

  lazy val testCounter = new AtomicInteger()
  def withIntegration[T](
      block: (
          Containers,
          ServiceTest.TestServer[ShoppingCartApplication],
          (String, ShoppingCart.Event) => Future[Done]
      ) => T
  ): T = {
    val testNumber = testCounter.getAndIncrement()

    withContainers { containers =>
      val cassandra     = containers.head
      val cassandraPort = cassandra.container.getFirstMappedPort
      logger.info(s"Cassandra port : $cassandraPort")
      val elasticsearch     = containers.tail
      val elasticsearchPort = elasticsearch.container.getFirstMappedPort
      logger.info(s"Elasticsearch port : $elasticsearchPort")

      val configurationOverrides = Configuration.from(
        Map(
          "cassandra-journal.contact-points"                       -> "localhost",
          "cassandra-journal.session-provider"                     -> "akka.persistence.cassandra.ConfigSessionProvider",
          "cassandra-journal.port"                                 -> cassandraPort,
          "cassandra-snapshot-store.contact-points"                -> "localhost",
          "cassandra-snapshot-store.session-provider"              -> "akka.persistence.cassandra.ConfigSessionProvider",
          "cassandra-snapshot-store.port"                          -> cassandraPort,
          "lagom.persistence.read-side.cassandra.contact-points"   -> "localhost",
          "lagom.persistence.read-side.cassandra.session-provider" -> "akka.persistence.cassandra.ConfigSessionProvider",
          "lagom.persistence.read-side.cassandra.port"             -> cassandraPort,
          "elasticsearch.port"                                     -> elasticsearchPort,
          "elasticsearch.index"                                    -> s"index-$testNumber",
          "cassandra-journal.keyspace"                             -> s"keyspace_$testNumber",
          "cassandra-snapshot-store.keyspace"                      -> s"keyspace_$testNumber",
          "lagom.persistence.read-side.cassandra.keyspace"         -> s"keyspace_$testNumber"
        )
      )

      ServiceTest.withServer(ServiceTest.defaultSetup.withCluster()) { ctx =>
        new ShoppingCartApplication(ctx, configurationOverrides)
      } { server =>
        lazy val offset = new AtomicInteger()
        def feedEvent(cartId: String, event: ShoppingCart.Event): Future[Done] = {
          server.application.readSide.feed(cartId, event, Sequence(offset.getAndIncrement))
        }

        block(containers, server, feedEvent)
      }
    }
  }

  "The shopping cart report processor" should {

    "create a report on first event" in withIntegration {
      case (_, server, feedEvent) =>
        lazy val reportRepository                  = server.application.reportRepository
        implicit lazy val exeCxt: ExecutionContext = server.actorSystem.dispatcher

        val cartId = UUID.randomUUID().toString

        withClue("Cart report is not expected to exist") {
          {
            eventually {
              reportRepository.findById(cartId).futureValue shouldBe None
            }
          }
        }

        feedEvent(cartId, ItemAdded("test1", 1)).futureValue

        withClue("Cart report is created on first event") {
          eventually {
            val updatedReport = reportRepository.findById(cartId).futureValue.value
            updatedReport.creationDate should not be null
            updatedReport.checkoutDate shouldBe None
          }
        }
    }

    "NOT update a report on subsequent ItemUpdated events" in withIntegration {
      case (_, server, feedEvent) =>
        lazy val reportRepository                  = server.application.reportRepository
        implicit lazy val exeCxt: ExecutionContext = server.actorSystem.dispatcher

        val cartId = UUID.randomUUID().toString

        withClue("Cart report is not expected to exist") {
          eventually {
            reportRepository.findById(cartId).futureValue shouldBe None
          }
        }

        // Create a report to check against it later
        var reportCreatedDate: Instant = Instant.now()
        feedEvent(cartId, ItemAdded("test2", 1)).futureValue

        withClue("Cart report created on first event") {
          eventually {
            val createdReport = reportRepository.findById(cartId).futureValue.value
            reportCreatedDate = createdReport.creationDate
          }
        }

        // To ensure that events have a different instant
        SECONDS.sleep(2);

        feedEvent(cartId, ItemAdded("test2", 2)).futureValue
        feedEvent(cartId, ItemAdded("test2", 3)).futureValue

        withClue("Cart report's creationDate should not change") {
          eventually {
            val updatedReport = reportRepository.findById(cartId).futureValue.value
            updatedReport.creationDate shouldBe reportCreatedDate
            updatedReport.checkoutDate shouldBe None
          }
        }
    }

    "produce a checked-out report on check-out event" in withIntegration {
      case (_, server, feedEvent) =>
        lazy val reportRepository                  = server.application.reportRepository
        implicit lazy val exeCxt: ExecutionContext = server.actorSystem.dispatcher

        val cartId = UUID.randomUUID().toString

        withClue("Cart report is not expected to exist") {
          eventually {
            reportRepository.findById(cartId).futureValue shouldBe None
          }
        }

        // Create a report to check against it later
        var reportCreatedDate: Instant = Instant.now()
        feedEvent(cartId, ItemAdded("test2", 1)).futureValue

        withClue("Cart report created on first event") {
          eventually {
            val createdReport = reportRepository.findById(cartId).futureValue.value
            reportCreatedDate = createdReport.creationDate
          }
        }

        // To ensure that events have a different instant
        SECONDS.sleep(2);

        val checkedOutTime = reportCreatedDate.plusSeconds(30)
        feedEvent(cartId, ItemAdded("test3", 1)).futureValue
        feedEvent(cartId, CartCheckedOut(checkedOutTime)).futureValue

        withClue("Cart report is marked as checked-out") {
          eventually {
            val updatedReport = reportRepository.findById(cartId).futureValue.value
            updatedReport.creationDate shouldBe reportCreatedDate
            updatedReport.checkoutDate shouldBe Some(checkedOutTime)
          }
        }
    }

  }
}
