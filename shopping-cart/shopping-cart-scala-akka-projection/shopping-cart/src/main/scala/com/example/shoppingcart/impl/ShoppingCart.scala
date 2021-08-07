package com.example.shoppingcart.impl

import java.time.Instant

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl._
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.Effect.reply
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.ReplyEffect
import akka.persistence.typed.scaladsl.RetentionCriteria
import com.example.utility.ActorRefOps
import com.lightbend.lagom.scaladsl.playjson.JsonSerializer
import com.lightbend.lagom.scaladsl.playjson.JsonSerializerRegistry
import play.api.libs.json.Format
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.reflect.ClassTag

object ShoppingCart {

  // SHOPPING CART COMMANDS
  sealed trait Command

  final case class AddItem(itemId: String, quantity: Int, replyTo: ActorRef[Confirmation]) extends Command

  final case class RemoveItem(itemId: String, replyTo: ActorRef[Confirmation]) extends Command

  final case class AdjustItemQuantity(itemId: String, quantity: Int, replyTo: ActorRef[Confirmation]) extends Command

  final case class Checkout(eventTime: Instant, replyTo: ActorRef[Confirmation]) extends Command

  final case class Get(replyTo: ActorRef[Summary]) extends Command

  // SHOPPING CART REPLIES
  final case class Summary(items: Map[String, Int], checkedOut: Boolean)

  sealed trait Confirmation

  final case class Accepted(summary: Summary) extends Confirmation

  final case class Rejected(reason: String) extends Confirmation

  implicit val summaryFormat: Format[Summary]               = Json.format
  implicit val confirmationAcceptedFormat: Format[Accepted] = Json.format
  implicit val confirmationRejectedFormat: Format[Rejected] = Json.format
  implicit val confirmationFormat: Format[Confirmation] = new Format[Confirmation] {
    override def reads(json: JsValue): JsResult[Confirmation] = {
      if ((json \ "reason").isDefined)
        Json.fromJson[Rejected](json)
      else
        Json.fromJson[Accepted](json)
    }

    override def writes(o: Confirmation): JsValue = {
      o match {
        case acc: Accepted => Json.toJson(acc)
        case rej: Rejected => Json.toJson(rej)
      }
    }
  }

  // SHOPPING CART EVENTS
  sealed trait Event

  final case class ItemAdded(itemId: String, quantity: Int) extends Event

  final case class ItemRemoved(itemId: String) extends Event

  final case class ItemQuantityAdjusted(itemId: String, newQuantity: Int) extends Event

  final case class CartCheckedOut(eventTime: Instant) extends Event

  // Events get stored and loaded from the database, hence a JSON format
  //  needs to be declared so that they can be serialized and deserialized.
  implicit val itemAddedFormat: Format[ItemAdded]                       = Json.format
  implicit val itemRemovedFormat: Format[ItemRemoved]                   = Json.format
  implicit val itemQuantityAdjustedFormat: Format[ItemQuantityAdjusted] = Json.format
  implicit val cartCheckedOutFormat: Format[CartCheckedOut]             = Json.format

  val empty: ShoppingCart = ShoppingCart(items = Map.empty)

  val typeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("ShoppingCart")

  val tags: Seq[String] = {
    val eventType = implicitly[ClassTag[Event]].runtimeClass.asInstanceOf[Class[Event]]
    Vector.tabulate(10)(i => s"${eventType.getName}$i")
  }

  // We can then access the entity behavior in our test tests, without the need to tag
  // or retain events.
  def apply(persistenceId: PersistenceId): EventSourcedBehavior[Command, Event, ShoppingCart] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, ShoppingCart](
        persistenceId = persistenceId,
        emptyState = ShoppingCart.empty,
        commandHandler = (cart, cmd) => cart.applyCommand(cmd),
        eventHandler = (cart, evt) => cart.applyEvent(evt)
      )
  }

  def apply(entityContext: EntityContext[Command]): Behavior[Command] =
    apply(PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId))
      .withTagger { _ =>
        val i = math.abs(entityContext.entityId.hashCode % tags.size)
        Set(tags(i))
      }
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))

  /**
   * The aggregate get snapshoted every configured number of events. This
   * means the state gets stored to the database, so that when the entity gets
   * loaded, you don't need to replay all the events, just the ones since the
   * snapshot. Hence, a JSON format needs to be declared so that it can be
   * serialized and deserialized when storing to and from the database.
   */
  implicit val shoppingCartFormat: Format[ShoppingCart] = Json.format

  def entityId(persistenceId: String) = {
    persistenceId.split(s"\\|").last
  }
}

