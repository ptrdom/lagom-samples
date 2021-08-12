package com.example.inventory.impl

import com.example.inventory.api.InventoryService
import com.example.shoppingcart.api.ShoppingCartService
import com.lightbend.lagom.scaladsl.akka.discovery.AkkaDiscoveryComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.server._
import com.softwaremill.macwire._
import play.api.libs.ws.ahc.AhcWSComponents

class InventoryLoader extends LagomApplicationLoader {
  override def load(context: LagomApplicationContext): LagomApplication =
    new InventoryApplication(context) with AkkaDiscoveryComponents

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new InventoryApplication(context) with LagomDevModeComponents

  override def describeService = Some(readDescriptor[InventoryService])
}

abstract class InventoryApplication(context: LagomApplicationContext)
    extends LagomApplication(context)
    with AhcWSComponents {

  // Bind the service that this server provides
  override lazy val lagomServer = serverFor[InventoryService](wire[InventoryServiceImpl])

  // Bind the ShoppingcartService client
  lazy val shoppingCartService = serviceClient.implement[ShoppingCartService]
}
