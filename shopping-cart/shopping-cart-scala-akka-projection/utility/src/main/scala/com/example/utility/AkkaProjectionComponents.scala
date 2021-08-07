package com.example.utility

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.projection.ProjectionId
import akka.projection.scaladsl.AtLeastOnceProjection
import akka.projection.scaladsl.Handler
import akka.projection.scaladsl.SourceProvider
import akka.projection.slick.SlickProjection
import com.lightbend.lagom.scaladsl.cluster.ClusterComponents
import play.api.libs.concurrent.AkkaTypedComponents
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

trait AkkaProjectionComponents {
  def akkaProjectionProvider: AkkaProjectionProvider
}

trait AkkaProjectionSlickComponents extends AkkaProjectionComponents with ClusterComponents with AkkaTypedComponents {
  lazy val databaseConfig: DatabaseConfig[PostgresProfile] = {
    val databaseConfig = DatabaseConfig.forConfig[PostgresProfile]("slick", actorSystem.settings.config)
    actorSystem.registerOnTermination(databaseConfig.db.close())
    databaseConfig
  }

  override lazy val akkaProjectionProvider: AkkaProjectionProvider =
    new AkkaProjectionSlickProvider(actorSystem, databaseConfig)
}

trait AkkaProjectionProvider {
  def project[Offset, Envelope](
      projectionId: ProjectionId,
      sourceProvider: SourceProvider[Offset, Envelope],
      handler: () => Handler[Envelope]
  ): AtLeastOnceProjection[Offset, Envelope]
}

//TODO provide only if slick persistence is used
class AkkaProjectionSlickProvider(actorSystem: ActorSystem, databaseConfig: DatabaseConfig[PostgresProfile])
    extends AkkaProjectionProvider {

  override def project[Offset, Envelope](
      projectionId: ProjectionId,
      sourceProvider: SourceProvider[Offset, Envelope],
      handler: () => Handler[Envelope]
  ): AtLeastOnceProjection[Offset, Envelope] = {
    implicit val ac = actorSystem.toTyped
    SlickProjection
      .atLeastOnceAsync(
        projectionId = projectionId,
        sourceProvider,
        databaseConfig,
        handler = handler
      )
  }
}

//TODO provide only if cassandra persistence is used
class AkkaProjectionCassandraProvider extends AkkaProjectionProvider {
  override def project[Offset, Envelope](
      projectionId: ProjectionId,
      sourceProvider: SourceProvider[Offset, Envelope],
      handler: () => Handler[Envelope]
  ): AtLeastOnceProjection[Offset, Envelope] =
    ???
}
