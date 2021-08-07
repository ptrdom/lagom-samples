package com.example.shoppingcart.impl

import java.time.Instant

import akka.NotUsed
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.util.Timeout
import com.example.shoppingcart.api.Quantity
import com.example.shoppingcart.api.ShoppingCartItem
import com.example.shoppingcart.api.ShoppingCartReport
import com.example.shoppingcart.api.ShoppingCartService
import com.example.shoppingcart.api.ShoppingCartView
import com.example.shoppingcart.impl.ShoppingCart._
import com.example.utility.TopicOps.AkkaProjectionTopic
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.transport.BadRequest
import com.lightbend.lagom.scaladsl.api.transport.NotFound

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Implementation of the `ShoppingCartService`.
 */
class ShoppingCartServiceImpl(
    clusterSharding: ClusterSharding,
    reportRepository: ShoppingCartReportRepository
)(implicit ec: ExecutionContext)
    extends ShoppingCartService {

  /**
   * Looks up the shopping cart entity for the given ID.
   */
  private def entityRef(id: String): EntityRef[Command] =
    clusterSharding.entityRefFor(ShoppingCart.typeKey, id)

  implicit val timeout = Timeout(5.seconds)

  override def get(id: String): ServiceCall[NotUsed, ShoppingCartView] = ServiceCall { _ =>
    entityRef(id)
      .ask(reply => Get(reply))
      .map(cartSummary => convertShoppingCart(id, cartSummary))
  }

  override def addItem(id: String): ServiceCall[ShoppingCartItem, ShoppingCartView] = ServiceCall { update =>
    entityRef(id)
      .ask(reply => AddItem(update.itemId, update.quantity, reply))
      .map { confirmation =>
        confirmationToResult(id, confirmation)
      }
  }

  override def removeItem(id: String, itemId: String): ServiceCall[NotUsed, ShoppingCartView] = ServiceCall { update =>
    entityRef(id)
      .ask(reply => RemoveItem(itemId, reply))
      .map { confirmation =>
        confirmationToResult(id, confirmation)
      }
  }

  override def adjustItemQuantity(id: String, itemId: String): ServiceCall[Quantity, ShoppingCartView] = ServiceCall {
    update =>
      entityRef(id)
        .ask(reply => AdjustItemQuantity(itemId, update.quantity, reply))
        .map { confirmation =>
          confirmationToResult(id, confirmation)
        }
  }

  override def checkout(id: String): ServiceCall[NotUsed, ShoppingCartView] = ServiceCall { _ =>
    entityRef(id)
      .ask(replyTo => Checkout(Instant.now(), replyTo))
      .map { confirmation =>
        confirmationToResult(id, confirmation)
      }
  }

  private def confirmationToResult(id: String, confirmation: Confirmation): ShoppingCartView =
    confirmation match {
      case Accepted(cartSummary) => convertShoppingCart(id, cartSummary)
      case Rejected(reason)      => throw BadRequest(reason)
    }

  override def shoppingCartTopic: Topic[ShoppingCartView] =
    AkkaProjectionTopic.create[ShoppingCartView, ShoppingCart.Event](
      ShoppingCart.tags
    ) { envelope =>
      val id = ShoppingCart.entityId(envelope.persistenceId)
      envelope.event match {
        case CartCheckedOut(_) =>
          entityRef(id)
            .ask(reply => Get(reply))
            .map(cart => Right(convertShoppingCart(id, cart)))
        case _ =>
          Future.successful(Left(NotUsed))
      }
    }

  private def convertShoppingCart(id: String, cartSummary: Summary) = {
    ShoppingCartView(
      id,
      cartSummary.items.map((ShoppingCartItem.apply _).tupled).toSeq,
      cartSummary.checkedOut
    )
  }

  override def getReport(cartId: String): ServiceCall[NotUsed, ShoppingCartReport] = ServiceCall { _ =>
    reportRepository.findById(cartId).map {
      case Some(cart) => cart
      case None       => throw NotFound(s"Couldn't find a shopping cart report for $cartId")
    }
  }
}
