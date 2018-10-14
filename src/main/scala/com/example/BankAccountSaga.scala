package com.example

import akka.NotUsed
import akka.actor.{ActorLogging, ActorRef, Props}
import akka.persistence.{PersistentActor, SnapshotOffer}
import akka.cluster.sharding.ShardRegion
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source

/**
  * Transaction coordinator companion object.
  */
object BankAccountSaga {

  import BankAccount._

  // Commands
  case class StartBankAccountSaga(commands: Seq[BankAccountTransactionalCommand], transactionId: String)
  case object GetBankAccountSagaState

  // States
  object BankAccountSagaStates  {
    val Uninitialized = "uninitialized"
    val Pending = "pending"
    val Committing = "committing"
    val RollingBack = "rollingBack"
  }

  import BankAccountSagaStates._

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case cmd: StartBankAccountSaga => (cmd.transactionId, cmd)
  }

  val BankAccountSagaShardCount = 1 // Todo: get from config

  val extractShardId: ShardRegion.ExtractShardId = {
    case cmd: StartBankAccountSaga => (cmd.transactionId.hashCode % BankAccountSagaShardCount).toString
    case ShardRegion.StartEntity(id) ⇒
      // StartEntity is used by remembering entities feature
      (id.hashCode % BankAccountSagaShardCount).toString
  }

  case class BankAccountSagaState(
    transactionId: String,
    currentState: String = Uninitialized,
    commands: Seq[BankAccountTransactionalCommand] = Seq.empty,
    pendingConfirmed: Seq[AccountNumber] = Seq.empty,
    commitConfirmed: Seq[AccountNumber] = Seq.empty,
    rollbackConfirmed: Seq[AccountNumber] = Seq.empty,
    exceptions: Seq[BankAccountException] = Seq.empty,
    timedOut: Boolean = false)

  def props(bankAccountRegion: ActorRef): Props =
    Props(classOf[BankAccountSaga], bankAccountRegion: ActorRef)
}

/**
  * This is effectively a long lived saga that operates within an Akka cluster. Classic saga patterns will be followed,
  * such as retrying rollback over and over as well as retry of transactions over and over if necessary, before
  * rollback.
  *
  * Limitations--any particular bank account may only participate in a saga once--for now.
  */
class BankAccountSaga(bankAccountRegion: ActorRef) extends PersistentActor with ActorLogging {

  import BankAccountSaga._
  import BankAccount._
  import BankAccountSagaStates._

  override def persistenceId: String = self.path.name

  private var state: BankAccountSagaState = BankAccountSagaState(persistenceId)

  private case class BankAccountTransactionConfirmed(evt: BankAccountEvent)
  private case class BankAccountExceptionConfirmed(evt: BankAccountException)

  // Subscribe to event log for all events for this transaction.
  implicit val materializer = ActorMaterializer()
  val readJournal = PersistenceQuery(context.system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
  val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(persistenceId, Offset.noOffset)
  source.map(_.event).runForeach {
    case evt: BankAccountTransaction => self ! BankAccountTransactionConfirmed(evt)
    case ex: BankAccountException    => self ! BankAccountExceptionConfirmed(ex)
  }

  override def receiveCommand: Receive = uninitialized.orElse(stateReporting)

  /**
    * In this state we are hobbled until we are sent the start message. Instantiation of this actor has to be in two
    * steps since the edge, in this case the restful route, must assign the transactionId, which automatically
    * becomes the persistentId. Since cluster sharding only allows construction with objects known when the app
    * starts, we have to send the commands as a second step.
    */
  def uninitialized: Receive = {
    case StartBankAccountSaga(commands, transactionId) =>
      log.info(s"starting new bank account saga with transactionId: $transactionId")
      transitionToPending(commands)
  }

  /**
    * The pending state. No commit OR rollback will occur until all pending events are in place, as per a Saga.
    */
  def pending: Receive = {
    case BankAccountTransactionConfirmed(evt) =>
      if (!state.pendingConfirmed.contains(evt.accountNumber)) {
        state = state.copy(pendingConfirmed = state.pendingConfirmed :+ evt.accountNumber)
        saveSnapshot(state)
      }

    case BankAccountExceptionConfirmed(ex) =>
      if (!state.exceptions.contains()) {
        state = state.copy(exceptions = state.exceptions :+ ex)
        log.error(s"Transaction rolling back due to exception on account ${ex.accountNumber}.")
        saveSnapshot(state)
      }

      // Check if done here
      if (state.pendingConfirmed.size + state.exceptions.size == state.commands.size)
        if (state.exceptions.isEmpty)
          transitionToCommit()
        else
          transitionToRollback()
  }

  /**
    * The committing state. When in this state we can only repeatedly attempt to commit. This transaction will remain
    * alive until commits have occurred across the board.
    */
  def committing: Receive = {
    case BankAccountTransactionConfirmed(evt) =>
      if (!state.commitConfirmed.contains(evt.accountNumber)) {
        state = state.copy(commitConfirmed = state.commitConfirmed :+ evt.accountNumber)
        saveSnapshot(state)
      }

      // Check if done here
      if (state.commitConfirmed.size == state.commands.size) {
        log.info(s"Bank account saga completed successfully for transactionId: ${state.transactionId}")
        context.stop(self)
      }
  }

  /**
    * The rolling back state.
    */
  def rollingBack: Receive = {
    case BankAccountTransactionConfirmed(evt) =>
      if (!state.commitConfirmed.contains(evt.accountNumber)) {
        state = state.copy(rollbackConfirmed = state.rollbackConfirmed :+ evt.accountNumber)
        saveSnapshot(state)
      }

      // Check if done here
      if (state.rollbackConfirmed.size == state.commands.size - state.exceptions.size) {
        log.info(s"Bank account saga rolled back successfully for transactionId: ${state.transactionId}")
        context.stop(self)
      }
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot: BankAccountSagaState) =>
      state = snapshot

      state.currentState match {
        case Uninitialized => context.become(uninitialized.orElse(stateReporting))
        case Pending       => context.become(pending.orElse(stateReporting))
        case Committing    => context.become(rollingBack.orElse(stateReporting))
        case RollingBack   => context.become(committing.orElse(stateReporting))
      }
  }

  /**
    * Change to pending state.
    */
  private def transitionToPending(commands: Seq[BankAccount.BankAccountTransactionalCommand]): Unit = {
    state = state.copy(currentState = Pending, commands = commands)
    saveSnapshot(state)
    context.become(pending.orElse(stateReporting))

    commands.foreach { a =>
      bankAccountRegion ! PendingTransaction(a, state.transactionId)
    }
  }

  /**
    * Change to committing state.
    */
  private def transitionToCommit(): Unit = {
    state = state.copy(currentState = Committing)
    saveSnapshot(state)
    context.become(committing.orElse(stateReporting))

    state.commands.foreach( c =>
      bankAccountRegion ! CommitTransaction(c, state.transactionId)
    )
  }

  /**
    * Change to rollback state.
    */
  private def transitionToRollback(): Unit = {
    state = state.copy(currentState = RollingBack)
    saveSnapshot(state)
    context.become(rollingBack.orElse(stateReporting))

    state.commands.foreach( c =>
      bankAccountRegion ! RollbackTransaction(c, state.transactionId)
    )
  }

  /**
    * Report current state for ease of testing.
    */
  def stateReporting: Receive = {
    case GetBankAccountSagaState =>
      sender() ! state
  }
}
