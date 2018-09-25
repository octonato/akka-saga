package com.example

import java.util.UUID

import akka.actor.{ActorLogging, ActorRef, Props, ReceiveTimeout}
import akka.persistence.{PersistentActor, SnapshotOffer}
import BankAccount.{BankAccountTransactionalCommand, _}
import akka.cluster.sharding.ShardRegion

import scala.concurrent.duration._

/**
  * Transaction coordinator companion object.
  */
object BankAccountSaga {

  case class StartTransaction(commands: Seq[BankAccountTransactionalCommand], transactionId: Option[String])

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case cmd: StartTransaction => (cmd.transactionId.getOrElse(
      throw new Exception("StartTransaction command missing transactionId")), cmd)
  }

  val numberOfShards = 1

  val extractShardId: ShardRegion.ExtractShardId = {
    case cmd: StartTransaction => (cmd.transactionId.getOrElse(
      throw new Exception("StartTransaction command missing transactionId")).hashCode % numberOfShards).toString
    case ShardRegion.StartEntity(id) ⇒
      // StartEntity is used by remembering entities feature
      (id.hashCode % numberOfShards).toString
  }

  def props(bankAccountRegion: ActorRef, timeout: FiniteDuration): Props =
    Props(classOf[BankAccountSaga], bankAccountRegion, timeout)
}

/**
  * This is effectively a long lived saga that operates within an Akka cluster. Classic saga patterns will be followed,
  * such as retrying rollback over and over as well as retry of transactions over and over if necessary, before
  * rollback.
  *
  * Limitations--any particular bank account may only participate in a saga once--for now.
  *
  * @param bankAccountRegion ActorRef the shard region for the bank accounts.
  * @param timeout FiniteDuration the timeout of this saga. Upon timeout, rollback will automatically occur.
  */
class BankAccountSaga(bankAccountRegion: ActorRef, timeout: FiniteDuration)
  extends PersistentActor with ActorLogging {

  import BankAccountSaga._

  val transactionId: String = UUID.randomUUID().toString
  val initialTimeout: FiniteDuration = 1.second

  override def persistenceId: String = transactionId

  case class TransactionCoordinatorState(
    commands: Seq[BankAccountTransactionalCommand],
    pendingTransactions: Seq[BankAccountTransactionalCommand] = Seq.empty,
    commits: Seq[BankAccountTransactionalCommand] = Seq.empty,
    rollbacks: Seq[BankAccountTransactionalCommand] = Seq.empty)

  // Our one and only state. We snapshot this state any time there is a change.
  var state: TransactionCoordinatorState = null

  context.setReceiveTimeout(initialTimeout)

  override def receiveCommand: Receive = ready

  def ready: Receive = {

    case transaction: StartTransaction =>
      require(transaction.transactionId.isDefined, "Missing transactionId!")
      log.info(s"received transaction ${transaction.transactionId}")
      state = TransactionCoordinatorState(transaction.commands)
      saveSnapshot(state)
      startTransactions()
      context.setReceiveTimeout(timeout)
      context.become(pending)

    case ReceiveTimeout =>
      log.error(s"Transaction timed out and never started after $timeout...aborting.")
      rollback()
  }

  /**
    * The pending state.
    */
  def pending: Receive = {

    case event: BankAccountTransactionPending =>
      state.pendingTransactions :+ state.commands.find(_.accountNumber == event.accountNumber).get
      saveSnapshot(state)

      if (canCommit()) {
        context.become(committing)
        commit()
      }

    case event: InsufficientFunds =>
      log.error(s"Transaction rolling back due to InsufficientFunds on account ${event.accountNumber}.")
      rollback()

    case ReceiveTimeout =>
      log.error(s"Transaction timed out after $timeout...starting rollback.")
      rollback()
  }

  /**
    * The committing state. When in this state we can only repeatedly attempt to commit. This transaction will remain
    * alive until commits have occurred across the board.
    */
  def committing: Receive = {

    case event: BankAccountTransactionCommitted =>
      state.commits :+ state.commands.find(_.accountNumber == event.accountNumber).get
      saveSnapshot(state)
  }

  /**
    * The rolling back state.
    */
  def rollingBack: Receive = {

    case event: BankAccountTransactionRolledBack =>
      state.rollbacks:+ state.commands.find(_.accountNumber == event.accountNumber).get
      saveSnapshot(state)
  }

  /**
    * Send all commands to persistent bank account entities.
    */
  def startTransactions(): Unit =
    state.commands.foreach( a =>
      bankAccountRegion ! Pending(a)
    )

  /**
    * Determines that all transactions are pending and ready to commit.
    * @return Boolean
    */
  def canCommit(): Boolean =
    if (state.pendingTransactions.size == state.commands.size)
      true
    else
      false

  /**
    * Attempts to commit all previously uncommitted commands.
    */
  def commit(): Unit =
    state.pendingTransactions.foreach( p =>
      bankAccountRegion ! Commit(p)
    )

  /**
    * Attempts to rollback all previously uncommitted commands.
    */
  def rollback(): Unit = {
    state.commands.foreach( c =>
      bankAccountRegion ! Rollback(c)
    )
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot: TransactionCoordinatorState) => state = snapshot
  }
}
