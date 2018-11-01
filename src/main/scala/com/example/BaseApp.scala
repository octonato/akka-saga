package com.example

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.util.Timeout
import com.example.PersistentSagaActor.StartSaga

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test friendly abstract application.
  * @param system ActorSystem
  */
abstract class BaseApp(implicit val system: ActorSystem) {

  import BankAccountCommands._

  // Set up bank account cluster sharding
  val bankAccountEntityIdExtractor: ShardRegion.ExtractEntityId = {
    case cmd: BankAccountCommand => (cmd.accountNumber, cmd)
  }
  val bankAccountShardCount: Int = system.settings.config.getInt("akka-saga.bank-account.shard-count")
  val bankAccountShardIdExtractor: ShardRegion.ExtractShardId = {
    case cmd: BankAccountCommand => (cmd.accountNumber.hashCode % bankAccountShardCount).toString
    case ShardRegion.StartEntity(id) ⇒
      // StartEntity is used by remembering entities feature
      (id.hashCode % bankAccountShardCount).toString
  }
  val bankAccountRegion: ActorRef = ClusterSharding(system).start(
    typeName = "bank-account",
    entityProps = BankAccount.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = bankAccountEntityIdExtractor,
    extractShardId = bankAccountShardIdExtractor
  )

  // Set up saga cluster sharding
  val sagaEntityIdExtractor: ShardRegion.ExtractEntityId = {
    case cmd: StartSaga => (cmd.transactionId, cmd)
  }
  val sagaShardCount: Int = system.settings.config.getInt("akka-saga.bank-account.saga.shard-count")
  val sagaShardIdExtractor: ShardRegion.ExtractShardId = {
    case cmd: StartSaga => (cmd.transactionId.hashCode % sagaShardCount).toString
    case ShardRegion.StartEntity(id) ⇒
      (id.hashCode % sagaShardCount).toString
  }
  val bankAccountSagaRegion: ActorRef = ClusterSharding(system).start(
    typeName = "bank-account-saga",
    entityProps = PersistentSagaActor.props(bankAccountRegion),
    settings = ClusterShardingSettings(system),
    extractEntityId = sagaEntityIdExtractor,
    extractShardId = bankAccountShardIdExtractor
  )

  // Set up bank account query side projection.
  system.actorOf(
    ClusterSingletonManager.props(
      singletonProps = BankAccountsQuery.props,
      terminationMessage = BankAccountsQuery.Stop,
      settings = ClusterSingletonManagerSettings(system)),
    name = "bank-accounts-query")

  /**
    * Main function for running the app.
    */
  protected def run(): Unit = {
    createHttpServer()
    Await.ready(system.whenTerminated, Duration.Inf)
  }

  /**
    * Create Akka Http Server
    *
    * @return BankAccountHttpServer
    */
  private def createHttpServer(): BankAccountHttpServer = {
    implicit val timeout: Timeout = Timeout(5.seconds)

    val bankAccountsQuery = system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = "/user/bank-accounts-query",
        settings = ClusterSingletonProxySettings(system)),
      name = "bank-accounts-query-proxy")

    new BankAccountHttpServer(bankAccountRegion, bankAccountSagaRegion, bankAccountsQuery)(system, timeout)
  }
}
