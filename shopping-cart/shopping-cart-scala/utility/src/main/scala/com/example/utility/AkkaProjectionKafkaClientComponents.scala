package com.example.utility

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import com.lightbend.lagom.internal.scaladsl.api.broker.TopicFactory
import com.lightbend.lagom.internal.scaladsl.api.broker.TopicFactoryProvider
import com.lightbend.lagom.scaladsl.api.ServiceInfo
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext

trait AkkaProjectionKafkaClientComponents extends TopicFactoryProvider {
  def serviceInfo: ServiceInfo
  def actorSystem: ActorSystem
  def executionContext: ExecutionContext
  def serviceLocator: ServiceLocator
  def config: Config

  lazy val topicFactory =
    new AkkaProjectionTopicFactory(serviceInfo, serviceLocator, config, actorSystem.toTyped, executionContext)

  override def optionalTopicFactory: Option[TopicFactory] = Some(topicFactory)
}
