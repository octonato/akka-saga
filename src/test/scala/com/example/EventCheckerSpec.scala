package com.example

import akka.actor.{ActorSystem, Props}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.PersistenceQuery
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import com.example.EventChecker.EventResults
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

class EventCheckerSpec extends TestKit(ActorSystem("EventCheckerSpec",
  ConfigFactory.parseString(BankAccountSpec.Config))) with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(5 seconds)

  "the EventChecker" should {

    import BankAccount._

    val CustomerNumber = "customerNumber"
    val TransactionId = "transactionId1"
    val bankAccount1 = system.actorOf(Props(classOf[BankAccount]), "accountNumber1")
    val bankAccount2 = system.actorOf(Props(classOf[BankAccount]), "accountNumber2")
    val bankAccount3 = system.actorOf(Props(classOf[BankAccount]), "accountNumber3")
    val probe1 = TestProbe()
    val probe2 = TestProbe()
    val probe3 = TestProbe()

    val readJournal = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)


    "properly pick up latest events for all entities on initial pass" in {
      bankAccount1 ! CreateBankAccount(CustomerNumber, "accountNumber1")
      bankAccount2 ! CreateBankAccount(CustomerNumber, "accountNumber2")
      bankAccount3 ! CreateBankAccount(CustomerNumber, "accountNumber3")

      probe1.send(bankAccount1, GetBankAccountState)
      probe1.expectMsg(BankAccountState("active", 0, 0))
      probe2.send(bankAccount1, GetBankAccountState)
      probe2.expectMsg(BankAccountState("active", 0, 0))
      probe3.send(bankAccount1, GetBankAccountState)
      probe3.expectMsg(BankAccountState("active", 0, 0))

      val cmd1 = Pending(DepositFunds("accountNumber1", 10), TransactionId)
      val cmd2 = Pending(DepositFunds("accountNumber2", 10), TransactionId)
      val cmd3 = Pending(WithdrawFunds("accountNumber3", 10), TransactionId)

      bankAccount1 ! cmd1
      bankAccount2 ! cmd2
      bankAccount3 ! cmd3

      val eventChecker = new EventChecker()
      val res = eventChecker.checkEvents(TransactionId, readJournal, Seq("accountNumber1"), Nil, Nil, Nil, Nil)
      res should be(EventResults(
        Seq("accountNumber2", "accountNumber1"),
        Nil,
        Nil,
        Seq(InsufficientFunds("accountNumber3", 0, 10)))
      )
    }
  }
}
