package com.example

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.example.BankAccount.{DepositFunds, PendingTransaction}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

object BankAccountSagaSpec {

  val Config =
    """
      akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
      akka.persistence.journal.leveldb.dir = "target/leveldb"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.persistence.snapshot-store.local.dir = "target/snapshots"
      akka.actor.warn-about-java-serializer-usage = "false"
    """
}

// TODO: finish me
class BankAccountSagaSpec extends TestKit(ActorSystem("BankAccountSagaSpec", ConfigFactory.parseString(BankAccountSagaSpec.Config)))
  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(5 seconds)

  "a BankAccountSaga" should {

    import BankAccountSaga._
    import BankAccount._
    import BankAccountSagaStates._

    val TransactionId: String = "transactionId1"

    // Instantiate the bank accounts (sharding would do this in clustered mode).
    system.actorOf(Props(classOf[BankAccount]), "accountNumber1")
    system.actorOf(Props(classOf[BankAccount]), "accountNumber2")
    system.actorOf(Props(classOf[BankAccount]), "accountNumber3")

    // Cluster shard mock.
    val bankAccountRegion = system.actorOf(Props(new Actor() {
      override def receive: Receive = {
        case cmd @ CreateBankAccount(_, accountNumber) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
        case cmd @ PendingTransaction(DepositFunds(accountNumber, _, _), TransactionId) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
      }
    }))

    // "Create" the bank accounts previously instantiated.
    bankAccountRegion ! CreateBankAccount("customer1", "accountNumber1")
    bankAccountRegion ! CreateBankAccount("customer1", "accountNumber2")
    bankAccountRegion ! CreateBankAccount("customer1", "accountNumber3")

    val saga = system.actorOf(BankAccountSaga.props(bankAccountRegion), TransactionId)
    val sagaProbe: TestProbe = TestProbe()

    "properly be started with StartBankAccountSaga command" in {
      val cmds = Seq(
        DepositFunds("accountNumber1", 10),
        DepositFunds("accountNumber2", 20),
        DepositFunds("accountNumber3", 30),
      )

      sagaProbe.send(saga, GetBankAccountSagaState)
      sagaProbe.expectMsg(BankAccountSagaState(TransactionId, Uninitialized))

      saga ! StartBankAccountSaga(cmds, TransactionId)
      sagaProbe.send(saga, GetBankAccountSagaState)
      sagaProbe.expectMsg(BankAccountSagaState(TransactionId, Pending, cmds))
    }
  }
}
