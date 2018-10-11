package com.example

import akka.actor.{ActorLogging, Props, Stash}
import akka.cluster.sharding.ShardRegion
import akka.persistence.PersistentActor
import akka.persistence.journal.Tagged

/**
  * Bank account companion object.
  */
case object BankAccount {

  trait BankAccountCommand {
    def accountNumber: String
  }

  trait BankAccountTransactionalCommand extends BankAccountCommand {
    def amount: BigDecimal
    def transactionType: String
  }

  case class CreateBankAccount(customerId: String, accountNumber: String) extends BankAccountCommand
  case class DepositFunds(accountNumber: String, amount: BigDecimal, final val transactionType: String = "DepositFunds")
    extends BankAccountTransactionalCommand
  case class WithdrawFunds(accountNumber: String, amount: BigDecimal, final val transactionType: String = "WithdrawFunds")
    extends BankAccountTransactionalCommand
  case class GetBankAccount(accountNumber: String) extends BankAccountCommand
  case object GetBankAccountState

  case class PendingTransaction(command: BankAccountTransactionalCommand, transactionId: String)
  case class CommitTransaction(command: BankAccountTransactionalCommand, transactionId: String)
  case class RollbackTransaction(command: BankAccountTransactionalCommand, transactionId: String)

  trait BankAccountEvent {
    def accountNumber: String
  }

  trait BankAccountTransaction extends BankAccountEvent {
    def transactionId: String
    def amount: BigDecimal
  }

  trait BankAccountTransactionRolledBack extends BankAccountEvent
  trait BankAccountException extends BankAccountEvent

  case class BankAccountCreated(customerId: String, accountNumber: String) extends BankAccountEvent
  case class FundsDepositedPending(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransaction
  case class FundsDepositedReversal(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransactionRolledBack
  case class FundsDeposited(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransaction
  case class InsufficientFunds(accountNumber: String, balance: BigDecimal, attemptedWithdrawal: BigDecimal)
    extends BankAccountException
  case class FundsWithdrawnPending(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransaction
  case class FundsWithdrawnReversal(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransactionRolledBack
  case class FundsWithdrawn(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransaction

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case cmd: BankAccountCommand => (cmd.accountNumber, cmd)
  }

  val BankAccountShardCount = 2 // TODO: get from config

  val extractShardId: ShardRegion.ExtractShardId = {
    case cmd: BankAccountCommand => (cmd.accountNumber.hashCode % BankAccountShardCount).toString
    case ShardRegion.StartEntity(id) ⇒
      // StartEntity is used by remembering entities feature
      (id.hashCode % BankAccountShardCount).toString
  }

  case class BankAccountState(
    currentState: String = "uninitialized",
    balance: BigDecimal = 0,
    pendingBalance: BigDecimal = 0)

  def props(): Props = Props(classOf[BankAccount])
}

/**
  * I am a bank account modeled as persistent actor.
  */
class BankAccount extends PersistentActor with ActorLogging with Stash {

  import BankAccount._

  override def persistenceId: String = self.path.name

  private var state: BankAccountState = BankAccountState()

  override def receiveCommand: Receive = default.orElse(stateReporting)

  def default: Receive = {

    case CreateBankAccount(customerId, accountNumber) =>
      persist(BankAccountCreated(customerId, accountNumber)) { _ =>
        log.info(s"Creating BankAccount with persistenceId $persistenceId")
        transitionToActive()
      }
  }

  def active: Receive = {
    case PendingTransaction(DepositFunds(_, amount, _), transactionId)  =>
      persist(Tagged(FundsDepositedPending(persistenceId, transactionId, amount), Set(transactionId))) { evt =>
        state = state.copy(pendingBalance = state.balance + amount)
        transitionToInTransaction(evt.payload.asInstanceOf[BankAccountTransaction])
      }

    case PendingTransaction(WithdrawFunds(_, amount, _), transactionId) =>
      if (state.balance - amount > 0)
        persist(Tagged(FundsWithdrawnPending(persistenceId, transactionId, amount), Set( transactionId))) { evt =>
          state = state.copy(pendingBalance = state.balance - amount)
          transitionToInTransaction(evt.payload.asInstanceOf[BankAccountTransaction])
        }
      else {
        persist(Tagged(InsufficientFunds(persistenceId, state.balance, amount), Set(transactionId)))
          { _ =>
            transitionToActive()
          }
      }
  }

  def inTransaction(processing: BankAccountTransaction): Receive = {
    case transaction @ CommitTransaction(DepositFunds(_, amount, transactionType), transactionId) =>
      if (amount == processing.amount && transactionType == "DepositFunds")
        persist(Tagged(FundsDeposited(persistenceId, transactionId, amount), Set(transactionId))) { _ =>
          state = state.copy(balance = state.pendingBalance, pendingBalance = 0)
          transitionToActive()
        }
      else
        log.error(s"Attempt to commit ${transaction.command.getClass.getSimpleName}($persistenceId, $amount) " +
          s" with ${processing.getClass.getSimpleName}($persistenceId, ${processing.amount}) outstanding.")

    case transaction @ CommitTransaction(WithdrawFunds(_, amount, transactionType), transactionId) =>
      if (amount == processing.amount && transactionType == "WithdrawFunds")
        persist(Tagged(FundsWithdrawn(persistenceId, transactionId, amount), Set(transactionId))) { _ =>
          state = state.copy(balance = state.pendingBalance, pendingBalance = 0)
          transitionToActive()
        }
      else
        log.error(s"Attempt to commit ${transaction.command.getClass.getSimpleName}($persistenceId, $amount) " +
          s" with ${processing.getClass.getSimpleName}($persistenceId, ${processing.amount}) outstanding.")

    case transaction @ RollbackTransaction(DepositFunds(_, amount, transactionType), transactionId) =>
      if (amount == processing.amount && transactionType == "DepositFunds")
        persist(Tagged(FundsDepositedReversal(persistenceId, transactionId, amount), Set(transactionId)))
          { _ =>
            state = state.copy(pendingBalance = 0)
            transitionToActive()
          }
      else
        log.error(s"Attempt to rollback ${transaction.command.getClass.getSimpleName}($persistenceId, $amount) " +
          s" with ${processing.getClass.getSimpleName}($persistenceId, ${processing.amount}) outstanding.")

    case transaction @ RollbackTransaction(WithdrawFunds(_, amount, transactionType), transactionId) =>
      if (amount == processing.amount && transactionType == "WithdrawFunds")
        persist(Tagged(FundsWithdrawnReversal(persistenceId, transactionId, amount), Set(transactionId)))
          { _ =>
            state = state.copy(pendingBalance = 0)
            transitionToActive()
          }
      else
        log.error(s"Attempt to rollback ${transaction.command.getClass.getSimpleName}($persistenceId, $amount) " +
          s" with ${processing.getClass.getSimpleName}($persistenceId, ${processing.amount}) outstanding.")
  }

  /**
    * Report current state for ease of testing.
    */
  def stateReporting: Receive = {
    case GetBankAccountState => sender() ! state
    case _ => stash()
  }

  /**
    * Change to this state (context.become) while changing currentState value.
    */
  private def transitionToActive(): Unit = {
    state = state.copy(currentState = "active")
    context.become(active.orElse(stateReporting))
    unstashAll()
  }

  /**
    * Change to this state (context.become) while changing currentState value.
    */
  private def transitionToInTransaction(processing: BankAccountTransaction): Unit = {
    state = state.copy(currentState = "inTransaction")
    context.become(inTransaction(processing).orElse(stateReporting))
  }

  override def receiveRecover: Receive = {
    case _: BankAccountCreated =>
      transitionToActive()

    case evt @ FundsDepositedPending(_, _, amount) =>
      transitionToInTransaction(evt)
      state = state.copy(pendingBalance = state.balance + amount)

    case _: FundsDeposited =>
      transitionToActive()
      state = state.copy(balance = state.pendingBalance, pendingBalance = 0)

    case _: FundsDepositedReversal =>
      transitionToActive()
      state = state.copy(pendingBalance = 0)

    case evt @ FundsWithdrawnPending(_, _, amount) =>
      transitionToInTransaction(evt)
      state = state.copy(pendingBalance = state.balance - amount)

    case _: FundsWithdrawn =>
      transitionToActive()
      state = state.copy(balance = state.pendingBalance, pendingBalance = 0)

    case _: FundsWithdrawnReversal =>
      transitionToActive()
      state = state.copy(pendingBalance = 0)
  }
}
