package com.example.shoppingcart.impl

import akka.Done
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.Handler

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ShoppingCardReportHandler(repository: ShoppingCartReportRepository)(implicit ec: ExecutionContext)
    extends Handler[EventEnvelope[ShoppingCart.Event]] {

  override def process(envelope: EventEnvelope[ShoppingCart.Event]): Future[Done] = {
    envelope.event match {
      case ShoppingCart.ItemAdded(_, _) =>
        repository.createReport(ShoppingCart.entityId(envelope.persistenceId)).map(_ => Done)
      case ShoppingCart.ItemRemoved(_) =>
        Future.successful(Done)
      case ShoppingCart.ItemQuantityAdjusted(_, _) =>
        Future.successful(Done)
      case ShoppingCart.CartCheckedOut(eventTime) =>
        repository.addCheckoutTime(ShoppingCart.entityId(envelope.persistenceId), eventTime).map(_ => Done)
    }
  }
}
