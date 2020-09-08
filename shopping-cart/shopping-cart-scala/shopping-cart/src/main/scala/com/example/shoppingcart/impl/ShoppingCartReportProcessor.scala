package com.example.shoppingcart.impl

import akka.Done
import com.example.shoppingcart.impl.ShoppingCart._
import com.lightbend.lagom.scaladsl.persistence.AggregateEventTag
import com.lightbend.lagom.scaladsl.persistence.ReadSideProcessor
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraReadSide
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ShoppingCartReportProcessor(
    readSide: CassandraReadSide,
    repository: ShoppingCartReportRepository
)(
    implicit ec: ExecutionContext
) extends ReadSideProcessor[Event] {

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def buildHandler(): ReadSideProcessor.ReadSideHandler[Event] =
    readSide
      .builder[Event]("shopping-cart-report")
      .setGlobalPrepare(
        () =>
          repository.prepareIndex().map { _ =>
            logger.info("Index prepared")
            Done
          }
      )
      .setEventHandler[ItemAdded] { envelope =>
        logger.info("Create report event emitted")
        repository.createReport(envelope.entityId)
      }
      .setEventHandler[ItemRemoved] { envelope =>
        Future.successful(Seq.empty) // not used in report
      }
      .setEventHandler[ItemQuantityAdjusted] { envelope =>
        Future.successful(Seq.empty) // not used in report
      }
      .setEventHandler[CartCheckedOut] { envelope =>
        repository.addCheckoutTime(envelope.entityId, envelope.event.eventTime)
      }
      .build()

  override def aggregateTags: Set[AggregateEventTag[Event]] = Event.Tag.allTags
}
