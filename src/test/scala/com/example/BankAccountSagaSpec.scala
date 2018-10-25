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
      |log-dead-letters-during-shutdown = off
      |log-dead-letters = off
    """.stripMargin
}

class BankAccountSagaSpec extends TestKit(ActorSystem("BankAccountSagaSpec", ConfigFactory.parseString(BankAccountSagaSpec.Config)))
  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll {

  import BankAccountCommands._
  import BankAccountEvents._
  import BankAccountSaga._
  import BankAccountSagaStates._

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
        case cmd @ PendingTransaction(DepositFunds(accountNumber, _, _), _) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
        case cmd @ PendingTransaction(WithdrawFunds(accountNumber, _, _), _) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
        case cmd @ CommitTransaction(DepositFunds(accountNumber, _, _), _) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
        case cmd @ CommitTransaction(WithdrawFunds(accountNumber, _, _), _) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
        case cmd @ RollbackTransaction(DepositFunds(accountNumber, _, _), _) =>
          system.actorSelection(s"/user/$accountNumber") ! cmd
        case cmd @ RollbackTransaction(WithdrawFunds(accountNumber, _, _), _) =>
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

    // Set up subscription actor to received events from journal.
    case class BankAccountTransactionConfirmed(evt: BankAccountEvent)
    case class BankAccountExceptionConfirmed(evt: BankAccountException)
    case object GetEvents
    case class GetEventsResult(confirms: Seq[BankAccountEvent], exceptions: Seq[BankAccountException])
    case object Reset
    val eventReceiver: ActorRef = system.actorOf(Props(new Actor {
      private var events: Seq[BankAccountEvent] = Seq.empty
      private var exceptions: Seq[BankAccountException] = Seq.empty
      override def receive: Receive = {
        case BankAccountTransactionConfirmed(evt) => events = events :+ evt
        case BankAccountExceptionConfirmed(ex) => exceptions = exceptions :+ ex
        case GetEvents =>
          sender() ! GetEventsResult(
            events.sortWith((a, b) =>
              a.getClass.getSimpleName + a.accountNumber < b.getClass.getSimpleName + b.accountNumber),
            exceptions.sortWith((a, b) =>
              a.getClass.getSimpleName + a.accountNumber < b.getClass.getSimpleName + b.accountNumber))
        case Reset =>
          events = Seq.empty
          exceptions = Seq.empty
      }
    }))
    // --

    "commit transaction when no exceptions" in {
      TransactionId = "transactionId1"

      val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(TransactionId, Offset.noOffset)
      source.map(_.event).runForeach {
        case evt: BankAccountTransaction => eventReceiver ! BankAccountTransactionConfirmed(evt)
        case ex: BankAccountException    => eventReceiver ! BankAccountExceptionConfirmed(ex)
      }

      val saga = system.actorOf(BankAccountSaga.props(bankAccountRegion), TransactionId)
      sagaProbe.send(saga, GetBankAccountSagaState)
      sagaProbe.expectMsg(BankAccountSagaState(TransactionId, Uninitialized))

      val cmds = Seq(
        DepositFunds("accountNumber1", 10),
        DepositFunds("accountNumber2", 20),
        DepositFunds("accountNumber3", 30),
      )

      saga ! StartBankAccountSaga(cmds, TransactionId)
      sagaProbe.send(saga, GetBankAccountSagaState)
      sagaProbe.expectMsg(BankAccountSagaState(TransactionId, Pending, cmds))

      val probe: TestProbe = TestProbe()
      val ExpectedEvents = Seq(
        FundsDepositedPending("accountNumber1", TransactionId, 10),
        FundsDepositedPending("accountNumber2", TransactionId, 20),
        FundsDepositedPending("accountNumber3", TransactionId, 30),
        FundsDeposited("accountNumber1", TransactionId, 10),
        FundsDeposited("accountNumber2", TransactionId, 20),
        FundsDeposited("accountNumber3", TransactionId, 30)
      )

      probe.awaitCond(Await.result((eventReceiver ? GetEvents)
        .mapTo[GetEventsResult], timeout.duration).confirms == ExpectedEvents,
        timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents not received.")

      sagaProbe.send(saga, GetBankAccountSagaState)
      val resp = sagaProbe.receiveOne(timeout.duration)
      resp match {
        case state: BankAccountSagaState =>
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
        case evt: BankAccountTransaction => eventReceiver ! BankAccountTransactionConfirmed(evt)
        case ex: BankAccountException    => eventReceiver ! BankAccountExceptionConfirmed(ex)
      }

      val saga = system.actorOf(BankAccountSaga.props(bankAccountRegion), TransactionId)

      val cmds = Seq(
        WithdrawFunds("accountNumber1", 11), // Account having balance of only 10
        WithdrawFunds("accountNumber2", 20),
        WithdrawFunds("accountNumber3", 30),
      )

      saga ! StartBankAccountSaga(cmds, TransactionId)
      sagaProbe.send(saga, GetBankAccountSagaState)
      sagaProbe.expectMsg(BankAccountSagaState(TransactionId, Pending, cmds))

      val probe: TestProbe = TestProbe()
      val Expected: GetEventsResult = GetEventsResult(
        Seq(
          FundsWithdrawnPending("accountNumber2", TransactionId, 20),
          FundsWithdrawnPending("accountNumber3", TransactionId, 30),
          FundsWithdrawnReversal("accountNumber2", TransactionId, 20),
          FundsWithdrawnReversal("accountNumber3", TransactionId, 30)
        ),
        Seq(InsufficientFunds("accountNumber1", TransactionId, 10, 11))
      )

      probe.awaitCond(Await.result((eventReceiver ? GetEvents)
        .mapTo[GetEventsResult], timeout.duration) == Expected,
        timeout.duration, 100.milliseconds, s"Expected events of $Expected not received.")

      sagaProbe.send(saga, GetBankAccountSagaState)
      val resp = sagaProbe.receiveOne(timeout.duration)
      resp match {
        case state: BankAccountSagaState =>
          state.transactionId should be(TransactionId)
          state.currentState should be(Complete)
          state.commands should be(cmds)
          state.pendingConfirmed.sorted should be(Seq("accountNumber2", "accountNumber3"))
          state.commitConfirmed.sorted should be(Nil)
          state.rollbackConfirmed.sorted should be(Seq("accountNumber2", "accountNumber3"))
          state.exceptions should be(Seq(InsufficientFunds("accountNumber1", TransactionId, 10, 11)))
      }
    }
  }
}
