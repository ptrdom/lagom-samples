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

  new AkkaProjectionRegisterTopicProducers(
    lagomServer,
    serviceInfo,
    serviceLocator,
    akkaProjectionProvider,
    actorSystem,
    executionContext
  )
}
