package com.example.utility

import akka.util.ByteString
import com.lightbend.lagom.scaladsl.api.deser.MessageSerializer.NegotiatedDeserializer
import org.apache.kafka.common.serialization.Deserializer

class KafkaDeserializer[T](deserializer: NegotiatedDeserializer[T, ByteString]) extends Deserializer[T] {
  override def configure(configs: java.util.Map[String, _], isKey: Boolean): Unit = {
    () // ignore
  }

  override def deserialize(topic: String, data: Array[Byte]): T =
    deserializer.deserialize(ByteString(data))

  override def close(): Unit = () // nothing to do
}