final case class ShoppingCart(items: Map[String, Int], checkedOutTime: Option[Instant] = None) {

  import ShoppingCart._

  def isOpen: Boolean     = checkedOutTime.isEmpty
  def checkedOut: Boolean = !isOpen

  //The shopping cart behavior changes if it's checked out or not. The command handles are different for each case.
  def applyCommand(cmd: Command): ReplyEffect[Event, ShoppingCart] =
    if (isOpen) {
      cmd match {
        case AddItem(itemId, quantity, replyTo)            => onAddItem(itemId, quantity, replyTo)
        case RemoveItem(itemId, replyTo)                   => onRemoveItem(itemId, replyTo)
        case AdjustItemQuantity(itemId, quantity, replyTo) => onAdjustItemQuantity(itemId, quantity, replyTo)
        case Checkout(eventTime, replyTo)                  => onCheckout(eventTime, replyTo)
        case Get(replyTo)                                  => onGet(replyTo)
      }
    } else {
      cmd match {
        case Get(replyTo)                      => onGet(replyTo)
        case AddItem(_, _, replyTo)            => reply(replyTo)(Rejected("Cannot add an item to a checked-out cart"))
        case RemoveItem(_, replyTo)            => reply(replyTo)(Rejected("Cannot remove an item from a checked-out cart"))
        case AdjustItemQuantity(_, _, replyTo) => reply(replyTo)(Rejected("Cannot adjust item on a checked-out cart"))
        case Checkout(_, replyTo)              => reply(replyTo)(Rejected("Cannot checkout a checked-out cart"))
      }
    }

  private def onCheckout(eventTime: Instant, replyTo: ActorRef[Confirmation]): ReplyEffect[Event, ShoppingCart] = {
    if (items.isEmpty)
      Effect.reply(replyTo)(Rejected("Cannot checkout an empty shopping cart"))
    else
      Effect
        .persist(CartCheckedOut(eventTime))
        .thenReply(replyTo)(updatedCart => Accepted(toSummary(updatedCart)))
  }

  private def onAddItem(
      itemId: String,
      quantity: Int,
      replyTo: ActorRef[Confirmation]
  ): ReplyEffect[Event, ShoppingCart] = {
    if (items.contains(itemId))
      Effect.reply(replyTo)(Rejected(s"Item '$itemId' was already added to this shopping cart"))
    else if (quantity <= 0)
      Effect.reply(replyTo)(Rejected("Quantity must be greater than zero"))
    else
      Effect
        .persist(ItemAdded(itemId, quantity))
        .thenReply(replyTo)(updatedCart => Accepted(toSummary(updatedCart)))
  }

  private def onRemoveItem(itemId: String, replyTo: ActorRef[Confirmation]): ReplyEffect[Event, ShoppingCart] = {
    if (items.contains(itemId))
      Effect
        .persist(ItemRemoved(itemId))
        .thenReply(replyTo)(updatedCart => Accepted(toSummary(updatedCart)))
    else
      Effect.reply(replyTo)(Accepted(toSummary(this))) // removing an item is idempotent
  }

  private def onAdjustItemQuantity(
      itemId: String,
      quantity: Int,
      replyTo: ActorRef[Confirmation]
  ): ReplyEffect[Event, ShoppingCart] = {
    if (quantity <= 0)
      Effect.reply(replyTo)(Rejected("Quantity must be greater than zero"))
    else if (items.contains(itemId))
      Effect
        .persist(ItemQuantityAdjusted(itemId, quantity))
        .thenReply(replyTo)(updatedCart => Accepted(toSummary(updatedCart)))
    else
      Effect.reply(replyTo)(Rejected(s"Cannot adjust quantity for item '$itemId'. Item not present on cart"))
  }

  private def onGet(replyTo: ActorRef[Summary]): ReplyEffect[Event, ShoppingCart] = {
    reply(replyTo)(toSummary(this))
  }

  private def toSummary(shoppingCart: ShoppingCart): Summary =
    Summary(shoppingCart.items, shoppingCart.checkedOut)

  // we don't make a distinction of checked or open for the event handler
  // because a checked-out cart will never persist any new event
  def applyEvent(evt: Event): ShoppingCart =
    evt match {
      case ItemAdded(itemId, quantity)            => onItemAddedOrUpdated(itemId, quantity)
      case ItemRemoved(itemId)                    => onItemRemoved(itemId)
      case ItemQuantityAdjusted(itemId, quantity) => onItemAddedOrUpdated(itemId, quantity)
      case CartCheckedOut(checkedOutTime)         => onCartCheckedOut(checkedOutTime)
    }

  private def onItemRemoved(itemId: String): ShoppingCart = copy(items = items - itemId)

  private def onItemAddedOrUpdated(itemId: String, quantity: Int): ShoppingCart =
    copy(items = items + (itemId -> quantity))

  private def onCartCheckedOut(checkedOutTime: Instant): ShoppingCart = {
    copy(checkedOutTime = Option(checkedOutTime))
  }
}

/**
 * Akka serialization, used by both persistence and remoting, needs to have
 * serializers registered for every type serialized or deserialized. While it's
 * possible to use any serializer you want for Akka messages, out of the box
 * Lagom provides support for JSON, via this registry abstraction.
 *
 * The serializers are registered here, and then provided to Lagom in the
 * application loader.
 */
class ShoppingCartSerializerRegistry(actorSystem: ActorSystem[_]) extends JsonSerializerRegistry {

  import ShoppingCart._

  override def serializers: Seq[JsonSerializer[_]] = Seq(
    // commands
    JsonSerializer[AddItem] {
      implicit val actorRefFormat = ActorRefOps.format[Confirmation](actorSystem)
      Json.format[AddItem]
    },
    JsonSerializer[RemoveItem] {
      implicit val actorRefFormat = ActorRefOps.format[Confirmation](actorSystem)
      Json.format[RemoveItem]
    },
    JsonSerializer[AdjustItemQuantity] {
      implicit val actorRefFormat = ActorRefOps.format[Confirmation](actorSystem)
      Json.format[AdjustItemQuantity]
    },
    JsonSerializer[Checkout] {
      implicit val actorRefFormat = ActorRefOps.format[Confirmation](actorSystem)
      Json.format[Checkout]
    },
    JsonSerializer[Get] {
      implicit val actorRefFormat = ActorRefOps.format[Summary](actorSystem)
      Json.format[Get]
    },
    // state and events can use play-json, but commands should use jackson because of ActorRef[T] (see application.conf)
    JsonSerializer[ShoppingCart],
    JsonSerializer[ItemAdded],
    JsonSerializer[ItemRemoved],
    JsonSerializer[ItemQuantityAdjusted],
    JsonSerializer[CartCheckedOut],
    // the replies use play-json as well
    JsonSerializer[Summary],
    JsonSerializer[Confirmation],
    JsonSerializer[Accepted],
    JsonSerializer[Rejected],
  )
}
