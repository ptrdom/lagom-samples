package com.example.utility

import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.server.LagomServer
import com.lightbend.lagom.scaladsl.server.LagomServerComponents

trait AkkaProjectionKafkaComponents
    extends LagomServerComponents
    with AkkaProjectionComponents
    with AkkaProjectionKafkaClientComponents {

  def lagomServer: LagomServer
  def serviceLocator: ServiceLocator

  override def topicPublisherName: Option[String] = super.topicPublisherName match {
    case Some(other) =>
      sys.error(
        s"Cannot provide the kafka topic factory as the default topic publisher since a default topic publisher has already been mixed into this cake: $other"
      )
    case None => Some("kafka")
  }

  new AkkaProjectionRegisterTopicProducers(
    lagomServer,
    serviceInfo,
    serviceLocator,
    akkaProjectionProvider,
    actorSystem,
    executionContext
  )
}
