package com.example.utility

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.SendProducer
import akka.projection.ProjectionBehavior
import akka.projection.ProjectionId
import akka.projection.slick.SlickProjection
import com.example.utility.TopicOps.AkkaProjectionTopicProducer
import com.lightbend.lagom.internal.api.UriUtils
import com.lightbend.lagom.scaladsl.api.Descriptor.TopicCall
import com.lightbend.lagom.scaladsl.api.ServiceInfo
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceSupport.ScalaMethodTopic
import com.lightbend.lagom.scaladsl.api.broker.kafka.KafkaProperties
import com.lightbend.lagom.scaladsl.server.LagomServer
import com.lightbend.lagom.scaladsl.server.LagomServiceBinding
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

@annotation.nowarn
class AkkaProjectionRegisterTopicProducers(
    lagomServer: LagomServer,
    info: ServiceInfo,
    serviceLocator: ServiceLocator,
    akkaProjectionProvider: AkkaProjectionProvider,
    implicit val actorSystem: ActorSystem,
    implicit val executionContext: ExecutionContext
) {

  private val log = LoggerFactory.getLogger(classOf[AkkaProjectionRegisterTopicProducers])

  val kafkaConfig: KafkaConfig = KafkaConfig(actorSystem.settings.config)

  val service: LagomServiceBinding[_] = lagomServer.serviceBinding
  for {
    tc <- service.descriptor.topics
    topicCall = tc.asInstanceOf[TopicCall[Any]]
  } {

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

    topicCall.topicHolder match {
      case holder: ScalaMethodTopic[_] =>
        val topicProducer = holder.method.invoke(service.service)
        val topicId       = topicCall.topicId

        val projectionId = s"topicProducer-${topicId.name}"

        topicProducer match {
          case akkaProjectionTopic: AkkaProjectionTopicProducer[Any, _] =>
            val keySerializer   = new StringSerializer
            val valueSerializer = new KafkaSerializer(topicCall.messageSerializer.serializerForRequest)

            val producerSettings =
              ProducerSettings(actorSystem, keySerializer, valueSerializer)
                .withBootstrapServers(bootstrapServers)
            val sendProducer = SendProducer(producerSettings)(actorSystem)

            val partitionKeyStrategy: Option[Any => String] = {
              topicCall.properties.get(KafkaProperties.partitionKeyStrategy).map { pks => message =>
                pks.computePartitionKey(message)
              }
            }

            ShardedDaemonProcess(actorSystem.toTyped)
              .init(
                name = projectionId,
                akkaProjectionTopic.tags.size,
                index =>
                  ProjectionBehavior {
                    val tag            = akkaProjectionTopic.tags(index)
                    val sourceProvider = akkaProjectionTopic.sourceProvider(index)(actorSystem.toTyped)
                    implicit val as    = actorSystem.toTyped
                    akkaProjectionProvider
                      .project(
                        projectionId = ProjectionId(projectionId, tag),
                        sourceProvider,
                        handler =
                          () => akkaProjectionTopic.projectionHandler(topicId.name, partitionKeyStrategy, sendProducer)
                      )
                  },
                ShardedDaemonProcessSettings(actorSystem.toTyped),
                Some(ProjectionBehavior.Stop)
              )
          case other =>
            log.warn {
              s"Unknown topic producer ${other.getClass.getName}. " +
                s"This will likely result in no events published to topic ${topicId.name} by service ${info.serviceName}."
            }
        }
      case other =>
        log.error {
          s"Cannot plug publisher source for topic ${topicCall.topicId}. " +
            s"Reason was that it was expected a topicHolder of type ${classOf[ScalaMethodTopic[_]]}, " +
            s"but ${other.getClass} was found instead."
        }
    }
  }
}
