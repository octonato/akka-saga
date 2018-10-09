package com.example

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}

/**
  * A runtime composition of an Http server.
  */
class BankAccountHttpServer(
  host: String,
  port: Int,
  override val bankAccountRegion: ActorRef,
  override val bankAccountSagaRegion: ActorRef,
  override val clusterListener: ActorRef
)(override implicit val system: ActorSystem, override val timeout: Timeout) extends BankAccountRoutes {

  implicit val dispatcher: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val binding: Future[Http.ServerBinding] = Http().bindAndHandle(route, host, port)
}
