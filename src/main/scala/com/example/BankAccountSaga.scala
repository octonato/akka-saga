package com.example

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.persistence.{PersistentActor, SnapshotOffer}
import akka.cluster.sharding.{ClusterSharding, ShardRegion}

/**
  * Transaction coordinator companion object.
  */
object BankAccountSaga {

  import BankAccount._

  // Commands
  case class StartBankAccountSaga(commands: Seq[BankAccountTransactionalCommand], transactionId: String)
  case object GetBankAccountSagaState
  case class BankAccountEventConfirmed(evt: BankAccountEvent)

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
    currentState: String = Uninitialized,
    transactionId: String,
    commands: Seq[BankAccountTransactionalCommand] = Seq.empty,
    pendingConfirmed: Seq[AccountNumber] = Seq.empty,
    commitConfirmed: Seq[AccountNumber] = Seq.empty,
    rollbackConfirmed: Seq[AccountNumber] = Seq.empty,
    exceptions: Seq[InsufficientFunds] = Seq.empty,
    timedOut: Boolean = false)

  def props(): Props =
    Props(classOf[BankAccountSaga])
}

/**
  * This is effectively a long lived saga that operates within an Akka cluster. Classic saga patterns will be followed,
  * such as retrying rollback over and over as well as retry of transactions over and over if necessary, before
  * rollback.
  *
  * Limitations--any particular bank account may only participate in a saga once--for now.
  */
class BankAccountSaga() extends PersistentActor with ActorLogging {

  import BankAccountSaga._
  import BankAccount._
  import BankAccountSagaStates._

  private val bankAccountRegion: ActorRef = ClusterSharding(context.system).shardRegion("BankAccount")
  private var state: BankAccountSagaState = null

  override def persistenceId: String = self.path.name

  override def receiveCommand: Receive = uninitialized.orElse(stateReporting)

  def uninitialized: Receive = {
    case StartBankAccountSaga(commands, transactionId) =>
      log.info(s"received StartBankAccountSaga $transactionId")
      transitionToPending(commands, transactionId)
  }

  /**
    * The pending state. No commit OR rollback will occur until all pending events are in place, as per a Saga.
    */
  def pending: Receive = {
    case BankAccountEventConfirmed(evt) =>
      if (!state.pendingConfirmed.contains(evt.accountNumber)) {
        state = state.copy(pendingConfirmed = state.pendingConfirmed :+ evt.accountNumber)
        saveSnapshot(state)
      }

    case e @ InsufficientFunds(accountNumber, _, _) =>
      if (!state.exceptions.contains(accountNumber)) {
        state = state.copy(exceptions = state.exceptions :+ e)
        log.error(s"Transaction rolling back due to InsufficientFunds on account ${accountNumber}.")
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
    case BankAccountEventConfirmed(evt) =>
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
    case BankAccountEventConfirmed(evt) =>
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
  private def transitionToPending(commands: Seq[BankAccount.BankAccountTransactionalCommand],
                                  transactionId: String): Unit = {
    state = BankAccountSagaState(Pending, transactionId, commands)
    saveSnapshot(state)
    context.become(pending.orElse(stateReporting))

    state.commands.foreach( a =>
      bankAccountRegion ! PendingTransaction(a, state.transactionId)
    )

    // Start event subscriber to confirm events written to store.
    context.actorOf(BankAccountEventSubscriber.props(transactionId))
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
      println("getting state....")
      sender() ! state
  }
}
