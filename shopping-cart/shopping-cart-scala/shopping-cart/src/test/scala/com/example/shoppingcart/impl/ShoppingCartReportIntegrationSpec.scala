package com.example.shoppingcart.impl

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.util.Timeout
import com.dimafeng.testcontainers.CassandraContainer
import com.dimafeng.testcontainers.ElasticsearchContainer
import com.dimafeng.testcontainers.lifecycle.and
import com.dimafeng.testcontainers.scalatest.TestContainersForAll
import com.lightbend.lagom.scaladsl.api.AdditionalConfiguration
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.server.LagomApplication
import com.lightbend.lagom.scaladsl.server.LagomApplicationContext
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
import scala.concurrent.duration._

class ShoppingCartReportIntegrationSpec
    extends AnyWordSpec
    with TestContainersForAll
    with Matchers
    with ScalaFutures
    with OptionValues
    with Eventually {

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit val timeout: Timeout         = Timeout(5.seconds)
  implicit val patience: PatienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Seconds))

  override type Containers = CassandraContainer and ElasticsearchContainer

  override def startContainers(): Containers = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val (cassandra, elasticsearch) = Future(
      CassandraContainer
        .Def("cassandra:3.11.2")
        .start()
    ).zip(
        Future(
          ElasticsearchContainer
            .Def("docker.elastic.co/elasticsearch/elasticsearch:7.8.1")
            .start()
        )
      )
      .futureValue
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

    override def serviceLocator: ServiceLocator = NoServiceLocator
  }

  lazy val testCounter = new AtomicInteger(1)
  def withIntegration[T](
      block: (
          Containers,
          ServiceTest.TestServer[ShoppingCartApplication]
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

      def firstTimeBucket: String = {
        val firstBucketFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HH:mm")
        LocalDateTime.now(ZoneOffset.UTC).format(firstBucketFormat)
      }

      val configurationOverrides = Configuration.from(
        Map(
          "cassandra-query-journal.events-by-tag.eventual-consistency-delay" -> "0",
          "cassandra-query-journal.first-time-bucket"                        -> firstTimeBucket,
          "cassandra-journal.contact-points"                                 -> "localhost",
          "cassandra-journal.session-provider"                               -> "akka.persistence.cassandra.ConfigSessionProvider",
          "cassandra-journal.port"                                           -> cassandraPort,
          "cassandra-snapshot-store.contact-points"                          -> "localhost",
          "cassandra-snapshot-store.session-provider"                        -> "akka.persistence.cassandra.ConfigSessionProvider",
          "cassandra-snapshot-store.port"                                    -> cassandraPort,
          "lagom.persistence.read-side.cassandra.contact-points"             -> "localhost",
          "lagom.persistence.read-side.cassandra.session-provider"           -> "akka.persistence.cassandra.ConfigSessionProvider",
          "lagom.persistence.read-side.cassandra.port"                       -> cassandraPort,
          "elasticsearch.port"                                               -> elasticsearchPort,
          "elasticsearch.index"                                              -> s"index-$testNumber",
          "cassandra-journal.keyspace"                                       -> s"keyspace_$testNumber",
          "cassandra-snapshot-store.keyspace"                                -> s"keyspace_$testNumber",
          "lagom.persistence.read-side.cassandra.keyspace"                   -> s"keyspace_$testNumber"
        )
      )

      ServiceTest.withServer(ServiceTest.defaultSetup.withCluster()) { ctx =>
        new ShoppingCartApplication(ctx, configurationOverrides)
      } { server =>
        block(containers, server)
      }
    }
  }

  "The shopping cart report processor" should {

    "create a report on first event" in withIntegration {
      case (_, server) =>
        logger.info("Create report test start")
        lazy val reportRepository                  = server.application.reportRepository
        implicit lazy val exeCxt: ExecutionContext = server.actorSystem.dispatcher

        val cartId = UUID.randomUUID().toString

        withClue("Cart report is not expected to exist") {
          {
            eventually {
              reportRepository.findById(cartId).futureValue shouldBe None
              logger.info("Find report not existing pass")
            }
          }
        }

        logger.info("Command sent")
        server.application.clusterSharding
          .entityRefFor(ShoppingCart.typeKey, cartId)
          .ask(ShoppingCart.AddItem("test1", 1, _))
          .futureValue shouldBe a[ShoppingCart.Accepted]

        withClue("Cart report is created on first event") {
          eventually {
            val updatedReport = reportRepository.findById(cartId).futureValue.value
            updatedReport.creationDate should not be null
            updatedReport.checkoutDate shouldBe None
            logger.info(s"Create report test pass")
          }
        }
    }

    "produce a checked-out report on check-out event" in withIntegration {
      case (_, server) =>
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

        server.application.clusterSharding
          .entityRefFor(ShoppingCart.typeKey, cartId)
          .ask(ShoppingCart.AddItem("test1", 1, _))
          .futureValue shouldBe a[ShoppingCart.Accepted]

        withClue("Cart report is created on first event") {
          eventually {
            val updatedReport = reportRepository.findById(cartId).futureValue.value
            updatedReport.creationDate should not be null
            updatedReport.checkoutDate shouldBe None
          }
        }

        server.application.clusterSharding
          .entityRefFor(ShoppingCart.typeKey, cartId)
          .ask(ShoppingCart.Checkout)
          .futureValue shouldBe a[ShoppingCart.Accepted]

        withClue("Cart report is marked as checked-out") {
          eventually {
            val updatedReport = reportRepository.findById(cartId).futureValue.value
            updatedReport.creationDate should not be null
            updatedReport.checkoutDate should not be null
          }
        }
    }
  }
}
