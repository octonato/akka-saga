package com.example

import akka.actor.{ActorLogging, Stash}
import akka.cluster.sharding.ShardRegion
import akka.persistence.PersistentActor

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
  case object GetState

  case class Pending(command: BankAccountTransactionalCommand, transactionId: String)
  case class Commit(command: BankAccountTransactionalCommand, transactionId: String)
  case class Rollback(command: BankAccountTransactionalCommand, transactionId: String)

  trait BankAccountEvent {
    def accountNumber: String
  }

  trait BankAccountTransactionPending extends BankAccountEvent {
    def amount: BigDecimal
  }

  trait BankAccountTransactionCommitted extends BankAccountEvent
  trait BankAccountTransactionRolledBack extends BankAccountEvent
  trait BankAccountException extends BankAccountEvent

  case class BankAccountCreated(customerId: String, accountNumber: String) extends BankAccountEvent
  case class FundsDepositedPending(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountEvent with BankAccountTransactionPending
  case class FundsDepositedReversal(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransactionRolledBack
  case class FundsDeposited(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransactionCommitted
  case class InsufficientFunds(accountNumber: String, balance: BigDecimal, attemptedWithdrawal: BigDecimal)
    extends BankAccountException
  case class FundsWithdrawnPending(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransactionPending
  case class FundsWithdrawnReversal(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransactionRolledBack
  case class FundsWithdrawn(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransactionCommitted

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
}

case class BankAccountState(currentState: String = "uninitialized", balance: BigDecimal = 0, pendingBalance: BigDecimal = 0)

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
    case Pending(DepositFunds(_, amount, _), transactionId)  =>
      persist(FundsDepositedPending(persistenceId, transactionId, amount)) { evt =>
        state = state.copy(pendingBalance = state.balance + amount)
        transitionToInTransaction(evt )
      }

    case Pending(WithdrawFunds(_, amount, _), transactionId) =>
      if (state.balance - amount > 0)
        persist(FundsWithdrawnPending(persistenceId, transactionId, amount)) { evt =>
          state = state.copy(pendingBalance = state.balance - amount)
          transitionToInTransaction(evt)
        }
      else
        persist(InsufficientFunds(persistenceId, state.balance, amount))(_)
  }

  def inTransaction(processing: BankAccountTransactionPending): Receive = {
    case transaction @ Commit(DepositFunds(_, amount, transactionType), transactionId) =>
      if (amount == processing.amount && transactionType == "DepositFunds")
        persist(FundsDeposited(persistenceId, transactionId, amount)) { event =>
          state = state.copy(balance = state.pendingBalance, pendingBalance = 0)
          transitionToActive()
        }
      else
        log.error(s"Attempt to commit ${transaction.command.getClass.getSimpleName}($persistenceId, $amount) " +
          s" with ${processing.getClass.getSimpleName}($persistenceId, ${processing.amount}) outstanding.")

    case Commit(WithdrawFunds(_, amount, _), transactionId) =>
      persist(FundsWithdrawn(persistenceId, transactionId, amount)) { _ =>
        state = state.copy(balance = state.pendingBalance, pendingBalance = 0)
        transitionToActive()
      }

    case Rollback(DepositFunds(_, amount, _), transactionId) =>
      persist(FundsDepositedReversal(persistenceId, transactionId, amount)) { _ =>
        state = state.copy(pendingBalance = 0)
        transitionToActive()
      }

    case Rollback(WithdrawFunds(_, amount, _), transactionId) =>
      persist(FundsWithdrawnReversal(persistenceId, transactionId, amount)) { _ =>
        state = state.copy(pendingBalance = 0)
        transitionToActive()
      }
  }

  /**
    * Report current state for ease of testing.
    */
  def stateReporting: Receive = {
    case GetState => sender() ! state
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
  private def transitionToInTransaction(processing: BankAccountTransactionPending): Unit = {
    state = state.copy(currentState = "inTransaction")
    context.become(inTransaction(processing).orElse(stateReporting))
  }

  override def receiveRecover: Receive = {
    case _: BankAccountCreated =>
      transitionToActive()

    case evt @ FundsDepositedPending(_, _, amount) =>
      transitionToInTransaction(evt)
      state = state.copy(pendingBalance = state.pendingBalance + amount)

    case _: FundsDeposited =>
      transitionToActive()
      state = state.copy(balance = state.pendingBalance, pendingBalance = 0)

    case _: FundsDepositedReversal =>
      transitionToActive()
      state = state.copy(pendingBalance = 0)

    case evt @ FundsWithdrawnPending(_, _, amount) =>
      transitionToInTransaction(evt)
      state = state.copy(pendingBalance = state.pendingBalance - amount)

    case _: FundsWithdrawn =>
      transitionToActive()
      state = state.copy(balance = state.pendingBalance, pendingBalance = 0)

    case _: FundsWithdrawnReversal =>
      transitionToActive()
      state = state.copy(pendingBalance = 0)
  }
}
