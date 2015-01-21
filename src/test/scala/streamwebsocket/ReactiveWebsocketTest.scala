package streamwebsocket

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import org.scalatest.{FlatSpecLike, Matchers}
import streamwebsocket.WebSocketMessage._

import scala.concurrent.Future
import scala.concurrent.duration._
import streamwebsocket.ReactiveServer.{ResourceSubscription, SubscribeForResource}
import akka.io.IO
import spray.can.{websocket, Http}
import spray.can.server.UHttp
import streamwebsocket.SimpleServer.WebSocketServer
import spray.can.websocket.frame.{TextFrame, Frame}
import spray.http.{HttpHeaders, HttpMethods, HttpRequest}
import spray.can.websocket.Send


class ReactiveWebsocketTest extends TestKit(ActorSystem("Websockets"))
         with FlatSpecLike with Matchers{
   implicit val materializer = FlowMaterializer()
   implicit val exec = system.dispatcher
   implicit val timeout = Timeout(3.seconds)

   class Listen(probe:TestProbe) extends Actor {
      def receive = {
         case Register(publisher, subscriber) =>
            println("just got the register")
      //      Source(publisher).foreach(m => println("foreach" + m))
            Source(publisher).map { case TextFrame(text) => {
               println("server received")
               probe.ref ! s"server received: $text"
               TextFrame("server message")
            }}.runWith(Sink(subscriber))
      }
   }

   class Client(probe:TestProbe, val upgradeRequest: HttpRequest) extends websocket.WebSocketClientWorker {
      IO(UHttp) ! Http.Connect("localhost", 8080, false)

      def businessLogic: Receive = {
         case Send(frame) => connection ! frame
         case TextFrame(text) =>
            println("client received: "+text)
            probe.ref ! s"client received: "+text)

         case _: Http.ConnectionClosed =>
            context.stop(self)
         case x => connection ! x
      }

   }

   "The websocket" should "do" in {
      val probe = TestProbe()

      val listen = system.actorOf(Props(new Listen(probe)))
      val server = system.actorOf(WebSocketServer.props(listen), "websocket")
      IO(UHttp) ! Http.Bind(server, "localhost", 8080)

//      val server = system.actorOf(Props(classOf[ReactiveServer], 8080))
//
//      (server ? SubscribeForResource("/somepath"))
//        .onSuccess{ case ResourceSubscription(routeSource) =>
//         routeSource.foreach(connection => {
//
//            Source(ActorPublisher[String](connection))
//              .foreach(str => {
//               probe.ref ! s"server received: $str"
//               connection ! WebSocketSend("server message")
//            })
//         })
//      }


      val headers = List(
      HttpHeaders.Host("localhost", 8080),
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
         HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
         HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
         HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate"))

      val client = system.actorOf(Props(new Client(probe, HttpRequest(HttpMethods.GET, "/", headers))))
      client ! Send(TextFrame("client message"))
//      Source(ActorPublisher(client))
//        .foreach { any : String  =>
//         probe.ref ! s"client received: $any"
//      }
//
//      client ! WebSocketSend("client message")
//Thread.sleep(6000)
      probe.expectMsg("server received: client message")
     probe.expectMsg("client received: server message")

   }


   "The websocket" should "exchange basic messages between client and server" in {

      val probe = TestProbe()
      val server = system.actorOf(Props(classOf[ReactiveServer], 8080))

      (server ? SubscribeForResource("/somepath"))
        .onSuccess{ case ResourceSubscription(routeSource) =>
            routeSource.foreach(connection => {

               Source(ActorPublisher[String](connection))
                 .foreach(str => {
                  probe.ref ! s"server received: $str"
                  connection ! WebSocketSend("server message")
               })
            })
         }

      val client = system.actorOf(Props(classOf[WebSocketActorClient], "ws://localhost:8080/somepath"))

      Source(ActorPublisher(client))
         .foreach { any : String  =>
            probe.ref ! s"client received: $any"
         }

      client ! WebSocketSend("client message")

      probe.expectMsg("server received: client message")
      probe.expectMsg("client received: server message")

   }
   "The websocket" should "exchange basic messages between client and server3" in {

      val serverProbe = TestProbe()
      val clientProbe = TestProbe()
      val server = system.actorOf(Props(classOf[ReactiveServer], 8080))

      (server ? SubscribeForResource("/somepath"))
        .onSuccess{ case ResourceSubscription(routeSource) =>
         routeSource.foreach(connection => {

            (connection ? SubscribeOpen)
              .mapTo[Future[ServerOpen]]
              .map(fut=> fut.onSuccess{ case open =>
               serverProbe.ref ! "open" })
            (connection ? SubscribeClose)
              .mapTo[Future[Close]]
              .map(fut=> fut.onSuccess{ case close =>
               serverProbe.ref ! "close" })

            Source(ActorPublisher[String](connection))
              .foreach(str => {
                  connection ! WebSocketSend("server message")
                  serverProbe.ref ! s"server received: $str"
            })
         })
      }

      val client = system.actorOf(Props(classOf[WebSocketActorClient], "ws://localhost:8080/somepath"))

      (client ? SubscribeOpen)
        .mapTo[Future[ClientOpen]]
        .map(fut=> fut.onSuccess{ case open => clientProbe.ref ! "open" })
      (client ? SubscribeClose)
        .mapTo[Future[Close]]
        .map(fut=> fut.onSuccess{ case close => clientProbe.ref ! "close" })

      Source(ActorPublisher(client))
        .foreach { any : String  =>
            clientProbe.ref ! s"client received: $any"
      }

      client ! WebSocketSend("client message")

      Thread.sleep(500.millis.toMillis)

      client ! WebSocketClose

      clientProbe.expectMsg("open")
      clientProbe.expectMsg("client received: server message")
      clientProbe.expectMsg("close")
      serverProbe.expectMsg("open")
      serverProbe.expectMsg("server received: client message")
      serverProbe.expectMsg("close")

   }

}