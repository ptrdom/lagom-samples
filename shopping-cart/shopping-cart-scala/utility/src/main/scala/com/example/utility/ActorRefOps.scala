package com.example.utility

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.ActorSystem
import play.api.libs.json.Format
import play.api.libs.json.JsPath
import play.api.libs.json.JsResult
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsonValidationError

object ActorRefOps {
  def format[T](actorSystem: ActorSystem[_]): Format[ActorRef[T]] =
    new Format[ActorRef[T]] {
      private val actorRefResolver = ActorRefResolver(actorSystem)

      override def reads(json: JsValue): JsResult[ActorRef[T]] = {
        json.validate[ActorRef[T]](
          JsPath
            .read[String]
            .collect(
              JsonValidationError("Expected JSON string of serialized ActorRef")
            )(actorRefResolver.resolveActorRef[T](_))
        )
      }

      override def writes(o: ActorRef[T]): JsValue = {
        JsString(actorRefResolver.toSerializationFormat[T](o))
      }
    }
}
