package com.example.utility

import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.cluster.ClusterComponents
import com.lightbend.lagom.scaladsl.server.LagomServer
import com.lightbend.lagom.scaladsl.server.LagomServerComponents
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.reflect.ClassTag

trait AkkaProjectionTopics extends LagomServerComponents with ClusterComponents {

  def lagomServer: LagomServer
  def serviceLocator: ServiceLocator
  def databaseConfig: DatabaseConfig[JdbcProfile] //TODO move to trait to support different databases

  new AkkaProjectionRegisterTopicProducers(
    lagomServer,
    serviceInfo,
    serviceLocator,
    databaseConfig,
    actorSystem,
    executionContext
  )
}
