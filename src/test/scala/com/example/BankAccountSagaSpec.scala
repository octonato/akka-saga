package com.example

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

object BankAccountSagaSpec {

  val Config =
    """
      |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
      |akka.persistence.journal.leveldb.dir = "target/leveldb"
      |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      |akka.persistence.snapshot-store.local.dir = "target/snapshots"
      |akka.actor.warn-about-java-serializer-usage = "false"
      |#log-dead-letters-during-shutdown = off
      |#log-dead-letters = off
      |akka-saga.bank-account.saga.retryAfter = 5 minutes
      |akka-saga.bank-account.saga.keepAliveAfterCompletion = 10 seconds
    """.stripMargin
}

class BankAccountSagaSpec extends TestKit(ActorSystem("BankAccountSagaSpec", ConfigFactory.parseString(BankAccountSagaSpec.Config)))
  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll {

  import BankAccountCommands._
  import BankAccountEvents._
  import PersistentSagaActor._
  import SagaStates._

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(5 seconds)

  "a BankAccountSaga" should {

    var TransactionId: String = "need to set this!"

    // Instantiate the bank accounts (sharding would do this in clustered mode).
    system.actorOf(Props(classOf[BankAccount]), "accountNumber1")
    system.actorOf(Props(classOf[BankAccount]), "accountNumber2")
    system.actorOf(Props(classOf[BankAccount]), "accountNumber3")

    // Cluster shard mock.
    val bankAccountRegion = system.actorOf(Props(new Actor() {
      override def receive: Receive = {
        case cmd @ CreateBankAccount(_, accountNumber) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
        case cmd @ StartTransaction(_, DepositFunds(accountNumber, _)) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
        case cmd @ StartTransaction(_, WithdrawFunds(accountNumber, _)) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
        case cmd @ CommitTransaction(_, accountNumber) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
        case cmd @ RollbackTransaction(_, accountNumber) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
      }
    }))

    // "Create" the bank accounts previously instantiated.
    bankAccountRegion ! CreateBankAccount("customer1", "accountNumber1")
    bankAccountRegion ! CreateBankAccount("customer1", "accountNumber2")
    bankAccountRegion ! CreateBankAccount("customer1", "accountNumber3")

    val sagaProbe: TestProbe = TestProbe()

    implicit val mat = ActorMaterializer()(system)
    val readJournal = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    // Set up subscription actor to received events from journal, to be easily queried for this spec.
    case class BankAccountTransactionConfirmed(envelope: TransactionalEventEnvelope, sort: String)
    case object GetEvents
    case class GetEventsResult(confirms: Seq[TransactionalEventEnvelope])
    case object Reset
    val eventReceiver: ActorRef = system.actorOf(Props(new Actor {
      private var confirms: Seq[BankAccountTransactionConfirmed] = Seq.empty
      override def receive: Receive = {
        case confirm: BankAccountTransactionConfirmed =>
          confirms = confirms :+ confirm
        case GetEvents =>
          sender() ! GetEventsResult(
            confirms.sortWith((a, b) =>
              a.envelope.event.getClass.getSimpleName + a.sort < b.envelope.event.getClass.getSimpleName + b.sort).map(_.envelope)
          )
        case Reset =>
          confirms = Seq.empty
      }
    }))
    // --

    "commit transaction when no exceptions" in {
      TransactionId = "transactionId1"

      val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(TransactionId, Offset.noOffset)
      source.map(_.event).runForeach {
        case envelope: TransactionalEventEnvelope => eventReceiver ! BankAccountTransactionConfirmed(envelope, envelope.event.entityId)
      }

      val saga = system.actorOf(PersistentSagaActor.props(bankAccountRegion), TransactionId)
      sagaProbe.send(saga, GetBankAccountSagaState)
      sagaProbe.expectMsg(SagaState(TransactionId, Uninitialized))

      val cmds = Seq(
        DepositFunds("accountNumber1", 10),
        DepositFunds("accountNumber2", 20),
        DepositFunds("accountNumber3", 30),
      )

      saga ! StartSaga(TransactionId, cmds)
      sagaProbe.send(saga, GetBankAccountSagaState)
      sagaProbe.expectMsg(SagaState(TransactionId, Pending, cmds))

      val probe: TestProbe = TestProbe()
      val ExpectedEvents = Seq(
        TransactionStarted(TransactionId, FundsDeposited("accountNumber1", 10)),
        TransactionCleared(TransactionId, FundsDeposited("accountNumber1", 10)),
        TransactionStarted(TransactionId, FundsDeposited("accountNumber2", 20)),
        TransactionCleared(TransactionId, FundsDeposited("accountNumber2", 20)),
        TransactionStarted(TransactionId, FundsDeposited("accountNumber3", 30)),
        TransactionCleared(TransactionId, FundsDeposited("accountNumber3", 30))
      )

      probe.awaitCond(Await.result((eventReceiver ? GetEvents)
        .mapTo[GetEventsResult], timeout.duration).confirms == ExpectedEvents,
        timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents not received.")

      sagaProbe.send(saga, GetBankAccountSagaState)
      val resp = sagaProbe.receiveOne(timeout.duration)
      resp match {
        case state: SagaState =>
          state.transactionId should be(TransactionId)
          state.currentState should be(Complete)
          state.commands should be(cmds)
          state.pendingConfirmed.sorted should be(Seq("accountNumber1", "accountNumber2", "accountNumber3"))
          state.commitConfirmed.sorted should be(Seq("accountNumber1", "accountNumber2", "accountNumber3"))
          state.rollbackConfirmed should be(Nil)
          state.exceptions should be(Nil)
      }
    }

    "rollback transaction when with exception" in {
      TransactionId = "transactionId2"
      eventReceiver ! Reset

      val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(TransactionId, Offset.noOffset)
      source.map(_.event).runForeach {
        case envelope: TransactionalEventEnvelope => eventReceiver ! BankAccountTransactionConfirmed(envelope, envelope.event.entityId)
      }

      val saga = system.actorOf(PersistentSagaActor.props(bankAccountRegion), TransactionId)

      val cmds = Seq(
        WithdrawFunds("accountNumber1", 11), // Account having balance of only 10
        WithdrawFunds("accountNumber2", 20),
        WithdrawFunds("accountNumber3", 30),
      )

      saga ! StartSaga(TransactionId, cmds)
      sagaProbe.send(saga, GetBankAccountSagaState)
      sagaProbe.expectMsg(SagaState(TransactionId, Pending, cmds))

      val probe: TestProbe = TestProbe()
      val Expected: GetEventsResult = GetEventsResult(
        Seq(
          TransactionStarted(TransactionId, FundsWithdrawn("accountNumber2", 20)),
          TransactionReversed(TransactionId, FundsWithdrawn("accountNumber2", 20)),
          TransactionStarted(TransactionId, FundsWithdrawn("accountNumber3", 30)),
          TransactionReversed(TransactionId, FundsWithdrawn("accountNumber3", 30)),
          TransactionException(TransactionId, InsufficientFunds("accountNumber1", 10, 11)),
        )
      )

      probe.awaitCond(Await.result((eventReceiver ? GetEvents)
        .mapTo[GetEventsResult], timeout.duration) == Expected,
        timeout.duration, 100.milliseconds, s"Expected events of $Expected not received.")

      sagaProbe.send(saga, GetBankAccountSagaState)
      val resp = sagaProbe.receiveOne(timeout.duration)
      resp match {
        case state: SagaState =>
          state.transactionId should be(TransactionId)
          state.currentState should be(Complete)
          state.commands should be(cmds)
          state.pendingConfirmed.sorted should be(Seq("accountNumber2", "accountNumber3"))
          state.commitConfirmed.sorted should be(Nil)
          state.rollbackConfirmed.sortWith(_ < _) should be(Seq("accountNumber2", "accountNumber3"))
          state.exceptions should be(Seq(TransactionException(TransactionId, InsufficientFunds("accountNumber1", 10, 11))))
      }
    }
  }
}
