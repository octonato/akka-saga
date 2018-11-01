package com.example

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout

import scala.concurrent.ExecutionContext

/**
  * A runtime composition of an Http server.
  */
class BankAccountHttpServer(
  override val bankAccountRegion: ActorRef,
  override val bankAccountSagaRegion: ActorRef,
  override val bankAccountsQuery: ActorRef
)(override implicit val system: ActorSystem, override val timeout: Timeout) extends BankAccountRoutes {

  implicit val dispatcher: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  Http().bindAndHandle(route, "0.0.0.0", 8080)
}
