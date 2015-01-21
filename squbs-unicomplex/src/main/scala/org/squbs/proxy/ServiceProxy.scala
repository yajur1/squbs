/*
 * Licensed to Typesafe under one or more contributor license agreements.
 * See the AUTHORS file distributed with this work for
 * additional information regarding copyright ownership.
 * This file is licensed to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.squbs.proxy

import akka.actor._
import scala.concurrent.Future
import com.typesafe.config.Config
import spray.http._
import spray.http.HttpRequest
import spray.http.ChunkedRequestStart
import scala.Some
import spray.http.HttpResponse
import scala.util.Try


case class RequestContext(
                           request: HttpRequest,
                           isChunkRequest: Boolean = false,
                           response: ProxyResponse = ResponseNotReady,
                           attributes: Map[String, Any] = Map.empty //Store any other data
                           ) {
  def attribute[T](key: String): Option[T] = {
    attributes.get(key) match {
      case None => None
      case Some(null) => None
      case Some(value) => Some(value.asInstanceOf[T])
    }
  }
}

trait ProxyResponse

object ResponseNotReady extends ProxyResponse

case class ExceptionalResponse(
                                response: HttpResponse = ExceptionalResponse.defaultErrorResponse,
                                cause: Option[Throwable] = None,
                                original: Option[NormalProxyResponse] = None) extends ProxyResponse

object ExceptionalResponse {

  val defaultErrorResponse = HttpResponse(status = StatusCodes.InternalServerError, entity = "Service Error!")

  def apply(t: Throwable): ExceptionalResponse = apply(t, None)

  def apply(t: Throwable, originalResp: Option[NormalProxyResponse]): ExceptionalResponse = {
    val message = t.getMessage match {
      case null | "" => "Service Error!"
      case other => other
    }

    ExceptionalResponse(HttpResponse(status = StatusCodes.InternalServerError, entity = message), cause = Option(t), original = originalResp)
  }

}

case class AckInfo(rawAck: Any, receiver: ActorRef)

case class NormalProxyResponse(
                                source: ActorRef = ActorRef.noSender,
                                confirmAck: Option[Any] = None,
                                isChunkStart: Boolean = false,
                                data: HttpResponsePart) extends ProxyResponse {

  def buildRealResponse: Try[HttpMessagePartWrapper] =
    Try {
      this match {
        case NormalProxyResponse(_, None, false, r@(_: HttpResponse)) => r
        case NormalProxyResponse(_, None, true, r@(_: HttpResponse)) => ChunkedResponseStart(r)
        case NormalProxyResponse(_, None, _, r@(_: MessageChunk | _: ChunkedMessageEnd)) => r
        case NormalProxyResponse(_, Some(ack), true, r@(_: HttpResponse)) => Confirmed(ChunkedResponseStart(r), AckInfo(ack, source))
        case NormalProxyResponse(_, Some(ack), _, r@(_: MessageChunk | _: ChunkedMessageEnd)) => Confirmed(r, AckInfo(ack, source))
        case other => throw new IllegalArgumentException("Illegal ProxyResponse: " + this)
      }
    }

  def httpResponse: Option[HttpResponse] = {
    if (data.isInstanceOf[HttpResponse]) {
      Some(data.asInstanceOf[HttpResponse])
    } else {
      None
    }
  }

}

object NormalProxyResponse {

  def apply(resp: HttpResponse): NormalProxyResponse = new NormalProxyResponse(data = resp)

  def apply(chunkStart: ChunkedResponseStart): NormalProxyResponse = NormalProxyResponse(isChunkStart = true, data = chunkStart.response)

  def apply(chunkMsg: MessageChunk): NormalProxyResponse = new NormalProxyResponse(data = chunkMsg)

  def apply(chunkEnd: ChunkedMessageEnd): NormalProxyResponse = new NormalProxyResponse(data = chunkEnd)

  def apply(confirm: Confirmed, from: ActorRef): NormalProxyResponse = confirm match {
    case Confirmed(ChunkedResponseStart(resp), ack) => NormalProxyResponse(source = from, confirmAck = Some(ack), isChunkStart = true, data = resp)
    case Confirmed(r@(_: HttpResponsePart), ack) => NormalProxyResponse(source = from, confirmAck = Some(ack), isChunkStart = false, data = r)
    case other => throw new IllegalArgumentException("Unsupported confirmed message: " + confirm.messagePart)
  }

}


abstract class ServiceProxy(settings: Option[Config], hostActor: ActorRef) extends Actor with ActorLogging {

  import context.dispatcher

  def handleRequest(requestCtx: RequestContext, responder: ActorRef)(implicit actorContext: ActorContext): Unit

  def receive: Actor.Receive = {

    case req: HttpRequest =>
      val client = sender()
      Future {
        handleRequest(RequestContext(req), client)
      }


    case crs: ChunkedRequestStart =>
      val client = sender()
      Future {
        handleRequest(RequestContext(crs.request, true), client)
      }


    //let underlying actor handle it
    case other =>
      hostActor forward other

  }


}