package com.example

import akka.NotUsed
import akka.actor.{ActorSystem, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
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
      |akka.persistence.snapshot-store.local.dir = "target/snapshots"
      akka.actor.warn-about-java-serializer-usage = "false"
    """
}

class BankAccountSpec extends TestKit(ActorSystem("BankAccountSpec", ConfigFactory.parseString(BankAccountSpec.Config)))
  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(5 seconds)

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
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState("active", 0, 0))

      val src: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 1L, Long.MaxValue)
      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeout.duration)
      evt shouldBe(BankAccountCreated(CustomerNumber, AccountNumber))
    }

    "accept pending DepositFunds command and transition to inTransaction state" in {
      val TransactionId = "transactionId1"
      val Amount = BigDecimal.valueOf(10)
      val cmd = PendingTransaction(DepositFunds(AccountNumber, Amount), TransactionId)
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState("inTransaction", 0, 10))

      val src: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 2L, Long.MaxValue)
      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeout.duration)
      evt shouldBe(FundsDepositedPending(AccountNumber, TransactionId, Amount))
    }

    "stash pending WithdrawFunds command while inTransaction state" in {
      val PreviousTransactionId = "transactionId1"
      val PreviousAmount = BigDecimal.valueOf(10)
      val TransactionId = "transactionId2"
      val Amount = BigDecimal.valueOf(5)
      val cmd = PendingTransaction(WithdrawFunds(AccountNumber, Amount), TransactionId)
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState("inTransaction", 0, 10))

      val src: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 2L, Long.MaxValue)
      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeout.duration)
      evt shouldBe(FundsDepositedPending(AccountNumber, PreviousTransactionId, PreviousAmount))
    }

    "ignore attempt to commit not existing transaction with pending deposit" in {
      val FakeTransactionId = "transactionId99"
      val FakeAmount = BigDecimal.valueOf(99)
      val cmd = CommitTransaction(DepositFunds(AccountNumber, FakeAmount), FakeTransactionId)
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState("inTransaction", 0, 10))
    }

    "accept commit of DepositFunds for first transaction and transition back to inTransaction state to handle " +
      "stashed Pending(WithdrawFunds)" in {
      val PreviousTransactionId = "transactionId1"
      val PreviousAmount = BigDecimal.valueOf(10)
      val TransactionId = "transactionId2"
      val Amount = BigDecimal.valueOf(5)
      val cmd = CommitTransaction(DepositFunds(AccountNumber, PreviousAmount), PreviousTransactionId)
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState("inTransaction", 10, 5))

      val src1: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 3L, Long.MaxValue)
      val evt1 = Await.result(src1.map(_.event).runWith(Sink.head), timeout.duration)
      evt1 shouldBe(FundsDeposited(AccountNumber, PreviousTransactionId, PreviousAmount))

      val src2: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 4L, Long.MaxValue)
      val evt2 = Await.result(src2.map(_.event).runWith(Sink.head), timeout.duration)
      evt2 shouldBe(FundsWithdrawnPending(AccountNumber, TransactionId, Amount))
    }

    "ignore attempt to commit not existing transaction with pending withdrawal" in {
      val FakeTransactionId = "transactionId99"
      val FakeAmount = BigDecimal.valueOf(99)
      val cmd = CommitTransaction(DepositFunds(AccountNumber, FakeAmount), FakeTransactionId)
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState("inTransaction", 10, 5))
    }

    "accept commit of WithdrawFunds and transition back to active state" in {
      val TransactionId = "transactionId2"
      val Amount = BigDecimal.valueOf(5)
      val cmd = CommitTransaction(WithdrawFunds(AccountNumber, Amount), TransactionId)
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState("active", 5, 0))

      val src: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 5L, Long.MaxValue)
      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeout.duration)
      evt shouldBe(FundsWithdrawn(AccountNumber, TransactionId, Amount))
    }

    "accept pending DepositFunds command and then a rollback" in {
      val TransactionId = "transactionId3"
      val Amount = BigDecimal.valueOf(11)
      val cmd1 = PendingTransaction(DepositFunds(AccountNumber, Amount), TransactionId)
      bankAccount ! cmd1

      val src1: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 6L, Long.MaxValue)
      val evt1 = Await.result(src1.map(_.event).runWith(Sink.head), timeout.duration)
      evt1 shouldBe(FundsDepositedPending(AccountNumber, TransactionId, Amount))

      val cmd2 = RollbackTransaction(DepositFunds(AccountNumber, Amount), TransactionId)
      bankAccount ! cmd2
      val src2: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 7L, Long.MaxValue)
      val evt2 = Await.result(src2.map(_.event).runWith(Sink.head), timeout.duration)
      evt2 shouldBe(FundsDepositedReversal(AccountNumber, TransactionId, Amount))

      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState("active", 5, 0))
    }

    "accept pending WithdrawFunds command and then a rollback" in {
      val TransactionId = "transactionId4"
      val Amount = BigDecimal.valueOf(1)
      val cmd1 = PendingTransaction(WithdrawFunds(AccountNumber, Amount), TransactionId)
      bankAccount ! cmd1

      val src1: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 8L, Long.MaxValue)
      val evt1 = Await.result(src1.map(_.event).runWith(Sink.head), timeout.duration)
      evt1 shouldBe(FundsWithdrawnPending(AccountNumber, TransactionId, Amount))

      val cmd2 = RollbackTransaction(WithdrawFunds(AccountNumber, Amount), TransactionId)
      bankAccount ! cmd2
      val src2: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId(AccountNumber, 9L, Long.MaxValue)
      val evt2 = Await.result(src2.map(_.event).runWith(Sink.head), timeout.duration)
      evt2 shouldBe(FundsWithdrawnReversal(AccountNumber, TransactionId, Amount))
    }

    "replay properly" in {
      val probe = TestProbe()
      probe.watch(bankAccount)
      bankAccount ! PoisonPill
      probe.expectMsgClass(classOf[Terminated])

      val bankAccount2 = system.actorOf(Props(classOf[BankAccount]), AccountNumber)

      def wait: Boolean = Await.result((bankAccount2 ? GetBankAccountState).mapTo[BankAccountState], timeout.duration) == BankAccountState("active", 5, 0)
      awaitCond(wait, timeout.duration, 100.milliseconds)
    }
  }
}
