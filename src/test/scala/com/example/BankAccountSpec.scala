package com.example

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

object BankAccountSpec {

  val Config =
    """
      akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
      akka.persistence.journal.leveldb.dir = "target/shared"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.actor.warn-about-java-serializer-usage = "false"
    """
}

class BankAccountSpec extends TestKit(ActorSystem("BankAccountSpec", ConfigFactory.parseString(BankAccountSpec.Config)))
  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val timeoutDuration: Duration = 5.seconds

  "a BankAccount" should {

    import BankAccount._

    val CustomerNumber = "customerNumber"
    val AccountNumber = "accountNumber1"
    val bankAccount = system.actorOf(Props(classOf[BankAccount]), AccountNumber)

    implicit val mat = ActorMaterializer()(system)
    val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    "properly be created with CreateBankAccount command" in {
      val cmd = CreateBankAccount(CustomerNumber, AccountNumber)
      bankAccount ! cmd
      bankAccount ! GetState
      expectMsg(BankAccountState("active", 0, 0))
      val src: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 1L, Long.MaxValue)

      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeoutDuration)
      evt shouldBe(BankAccountCreated(CustomerNumber, AccountNumber))
    }

    "accept pending DepositFunds command and transition to inTransaction state" in {
      val TransactionId = "transactionId1"
      val Amount = BigDecimal.valueOf(10)
      val cmd = Pending(DepositFunds(AccountNumber, Amount), TransactionId)
      bankAccount ! cmd
      bankAccount ! GetState
      expectMsg(BankAccountState("inTransaction", 0, 10))
      val src: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 2L, Long.MaxValue)

      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeoutDuration)
      evt shouldBe(FundsDepositedPending(AccountNumber, TransactionId, Amount))
    }

    "stash pending WithdrawFunds command while inTransaction state" in {
      val PreviousTransactionId = "transactionId1"
      val PreviousAmount = BigDecimal.valueOf(10)
      val TransactionId = "transactionId2"
      val Amount = BigDecimal.valueOf(5)
      val cmd = Pending(WithdrawFunds(AccountNumber, Amount), TransactionId)
      bankAccount ! cmd
      bankAccount ! GetState
      expectMsg(BankAccountState("inTransaction", 0, 10))
      val src: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 2L, Long.MaxValue)

      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeoutDuration)
      evt shouldBe(FundsDepositedPending(AccountNumber, PreviousTransactionId, PreviousAmount))
    }

    "ignore attempt to commit not existing transaction" in {
      val FakeTransactionId = "transactionId99"
      val FakeAmount = BigDecimal.valueOf(99)
      val cmd = Commit(DepositFunds(AccountNumber, FakeAmount), FakeTransactionId)
      bankAccount ! cmd
      bankAccount ! GetState
      expectMsg(BankAccountState("inTransaction", 0, 10))
    }

    "accept commit of DepositFunds for first transaction command and transition back to inTransaction state to handle stashed Pending(WithdrawFunds)" in {
      val PreviousTransactionId = "transactionId1"
      val PreviousAmount = BigDecimal.valueOf(10)
      val TransactionId = "transactionId2"
      val Amount = BigDecimal.valueOf(5)
      val cmd = Commit(DepositFunds(AccountNumber, PreviousAmount), PreviousTransactionId)
      bankAccount ! cmd
      bankAccount ! GetState
      expectMsg(BankAccountState("inTransaction", 10, 5))

      val src: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 3L, Long.MaxValue)
      val evt1 = Await.result(src.map(_.event).runWith(Sink.head), timeoutDuration)
      evt1 shouldBe(FundsDeposited(AccountNumber, PreviousTransactionId, PreviousAmount))

      val src2: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 4L, Long.MaxValue)
      val evt2 = Await.result(src2.map(_.event).runWith(Sink.head), timeoutDuration)
      evt2 shouldBe(FundsWithdrawnPending(AccountNumber, TransactionId, Amount))
    }
  }
}
