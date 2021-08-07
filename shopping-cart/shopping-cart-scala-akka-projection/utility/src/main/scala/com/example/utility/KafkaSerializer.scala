package com.example.utility

import akka.util.ByteString
import com.lightbend.lagom.scaladsl.api.deser.MessageSerializer.NegotiatedSerializer
import org.apache.kafka.common.serialization.Serializer

class KafkaSerializer[T](serializer: NegotiatedSerializer[T, ByteString]) extends Serializer[T] {
  override def configure(configs: java.util.Map[String, _], isKey: Boolean): Unit = {
    () // ignore
  }

  override def serialize(topic: String, data: T): Array[Byte] =
    serializer.serialize(data).toArray

  override def close(): Unit = () // nothing to do
}
