package activator

import activator.typesafeproxy._
import akka.actor._
import akka.event.LoggingAdapter
import akka.pattern._
import console.ClientController.HandleRequest
import play.api.Play
import play.api.libs.json.Json._
import play.api.libs.json._
import activator.JsonHelper._
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import akka.util.Timeout

class AppWebSocketActor(val config: AppConfig,
  val typesafeComActor: ActorRef,
  val lookupTimeout: Timeout) extends WebSocketActor[JsValue] with ActorLogging {
  implicit val timeout = WebSocketActor.timeout

  override def onMessage(json: JsValue): Unit = {
    json match {
      case WebSocketActor.Ping(ping) => produce(WebSocketActor.Pong(ping.cookie))
      case UIActor.WebSocket.Inbound(req) =>
        context.actorSelection(req.actorPath).resolveOne()(lookupTimeout).onSuccess({ case a => a ! req })
      case TypesafeComProxyUIActor.Inbound(req) =>
        context.actorOf(TypesafeComProxyUIActor.props(req, typesafeComActor, self))
      case SbtRequest(req) => handleSbtPayload(req.json)
      case WriteTypesafeProperties(msg) =>
        AppWebSocketActor.bestEffortCreateTypesafeProperties(config.location, msg.subscriptionId)
      case _ => log.debug("unhandled message on web socket: {}", json)
    }
  }

  import sbt.protocol.Completion
  implicit val completionWrites = Json.writes[Completion]

  /**
   * Parses incoming sbt payload into an sbt command to execute.
   * Send result of execution asynchronously via web socket.
   *
   * The 'serialId' is what connects the request with the asynchronous response.
   * This way a client can filter out events that are a result of its invocation.
   *
   * Please note that the 'serialId' is not any id used in sbt-server.
   */
  def handleSbtPayload(json: JsValue) = {
    def sendResult(subType: String, serialId: Long, result: JsValue, partialCommand: Option[String] = None) = {
      var payload = Seq(
        "type" -> JsString("sbt"),
        "subType" -> JsString(subType),
        "serialId" -> JsNumber(serialId),
        "result" -> result)

      val pc = for {
        pc <- partialCommand
        r = Seq("partialCommand" -> JsString(pc))
      } yield r
      payload ++= pc.getOrElse(Seq.empty)

      context.parent ! NotifyWebSocket(JsObject(payload))
    }

    json.validate[SbtPayload](SbtPayload.sbtPayloadReads) match {
      case JsSuccess(payload, path) =>
        payload.requestType match {
          case AppWebSocketActor.requestExecution =>
            context.parent ? RequestExecution(payload.serialId, Some(payload.command)) map {
              case SbtClientResponse(serialId, executionId: Long, command) =>
                sendResult(AppWebSocketActor.requestExecution, serialId, JsNumber(executionId))
              case other =>
                log.debug(s"sbt could not execute command: $other")
            }
          case AppWebSocketActor.cancelExecution =>
            if (payload.executionId.isDefined) {
              context.parent ? CancelExecution(payload.serialId, payload.executionId.get) map {
                case SbtClientResponse(serialId, result: Boolean, _) =>
                  sendResult(AppWebSocketActor.cancelExecution, serialId, JsBoolean(result))
                case other =>
                  log.debug("sbt could not cancel command")
              }
            } else {
              log.debug("Cannot cancel sbt request without execution id.")
              None
            }
          case AppWebSocketActor.possibleAutoCompletions =>
            context.parent ? PossibleAutoCompletions(payload.serialId, Some(payload.command)) map {
              case SbtClientResponse(serialId, choicesAny: Vector[_], command) =>
                val choices = choicesAny.map(_.asInstanceOf[sbt.protocol.Completion])
                sendResult(AppWebSocketActor.possibleAutoCompletions, serialId, JsArray(choices.toList map { Json.toJson(_) }), command)
              case other => log.debug(s"sbt could not execute possible auto completions")
            }
          case other =>
            log.debug("Unknown sbt request type: $other")
            None
        }
      case e: JsError =>
        log.debug(s"Could not parse $json to valid SbtPayload. Error is: $e")
        None
    }
  }

  override def subReceive: Receive = {
    case NotifyWebSocket(json) =>
      log.debug("sending message on web socket: {}", json)
      produce(json)
    case UIActor.WebSocket.Outbound(msg) =>
      import UIActor.WebSocket._
      produce(Json.toJson(msg))
    case TypesafeComProxyUIActor.Outbound(msg) =>
      import TypesafeComProxyUIActor._
      produce(Json.toJson(msg))
  }
}

object AppWebSocketActor {
  val requestExecution = "RequestExecution"
  val cancelExecution = "CancelExecution"
  val possibleAutoCompletions = "PossibleAutoCompletions"

  def bestEffortCreateTypesafeProperties(location: java.io.File, subscriptionId: String): Unit = {
    val sid = subscriptionId.trim
    if (sid.nonEmpty) {
      val propertiesFile = new java.io.File(location, "project/typesafe.properties")
      try {
        // templates should not have the file already, but if they do, punt because
        // we don't know what's going on.
        if (location.exists && !propertiesFile.exists) {
          propertiesFile.getParentFile().mkdirs() // in case project/ doesn't exist
          val props = new java.util.Properties()
          props.setProperty("typesafe.subscription", sid)
          val stream = new java.io.FileOutputStream(propertiesFile)
          props.store(stream, "Lightbend Reactive Platform subscription ID, see https://www.lightbend.com/subscription")
          stream.close()
        } else {
          System.out.println(s"Not writing project/typesafe.properties to $location ${if (location.exists) s"($location does not exist)"} ${if (propertiesFile.exists) s"($propertiesFile already exists)"}")
        }
      } catch {
        case NonFatal(e) =>
          System.err.println(s"Failed to write $propertiesFile: ${e.getClass.getName}: ${e.getMessage}")
      }
    }
  }

}

case class SbtRequest(json: JsValue)

case class SbtPayload(serialId: Long, requestType: String, command: String, executionId: Option[Long])

case class WriteTypesafeProperties(subscriptionId: String)

object WriteTypesafeProperties {
  val tag = "WriteTypesafeProperties"

  implicit val writeTypesafePropertiesReads: Reads[WriteTypesafeProperties] =
    extractRequest[WriteTypesafeProperties](tag)((__ \ "subscriptionId").read[String].map(WriteTypesafeProperties.apply _))

  implicit val writeTypesafePropertiesWrites: Writes[WriteTypesafeProperties] =
    emitRequest(tag)(in => obj("subscriptionId" -> in.subscriptionId))

  def unapply(in: JsValue): Option[WriteTypesafeProperties] = Json.fromJson[WriteTypesafeProperties](in).asOpt
}

object SbtRequest {
  val tag = "sbt"

  implicit val sbtRequestReads: Reads[SbtRequest] =
    extractRequest[SbtRequest](tag)((__ \ "payload").read[JsValue].map(SbtRequest.apply _))

  implicit val sbtRequestWrites: Writes[SbtRequest] =
    emitRequest(tag)(in => obj("payload" -> in.json))

  def unapply(in: JsValue): Option[SbtRequest] = Json.fromJson[SbtRequest](in).asOpt
}

object SbtPayload {

  import play.api.libs.functional.syntax._

  implicit val sbtPayloadReads = (
    (__ \ "serialId").read[Long] and
    (__ \ "type").read[String] and
    (__ \ "command").read[String] and
    (__ \ "executionId").readNullable[Long])(SbtPayload.apply _)
}
