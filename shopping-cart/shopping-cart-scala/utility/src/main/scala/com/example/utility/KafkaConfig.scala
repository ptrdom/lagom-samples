package com.example.utility

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.kafka.CommitterSettings
import com.typesafe.config.Config

import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration

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

sealed trait ClientConfig {
  def offsetTimeout: FiniteDuration
  def minBackoff: FiniteDuration
  def maxBackoff: FiniteDuration
  def randomBackoffFactor: Double
}

object ClientConfig {
  class ClientConfigImpl(conf: Config) extends ClientConfig {
    val offsetTimeout       = conf.getDuration("offset-timeout", TimeUnit.MILLISECONDS).millis
    val minBackoff          = conf.getDuration("failure-exponential-backoff.min", TimeUnit.MILLISECONDS).millis
    val maxBackoff          = conf.getDuration("failure-exponential-backoff.max", TimeUnit.MILLISECONDS).millis
    val randomBackoffFactor = conf.getDouble("failure-exponential-backoff.random-factor")
  }
}

sealed trait ConsumerConfig extends ClientConfig {
  def offsetBuffer: Int
  def committerSettings: CommitterSettings
}

object ConsumerConfig {
  val configPath = "lagom.broker.kafka.client.consumer"

  def apply(system: ActorSystem): ConsumerConfig =
    // reads Alpakka defaults from Alpakka Kafka's reference.conf and overwrites with Lagom's values
    new ConsumerConfigImpl(system.settings.config.getConfig(configPath), CommitterSettings(system))

  private final class ConsumerConfigImpl(conf: Config, alpakkaCommitterSettings: CommitterSettings)
      extends ClientConfig.ClientConfigImpl(conf)
      with ConsumerConfig {
    override val offsetBuffer: Int = conf.getInt("offset-buffer")

    override val committerSettings: CommitterSettings = alpakkaCommitterSettings
      .withMaxBatch(conf.getInt("batching-size"))
      .withMaxInterval(conf.getDuration("batching-interval"))
      .withParallelism(conf.getInt("batching-parallelism"))
  }
}
