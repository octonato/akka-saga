package com.example

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.example.BankAccount.{DepositFunds, PendingTransaction}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

class BankAccountSagaSpec extends TestKit(ActorSystem("BankAccountSagaSpec", ConfigFactory.parseString(BankAccountSpec.Config)))
  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(5 seconds)

  "a BankAccountSaga" should {

    import BankAccountSaga._

    val bankAccountRegion = TestProbe()
    val saga = system.actorOf(BankAccountSaga.props(), "transactionId1")

    "properly be started with StartBankAccountSaga command" in {
      val cmds = Seq(
        DepositFunds("accountNumber1", 10),
        DepositFunds("accountNumber2", 20),
        DepositFunds("accountNumber3", 30))

      val cmd = StartBankAccountSaga(cmds, "transactionId1")
      saga ! cmd
      saga ! GetBankAccountSagaState

      expectMsg(BankAccountSagaState("pending", "transactionId1", cmds))
      bankAccountRegion.expectMsgAllOf(
        PendingTransaction(DepositFunds("accountNumber1", 10), "transactionId1"),
        PendingTransaction(DepositFunds("accountNumber2", 20), "transactionId1"),
        PendingTransaction(DepositFunds("accountNumber3", 30), "transactionId1"))
    }

    "accept first 2 commits" in {

      val cmds = Seq(
        DepositFunds("accountNumber1", 10),
        DepositFunds("accountNumber2", 20),
        DepositFunds("accountNumber3", 30))

      val cmd = StartBankAccountSaga(cmds, "transactionId1")
      saga ! cmd
      saga ! GetBankAccountSagaState

      expectMsg(BankAccountSagaState("pending", "transactionId1", cmds))
      bankAccountRegion.expectMsgAllOf(
        PendingTransaction(DepositFunds("accountNumber1", 10), "transactionId1"),
        PendingTransaction(DepositFunds("accountNumber2", 20), "transactionId1"),
        PendingTransaction(DepositFunds("accountNumber3", 30), "transactionId1"))
    }
  }

}
