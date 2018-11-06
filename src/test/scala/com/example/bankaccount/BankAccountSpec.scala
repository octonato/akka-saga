package com.example.bankaccount

import akka.NotUsed
import akka.actor.{ActorSystem, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.example.PersistentSagaActor
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

object BankAccountSpec {

  val Cassandra = false

  private val Journal =
    if (Cassandra) {
      """
        |akka.persistence.journal.plugin = "cassandra-journal"
        |akka.persistence.snapshot-store.plugin = "cassandra-snapshot-store"
        |cassandra-query-journal.refresh-interval = 20ms
      """.stripMargin
    }
    else {
      """
        |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
        |akka.persistence.journal.leveldb.dir = "target/leveldb"
        |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
        |akka.persistence.snapshot-store.local.dir = "target/snapshots"
      """.stripMargin
    }

  val Config =
    """
      |akka.actor.warn-about-java-serializer-usage = "false"
    """.stripMargin + Journal
}

class BankAccountSpec extends TestKit(ActorSystem("BankAccountSpec", ConfigFactory.parseString(BankAccountSpec.Config)))
  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll {

  import BankAccountCommands._
  import BankAccountEvents._

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(20.seconds)

  "a BankAccount" should {

    import BankAccount._
    import BankAccountStates._
    import PersistentSagaActor._

    val CustomerNumber = "customerNumber"
    val AccountNumber = "accountNumber1"
    val bankAccount = system.actorOf(Props(classOf[BankAccount]), AccountNumber)

    implicit val mat = ActorMaterializer()(system)

    val readJournal =
      if (BankAccountSpec.Cassandra)
      PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    else
      PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    "properly be created with CreateBankAccount command" in {
      val cmd = CreateBankAccount(CustomerNumber, AccountNumber)
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState(Active, 0, 0))

      val src: Source[EventEnvelope, NotUsed] =
        readJournal.eventsByPersistenceId(AccountNumber, 1L, Long.MaxValue)
      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeout.duration)
      evt shouldBe(BankAccountCreated(CustomerNumber, AccountNumber))
    }

    "accept pending DepositFunds command and transition to inTransaction state" in {
      val TransactionId = "transactionId1"
      val Amount = BigDecimal.valueOf(10)
      val cmd = StartTransaction(TransactionId, DepositFunds(AccountNumber, Amount))
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState(InTransaction, 0, 10))

      val src: Source[EventEnvelope, NotUsed] =
        readJournal.eventsByPersistenceId(AccountNumber, 2L, Long.MaxValue)
      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeout.duration)
      evt shouldBe(TransactionStarted(TransactionId, AccountNumber, FundsDeposited(AccountNumber, Amount)))
    }

    "stash pending WithdrawFunds command while inTransaction state" in {
      val PreviousTransactionId = "transactionId1"
      val PreviousAmount = BigDecimal.valueOf(10)
      val TransactionId = "transactionId2"
      val Amount = BigDecimal.valueOf(5)
      val cmd = StartTransaction(TransactionId, WithdrawFunds(AccountNumber, Amount))
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState(InTransaction, 0, 10))

      val src: Source[EventEnvelope, NotUsed] =
        readJournal.eventsByPersistenceId(AccountNumber, 2L, Long.MaxValue)
      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeout.duration)
      evt shouldBe(TransactionStarted(PreviousTransactionId, AccountNumber, FundsDeposited(AccountNumber, PreviousAmount)))
    }

    "accept commit of DepositFunds for first transaction and transition back to inTransaction state to handle " +
      "stashed Pending(WithdrawFunds)" in {
      val PreviousTransactionId = "transactionId1"
      val PreviousAmount = BigDecimal.valueOf(10)
      val TransactionId = "transactionId2"
      val Amount = BigDecimal.valueOf(5)
      val cmd = CommitTransaction(PreviousTransactionId, AccountNumber)
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState(InTransaction, 10, 5))

      val src1: Source[EventEnvelope, NotUsed] =
        readJournal.eventsByPersistenceId(AccountNumber, 3L, Long.MaxValue)
      val evt1 = Await.result(src1.map(_.event).runWith(Sink.head), timeout.duration)
      evt1 shouldBe(TransactionCleared(PreviousTransactionId, AccountNumber, FundsDeposited(AccountNumber, PreviousAmount)))

      val src2: Source[EventEnvelope, NotUsed] =
        readJournal.eventsByPersistenceId(AccountNumber, 4L, Long.MaxValue)
      val evt2 = Await.result(src2.map(_.event).runWith(Sink.head), timeout.duration)
      evt2 shouldBe(TransactionStarted(TransactionId, AccountNumber, FundsWithdrawn(AccountNumber, Amount)))
    }

    "accept commit of WithdrawFunds and transition back to active state" in {
      val TransactionId = "transactionId2"
      val Amount = BigDecimal.valueOf(5)
      val cmd = CommitTransaction(TransactionId, AccountNumber)
      bankAccount ! cmd
      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState("active", 5, 0))

      val src: Source[EventEnvelope, NotUsed] =
        readJournal.eventsByPersistenceId(AccountNumber, 5L, Long.MaxValue)
      val evt = Await.result(src.map(_.event).runWith(Sink.head), timeout.duration)
      evt shouldBe(TransactionCleared(TransactionId, AccountNumber, FundsWithdrawn(AccountNumber, Amount)))
    }

    "accept pending DepositFunds command and then a rollback" in {
      val TransactionId = "transactionId3"
      val Amount = BigDecimal.valueOf(11)
      val cmd1 = StartTransaction(TransactionId, DepositFunds(AccountNumber, Amount))
      bankAccount ! cmd1

      val src1: Source[EventEnvelope, NotUsed] =
        readJournal.eventsByPersistenceId(AccountNumber, 6L, Long.MaxValue)
      val evt1 = Await.result(src1.map(_.event).runWith(Sink.head), timeout.duration)
      evt1 shouldBe(TransactionStarted(TransactionId, AccountNumber, FundsDeposited(AccountNumber, Amount)))

      val cmd2 = RollbackTransaction(TransactionId, AccountNumber)
      bankAccount ! cmd2
      val src2: Source[EventEnvelope, NotUsed] =
        readJournal.eventsByPersistenceId(AccountNumber, 7L, Long.MaxValue)
      val evt2 = Await.result(src2.map(_.event).runWith(Sink.head), timeout.duration)
      evt2 shouldBe(TransactionReversed(TransactionId, AccountNumber, FundsDeposited(AccountNumber, Amount)))

      bankAccount ! GetBankAccountState
      expectMsg(BankAccountState("active", 5, 0))
    }

    "accept pending WithdrawFunds command and then a rollback" in {
      val TransactionId = "transactionId4"
      val Amount = BigDecimal.valueOf(1)
      val cmd1 = StartTransaction(TransactionId, WithdrawFunds(AccountNumber, Amount))
      bankAccount ! cmd1

      val src1: Source[EventEnvelope, NotUsed] =
        readJournal.eventsByPersistenceId(AccountNumber, 8L, Long.MaxValue)
      val evt1 = Await.result(src1.map(_.event).runWith(Sink.head), timeout.duration)
      evt1 shouldBe(TransactionStarted(TransactionId, AccountNumber, FundsWithdrawn(AccountNumber, Amount)))

      val cmd2 = RollbackTransaction(TransactionId, AccountNumber)
      bankAccount ! cmd2
      val src2: Source[EventEnvelope, NotUsed] =
        readJournal.eventsByPersistenceId(AccountNumber, 9L, Long.MaxValue)
      val evt2 = Await.result(src2.map(_.event).runWith(Sink.head), timeout.duration)
      evt2 shouldBe(TransactionReversed(TransactionId, AccountNumber, FundsWithdrawn(AccountNumber, Amount)))
    }

    "replay properly" in {
      val probe = TestProbe()
      probe.watch(bankAccount)
      bankAccount ! PoisonPill
      probe.expectMsgClass(classOf[Terminated])

      val bankAccount2 = system.actorOf(Props(classOf[BankAccount]), AccountNumber)

      def wait: Boolean = Await.result((bankAccount2 ? GetBankAccountState).mapTo[BankAccountState],
        timeout.duration) == BankAccountState(Active, 5, 0)
      awaitCond(wait, timeout.duration, 100.milliseconds)
    }
  }
}
