package com.example

import java.util.UUID

import akka.actor.{ActorLogging, ActorRef, ReceiveTimeout}
import akka.persistence.PersistentActor
import BankAccount._

import scala.concurrent.duration.FiniteDuration

/**
  * This is effectively a long lived saga that operates within an Akka cluster. Classic saga patterns will be followed,
  * such as retrying rollback over and over as well as retry of transactions over and over if necessary, before
  * rollback.
  *
  * Limitations--any particular bank account may only participate in a saga once--for now.
  *
  * @param commands Seq[TransactionalCommand] the ordered commands to be used in saga transaction.
  * @param shardRegion ActorRef the shard region for the bank accounts.
  * @param timeout FiniteDuration the timeout of this saga. Upon timeout, rollback will automatically occur.
  */
class TransactionCoordinator(commands: Seq[BankAccountTransactionalCommand], shardRegion: ActorRef, timeout: FiniteDuration)
  extends PersistentActor with ActorLogging {

  val transactionId: String = UUID.randomUUID().toString

  override def persistenceId: String = transactionId

  val pendingTransactions, commits, rollbacks: Seq[BankAccountTransactionalCommand] = Seq.empty

  context.setReceiveTimeout(timeout)

  override def preStart(): Unit = {

    super.preStart()

    startTransactions()
  }

  override def receiveCommand: Receive = pending

  /**
    * The pending state.
    */
  def pending: Receive = {
    case event: PendingTransaction =>
      pendingTransactions :+ commands.find(_.accountNumber == event.accountNumber).get

      if (canCommit()) {
        context.become(committing)
        commit()
      }

    case _: InsufficientFunds =>
      rollback()

    case ReceiveTimeout =>

  }

  /**
    * The committing state.
    */
  def committing: Receive = {
    case event: CommitTransaction =>
      commits :+ commands.find(_.accountNumber == event.accountNumber).get

    case ReceiveTimeout =>

  }

  /**
    * The rolling back state.
    */
  def rollingBack: Receive = {
    case event: RollbackTransaction =>
      rollbacks:+ commands.find(_.accountNumber == event.accountNumber).get
  }

  /**
    * Send all commands to persistent bank account entities.
    */
  def startTransactions(): Unit =
    commands.foreach( a =>
      shardRegion ! Pending(a)
    )

  /**
    * Determines that all transactions are pending and ready to commit.
    * @return Boolean
    */
  def canCommit(): Boolean =
    if (pendingTransactions.size == commands.size)
      true
    else
      false

  /**
    * Attempts to commit all previously uncommitted commands.
    */
  def commit(): Unit =
    if (pendingTransactions.size == commands.size)
      pendingTransactions.foreach( p =>
        shardRegion ! Commit(p)
      )

  def rollback(): Unit = {

  }

  override def receiveRecover: Receive = {
    throw new Exception //TODO
  }
}
