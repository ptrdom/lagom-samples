package com.example.utility

import com.typesafe.config.Config

sealed trait KafkaConfig {

  /** The name of the Kafka server to look up out of the service locator. */
  def serviceName: Option[String]

  /** A comma separated list of Kafka brokers. Will be ignored if serviceName is defined. */
  def brokers: String
}

object KafkaConfig {
  def apply(conf: Config): KafkaConfig =
    new KafkaConfigImpl(conf.getConfig("lagom.broker.kafka"))

  private final class KafkaConfigImpl(conf: Config) extends KafkaConfig {
    override val brokers: String             = conf.getString("brokers")
    override val serviceName: Option[String] = Some(conf.getString("service-name")).filter(_.nonEmpty)
  }
}
