package com.example.shoppingcart.impl

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.projection.ProjectionBehavior
import akka.projection.ProjectionId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.slick.SlickProjection
import akka.serialization.SerializationSetup
import com.example.shoppingcart.api.ShoppingCartService
import com.lightbend.lagom.scaladsl.akka.discovery.AkkaDiscoveryComponents
import com.lightbend.lagom.scaladsl.cluster.ClusterComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.playjson.EmptyJsonSerializerRegistry
import com.lightbend.lagom.scaladsl.playjson.JsonSerializerRegistry
import com.lightbend.lagom.scaladsl.server._
import com.softwaremill.macwire._
import play.api.Configuration
import play.api.libs.ws.ahc.AhcWSComponents
import play.components.ConfigurationComponents
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.ExecutionContext

class ShoppingCartLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new ShoppingCartApplication(context) with AkkaDiscoveryComponents

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new ShoppingCartApplication(context) with LagomDevModeComponents

  override def describeService = Some(readDescriptor[ShoppingCartService])
}

trait ShoppingCartComponents
    extends LagomServerComponents
    with ClusterComponents
    with AhcWSComponents
    with ConfigurationComponents {

  implicit def executionContext: ExecutionContext

  // Bind the service that this server provides
  override lazy val lagomServer: LagomServer =
    serverFor[ShoppingCartService](wire[ShoppingCartServiceImpl])

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry: JsonSerializerRegistry = EmptyJsonSerializerRegistry

  override lazy val actorSystem: ActorSystem = play.api.libs.concurrent.ActorSystemProvider.start(
    environment.classLoader,
    Configuration(config),
    SerializationSetup { system =>
      Vector(
        JsonSerializerRegistry.serializerDetailsFor(system, new ShoppingCartSerializerRegistry(system.toTyped))
      )
    }
  )

  lazy val databaseConfig: DatabaseConfig[PostgresProfile] =
    DatabaseConfig.forConfig("db.default", actorSystem.settings.config)

  lazy val reportRepository: ShoppingCartReportRepository =
    wire[ShoppingCartReportRepository]

  lazy val shoppingCardReportHandler: ShoppingCardReportHandler = wire[ShoppingCardReportHandler]
  ShardedDaemonProcess(actorSystem.toTyped)
    .init(
      name = "shopping-cart-report",
      ShoppingCart.tags.size,
      index =>
        ProjectionBehavior {
          val tag = ShoppingCart.tags(index)
          val sourceProvider = EventSourcedProvider.eventsByTag[ShoppingCart.Event](
            system = actorSystem.toTyped,
            readJournalPluginId = JdbcReadJournal.Identifier,
            tag = tag
          )
          implicit val as = actorSystem.toTyped
          SlickProjection
            .atLeastOnceAsync(
              projectionId = ProjectionId("shopping-cart-report", tag),
              sourceProvider,
              databaseConfig,
              handler = () => shoppingCardReportHandler
            )
        },
      ShardedDaemonProcessSettings(actorSystem.toTyped),
      Some(ProjectionBehavior.Stop)
    )

  // Initialize the sharding for the ShoppingCart aggregate.
  // See https://doc.akka.io/docs/akka/2.6/typed/cluster-sharding.html
  clusterSharding.init(
    Entity(ShoppingCart.typeKey) { entityContext =>
      ShoppingCart(entityContext)
    }
  )
}

abstract class ShoppingCartApplication(context: LagomApplicationContext)
    extends LagomApplication(context)
    with ShoppingCartComponents {}
