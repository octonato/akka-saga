package com.example

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test friendly abstract application.
  * @param system ActorSystem
  */
abstract class BaseApp(implicit val system: ActorSystem) {

  val sagaTimeout: FiniteDuration = 1.hour

  val BankAccountSagaShardCount = 2
  val BankAccountShardCount = 2

  val bankAccountRegion: ActorRef = ClusterSharding(system).start(
    typeName = "bank-account",
    entityProps = Props[BankAccount],
    settings = ClusterShardingSettings(system),
    extractEntityId = BankAccount.extractEntityId,
    extractShardId = BankAccount.extractShardId
  )

  val bankAccountSagaRegion: ActorRef = ClusterSharding(system).start(
    typeName = "bank-account-saga",
    entityProps = BankAccountSaga.props(bankAccountRegion, sagaTimeout),
    settings = ClusterShardingSettings(system),
    extractEntityId = BankAccountSaga.extractEntityId,
    extractShardId = BankAccountSaga.extractShardId
  )

  val httpServerHost: String = "0.0.0.0"
  val httpServerPort: Int = 8080
  implicit val timeout: Timeout = Timeout(5.seconds)
  val clusterListener = system.actorOf(Props(new SimpleClusterListener))
  val httpServer: BankAccountHttpServer = createHttpServer()

  /**
    * Main function for running the app.
    */
  protected def run(): Unit = {
    Await.ready(system.whenTerminated, Duration.Inf)
  }

  /**
    * Create Akka Http Server
    *
    * @return BankAccountHttpServer
    */
  private def createHttpServer(): BankAccountHttpServer =
    new BankAccountHttpServer(
      httpServerHost,
      httpServerPort,
      bankAccountRegion,
      bankAccountSagaRegion,
      clusterListener
    )(system, timeout)
}
