package com.example.utility

import akka.Done
import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.kafka.scaladsl.SendProducer
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.Handler
import akka.projection.scaladsl.SourceProvider
import com.lightbend.lagom.scaladsl.api.broker.Subscriber
import com.lightbend.lagom.scaladsl.api.broker.Topic
import org.apache.kafka.clients.producer.ProducerRecord

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object TopicOps {

  case class AkkaProjectionTopic[Payload, Event](
      tags: Seq[String],
      eventHandler: EventEnvelope[Event] => Future[Either[NotUsed, Payload]]
  ) extends Topic[Payload] {
    final override def topicId: Topic.TopicId =
      throw new UnsupportedOperationException("Topic#topicId is not permitted in the service's topic implementation")

    final override def subscribe: Subscriber[Payload] =
      throw new UnsupportedOperationException("Topic#subscribe is not permitted in the service's topic implementation.")

    def sourceProvider(
        index: Int
    )(implicit actorSystem: ActorSystem[_]): SourceProvider[Offset, EventEnvelope[Event]] = {
      val tag = tags(index)
      EventSourcedProvider.eventsByTag[Event](
        system = actorSystem,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = tag
      )
    }

    def projectionHandler(
        topicId: String,
        partitionKeyStrategy: Option[Any => String],
        sendProducer: SendProducer[String, Payload]
    )(implicit ec: ExecutionContext): Handler[EventEnvelope[Event]] =
      (envelope: EventEnvelope[Event]) => {
        eventHandler(envelope).flatMap {
          case Left(_) =>
            Future.successful(Done)
          case Right(payload) =>
            val key = partitionKeyStrategy match {
              case Some(strategy) => strategy(envelope.event)
              case None           => null
            }
            val producerRecord = new ProducerRecord(topicId, key, payload)
            sendProducer.send(producerRecord).map(_ => Done)
        }
      }
  }

  object AkkaProjectionTopic {

    def create[Payload, Event](tags: Seq[String])(
        eventHandler: EventEnvelope[Event] => Future[Either[NotUsed, Payload]]
    ): AkkaProjectionTopic[Payload, Event] =
      AkkaProjectionTopic(tags, eventHandler)
  }

  implicit class TopicOps[Payload](topic: Topic[Payload]) {

    def consume() = ???
  }
}
