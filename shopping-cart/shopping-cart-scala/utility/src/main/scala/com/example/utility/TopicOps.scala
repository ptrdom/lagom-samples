package com.example.utility

import akka.Done
import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.kafka.ConsumerSettings
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Committer
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.SendProducer
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.Handler
import akka.projection.scaladsl.SourceProvider
import com.lightbend.lagom.internal.api.UriUtils
import com.lightbend.lagom.scaladsl.api.Descriptor.TopicCall
import com.lightbend.lagom.scaladsl.api.ServiceInfo
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.broker.Subscriber
import com.lightbend.lagom.scaladsl.api.broker.Topic
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

@annotation.nowarn
object TopicOps {

  class AkkaProjectionTopicProducer[Payload, Event](
      val tags: Seq[String],
      val eventHandler: EventEnvelope[Event] => Future[Either[NotUsed, Payload]] //TODO convert Either into None
  ) extends Topic[Payload] {
    final override def topicId: Topic.TopicId =
      throw new UnsupportedOperationException

    final override def subscribe: Subscriber[Payload] =
      throw new UnsupportedOperationException

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

  object AkkaProjectionTopicProducer {

    def create[Payload, Event](tags: Seq[String])(
        eventHandler: EventEnvelope[Event] => Future[Either[NotUsed, Payload]]
    ): AkkaProjectionTopicProducer[Payload, Event] =
      new AkkaProjectionTopicProducer(tags, eventHandler)
  }

  class AkkaProjectionTopic[Payload](
      val kafkaConfig: KafkaConfig,
      val topicCall: TopicCall[Payload],
      val info: ServiceInfo,
      val serviceLocator: ServiceLocator,
      implicit val actorSystem: ActorSystem[_],
      implicit val executionContext: ExecutionContext
  ) extends Topic[Payload] {
    final override def topicId: Topic.TopicId =
      throw new UnsupportedOperationException

    final override def subscribe: Subscriber[Payload] =
      throw new UnsupportedOperationException
  }

  //TODO add consumer with metadata
  class AkkaProjectionTopicConsumer[Payload](
      kafkaConfig: KafkaConfig,
      topicCall: TopicCall[Payload],
      groupId: String,
      info: ServiceInfo,
      serviceLocator: ServiceLocator,
      implicit val actorSystem: ActorSystem[_],
      implicit val executionContext: ExecutionContext
  ) {

    private val log = LoggerFactory.getLogger(classOf[AkkaProjectionTopicConsumer[Payload]])

    private def consumerConfig: ConsumerConfig = ConsumerConfig(actorSystem.classicSystem)

    def withGroupId(groupId: String): AkkaProjectionTopicConsumer[Payload] = new AkkaProjectionTopicConsumer[Payload](
      kafkaConfig,
      topicCall,
      groupId,
      info,
      serviceLocator,
      actorSystem,
      executionContext
    )

    def handle(
        handler: Payload => Future[Done]
    ): DrainingControl[Done] = {
      val kafkaConfig: KafkaConfig = KafkaConfig(actorSystem.settings.config)

      val bootstrapServers = {
        def strToOpt(str: String) =
          Option(str).filter(_.trim.nonEmpty)

        val serviceLookupFuture: Future[Option[String]] =
          kafkaConfig.serviceName match {
            case Some(name) =>
              serviceLocator
                .locateAll(name)
                .map { uris =>
                  strToOpt(UriUtils.hostAndPorts(uris))
                }

            case None =>
              Future.successful(strToOpt(kafkaConfig.brokers))
          }

        val brokerList: Future[String] = serviceLookupFuture.map {
          case Some(brokers) => brokers
          case None =>
            kafkaConfig.serviceName match {
              case Some(serviceName) =>
                val msg = s"Unable to locate Kafka service named [$serviceName]. Retrying..."
                log.error(msg)
                throw new IllegalArgumentException(msg)
              case None =>
                val msg = "Unable to locate Kafka brokers URIs. Retrying..."
                log.error(msg)
                throw new RuntimeException(msg)
            }
        }

        //TODO make async
        Await.result(brokerList, 10.seconds)
      }

      val keyDeserializer = new StringDeserializer

      val valueDeserializer = {
        val messageSerializer = topicCall.messageSerializer
        val protocol          = messageSerializer.serializerForRequest.protocol
        val deserializer      = messageSerializer.deserializer(protocol)
        new KafkaDeserializer(deserializer)
      }

      val consumerSettings =
        ConsumerSettings(actorSystem, keyDeserializer, valueDeserializer)
          .withBootstrapServers(bootstrapServers)
          .withGroupId(groupId)

      Consumer
        .committableSource(consumerSettings, Subscriptions.topics(topicCall.topicId.name))
        .mapAsync(1) { msg =>
          handler(msg.record.value).map(_ => msg.committableOffset)
        }
        .toMat(Committer.sink(consumerConfig.committerSettings))(DrainingControl.apply)
        .run()
    }
  }

  implicit class TopicOps[Payload](topic: Topic[Payload]) {

    def consume: AkkaProjectionTopicConsumer[Payload] = topic match {
      case topic: AkkaProjectionTopic[Payload] =>
        new AkkaProjectionTopicConsumer(
          topic.kafkaConfig,
          topic.topicCall,
          topic.info.serviceName,
          topic.info,
          topic.serviceLocator,
          topic.actorSystem,
          topic.executionContext
        )
      case unhandled =>
        throw new RuntimeException(s"Cannot consume $unhandled, only AkkaProjectionTopic can be consumed.")
    }
  }
}
