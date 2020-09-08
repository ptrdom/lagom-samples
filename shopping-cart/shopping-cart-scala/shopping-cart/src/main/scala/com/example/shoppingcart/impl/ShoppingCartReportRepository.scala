package com.example.shoppingcart.impl

import java.time.Instant

import akka.Done
import com.datastax.driver.core.BoundStatement
import com.example.shoppingcart.api.ShoppingCartReport
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.playjson._
import org.slf4j.LoggerFactory
import play.api.Configuration

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ShoppingCartReportRepository(
    client: ElasticClient,
    configuration: Configuration
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  val indexName: String = configuration.get[String]("elasticsearch.index")

  def prepareIndex(): Future[Done] = {
    client.execute(createIndex(indexName)).map(_ => Done)
  }

  def findById(cartId: String): Future[Option[ShoppingCartReport]] = {
    val query = get(indexName, cartId)
    client.execute(query).map(_.result.toOpt[ShoppingCartReport])
  }

  def createReport(entityId: String): Future[Seq[BoundStatement]] = {
    findById(entityId)
      .flatMap {
        case Some(_) =>
          Future.successful(Seq.empty)
        case None =>
          val report = ShoppingCartReport(
            entityId,
            Instant.now(),
            None
          )
          val query = indexInto(indexName).doc(report).withId(entityId)
          client
            .execute(query)
            .map { _ =>
              logger.info("Create report query done")
              Seq.empty
            }
      }
  }

  def addCheckoutTime(entityId: String, eventTime: Instant): Future[Seq[BoundStatement]] = {
    val query = updateById(indexName, entityId)
      .doc("checkoutDate" -> eventTime)
    client
      .execute(query)
      .map(_ => Seq.empty)
  }
}
