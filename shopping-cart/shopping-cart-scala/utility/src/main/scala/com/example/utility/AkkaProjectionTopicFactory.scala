package com.example.utility

import akka.actor.typed.ActorSystem
import com.example.utility.TopicOps.AkkaProjectionTopic
import com.lightbend.lagom.internal.scaladsl.api.broker.TopicFactory
import com.lightbend.lagom.scaladsl.api.Descriptor
import com.lightbend.lagom.scaladsl.api.ServiceInfo
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext

class AkkaProjectionTopicFactory(
    serviceInfo: ServiceInfo,
    serviceLocator: ServiceLocator,
    config: Config,
    implicit val actorSystem: ActorSystem[_],
    implicit val executionContext: ExecutionContext
) extends TopicFactory {

  private val kafkaConfig = KafkaConfig(config)

  override def create[Message](topicCall: Descriptor.TopicCall[Message]): Topic[Message] = {
    new AkkaProjectionTopic(
      kafkaConfig,
      topicCall,
      serviceInfo,
      serviceLocator,
      actorSystem,
      executionContext
    )
  }
}
