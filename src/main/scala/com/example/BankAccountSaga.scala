package com.example

import akka.actor.{ActorLogging, ActorRef, Props, ReceiveTimeout}
import akka.persistence.{PersistentActor, SnapshotOffer}
import BankAccount.{BankAccountTransactionalCommand, _}
import akka.cluster.sharding.ShardRegion
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.EventsByPersistenceIdQuery

import scala.concurrent.duration._

/**
  * Transaction coordinator companion object.
  */
object BankAccountSaga {

  case class StartBankAccountSaga(commands: Seq[BankAccountTransactionalCommand], transactionId: String)
  case object GetBankAccountSagaState

  // States
  final val UninitializedState = "uninitialized"
  final val PendingState = "pending"
  final val CommittingState = "committing"
  final val RollingBackState = "rollingBack"

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
    currentState: String = UninitializedState,
    transactionId: String,
    commands: Seq[BankAccountTransactionalCommand] = Seq.empty,
    pendingConfirmed: Seq[AccountNumber] = Seq.empty,
    commitConfirmed: Seq[AccountNumber] = Seq.empty,
    rollbackConfirmed: Seq[AccountNumber] = Seq.empty,
    exceptions: Seq[InsufficientFunds] = Seq.empty,
    timedOut: Boolean = false)

  def props(bankAccountRegion: ActorRef, timeout: FiniteDuration, events: EventsByPersistenceIdQuery): Props =
    Props(classOf[BankAccountSaga], bankAccountRegion, timeout, events)
}

/**
  * This is effectively a long lived saga that operates within an Akka cluster. Classic saga patterns will be followed,
  * such as retrying rollback over and over as well as retry of transactions over and over if necessary, before
  * rollback.
  *
  * Limitations--any particular bank account may only participate in a saga once--for now.
  */
class BankAccountSaga(bankAccountRegion: ActorRef, timeout: FiniteDuration, readJournal: LeveldbReadJournal)
  extends PersistentActor with ActorLogging {

  import BankAccountSaga._
  import context.dispatcher

  private var state: BankAccountSagaState = null
  private val eventCheckDuration: FiniteDuration = 200.milliseconds
  private val eventChecker = new EventChecker()
  private case object CheckEvents

  override def persistenceId: String = self.path.name
  context.setReceiveTimeout(timeout)
  override def receiveCommand: Receive = uninitialized.orElse(stateReporting)

  def uninitialized: Receive = {
    case StartBankAccountSaga(commands, transactionId) =>
      log.info(s"received StartBankAccountSaga $transactionId")
      transitionToPending(commands, transactionId)

    case ReceiveTimeout =>
      log.error(s"Transaction timed out and never started after $timeout...aborting.")
      transitionToRollback()
  }

  /**
    * The pending state. No commit OR rollback will occur until all pending events are in place, as per a Saga.
    */
  def pending: Receive = {
    case CheckEvents =>
      val res = eventChecker.checkEvents(state.transactionId, readJournal, state.commands.map(_.accountNumber),
        state.pendingConfirmed, state.commitConfirmed, state.rollbackConfirmed, state.exceptions)
      state = state.copy(pendingConfirmed = res.pendingConfirmed, commitConfirmed = res.commitConfirmed,
        rollbackConfirmed = res.rollbackConfirmed, exceptions = res.exceptions)
      saveSnapshot(state)

      // Log the new exceptions as early as we can
      res.exceptions.foreach( e =>
        log.error(s"Transaction rolling back due to InsufficientFunds on account ${e.accountNumber}.")
      )

      // Check if done here
      if (state.pendingConfirmed.size + state.exceptions.size == state.commands.size)
        if (state.exceptions.isEmpty)
          transitionToCommit()
        else
          transitionToRollback()
      else
        context.system.scheduler.scheduleOnce(eventCheckDuration, self, CheckEvents)

    case ReceiveTimeout =>
      log.error(s"Transaction timed out after $timeout...will rollback when possible.")
      context.setReceiveTimeout(Duration.Undefined)
      state = state.copy(timedOut = true)
  }

  /**
    * The committing state. When in this state we can only repeatedly attempt to commit. This transaction will remain
    * alive until commits have occurred across the board.
    */
  def committing: Receive = {
    case CheckEvents =>
      val res = eventChecker.checkEvents(state.transactionId, readJournal, state.commands.map(_.accountNumber),
        state.pendingConfirmed, state.commitConfirmed, state.commitConfirmed, state.exceptions)
      state = state.copy(pendingConfirmed = res.pendingConfirmed, commitConfirmed = res.commitConfirmed,
        rollbackConfirmed = res.rollbackConfirmed, exceptions = res.exceptions)
      saveSnapshot(state)

      // Check if done here
      if (state.commitConfirmed.size == state.commands.size) {
        log.info(s"Bank account saga completed successfully for transactionId: ${state.transactionId}")
        context.stop(self)
      }
      else
        context.system.scheduler.scheduleOnce(eventCheckDuration, self, CheckEvents)
  }

  /**
    * The rolling back state.
    */
  def rollingBack: Receive = {
    case CheckEvents =>
      val res = eventChecker.checkEvents(state.transactionId, readJournal, state.commands.map(_.accountNumber),
        state.pendingConfirmed, state.commitConfirmed, state.commitConfirmed, state.exceptions)
      state = state.copy(pendingConfirmed = res.pendingConfirmed, commitConfirmed = res.commitConfirmed,
        rollbackConfirmed = res.rollbackConfirmed, exceptions = res.exceptions)
      saveSnapshot(state)

      // Check if done here
      if (state.rollbackConfirmed.size == state.commands.size - state.exceptions.size) {
        log.info(s"Bank account saga rolled back successfully for transactionId: ${state.transactionId}")
        context.stop(self)
      }
      else
        context.system.scheduler.scheduleOnce(eventCheckDuration, self, CheckEvents)
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot: BankAccountSagaState) =>
      state = snapshot

      state.currentState match {
        case UninitializedState => context.become(uninitialized.orElse(stateReporting))
        case PendingState       => context.become(pending.orElse(stateReporting))
        case CommittingState    => context.become(rollingBack.orElse(stateReporting))
        case RollingBackState   => context.become(committing.orElse(stateReporting))
      }
  }

  /**
    * Change to pending state.
    */
  private def transitionToPending(commands: Seq[BankAccount.BankAccountTransactionalCommand],
                                  transactionId: String): Unit = {

    state = BankAccountSagaState(PendingState, transactionId, commands)
    saveSnapshot(state)
    context.become(pending.orElse(stateReporting))
    context.system.scheduler.scheduleOnce(eventCheckDuration, self, CheckEvents)

    state.commands.foreach( a =>
      bankAccountRegion ! Pending(a, state.transactionId)
    )
  }

  /**
    * Change to committing state.
    */
  private def transitionToCommit(): Unit = {
    state = state.copy(currentState = CommittingState)
    saveSnapshot(state)
    context.become(committing.orElse(stateReporting))

    state.commands.foreach( c =>
      bankAccountRegion ! Commit(c, state.transactionId)
    )
  }

  /**
    * Change to rollback state.
    */
  private def transitionToRollback(): Unit = {
    state = state.copy(currentState = RollingBackState)
    saveSnapshot(state)
    context.become(rollingBack.orElse(stateReporting))

    state.commands.foreach( c =>
      bankAccountRegion ! Rollback(c, state.transactionId)
    )
  }

  /**
    * Report current state for ease of testing.
    */
  def stateReporting: Receive = {
    case GetBankAccountSagaState =>
      println("getting state....")
      sender() ! state
  }
}
