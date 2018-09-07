package com.example

import akka.actor.{ActorLogging, Stash}
import akka.persistence.PersistentActor

/**
  * Companion object.
  */
case object BankAccount {

  trait BankAccountCommand {
    def accountNumber: String
  }

  trait BankAccountTransactionalCommand extends BankAccountCommand {
    def transactionId: String
    def amount: BigDecimal
  }

  case class CreateBankAccount(customerId: String, accountNumber: String) extends BankAccountCommand
  case class DepositFunds(accountNumber: String, transactionId: String, amount: BigDecimal) extends BankAccountTransactionalCommand
  case class WithdrawFunds(accountNumber: String, transactionId: String, amount: BigDecimal) extends BankAccountTransactionalCommand

  case class Pending(command: BankAccountTransactionalCommand)
  case class Commit(command: BankAccountTransactionalCommand)
  case class Rollback(command: BankAccountTransactionalCommand)

  trait BankAccountEvent {
    def accountNumber: String
  }

  trait PendingTransaction extends BankAccountEvent
  trait CommitTransaction extends BankAccountEvent
  trait RollbackTransaction extends BankAccountEvent

  trait BankAccountException extends BankAccountEvent
  case class BankAccountCreated(customerId: String, accountNumber: String) extends BankAccountEvent
  case class FundsDepositedPending(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountEvent with PendingTransaction
  case class FundsDepositedReversal(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends RollbackTransaction
  case class FundsDeposited(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends CommitTransaction
  case class InsufficientFunds(accountNumber: String, balance: BigDecimal, attemptedWithdrawal: BigDecimal)
    extends BankAccountException
  case class FundsWithdrawnPending(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends PendingTransaction
  case class FundsWithdrawnReversal(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends RollbackTransaction
  case class FundsWithdrawn(accountNumber: String, transactionId: String, amount: BigDecimal) extends CommitTransaction
}

/**
  * I am a bank account modeled as persistent actor.
  */
class BankAccount extends PersistentActor with ActorLogging with Stash {

  import BankAccount._

  private var accountNumber = ""

  override def persistenceId: String = accountNumber

  private var balance: BigDecimal = 0

  private val pendingTransactions: Seq[BankAccountEvent] = Seq.empty

  override def receiveCommand: Receive = {
    case CreateBankAccount(customerId, accountNumber) =>

      persist(BankAccountCreated(customerId, accountNumber)) { event =>
        this.accountNumber = accountNumber
        context.become(active)
      }
  }

  def active: Receive = {
    case Pending(DepositFunds(_, transactionId, amount))  =>

      persist(FundsDepositedPending(accountNumber, transactionId, amount)) { event =>
        balance = balance + amount
        pendingTransactions :+ event
        sideEffectEvent(event)
        context.become(inTransaction)
      }

    case Pending(WithdrawFunds(_, transactionId, amount)) =>

      if (balance - amount > 0)
        persist(FundsWithdrawnPending(accountNumber, transactionId, amount)) { event =>
          balance = balance - amount
          sideEffectEvent(event)
          context.become(inTransaction)
        }
      else
        persist(InsufficientFunds(accountNumber, balance, amount)) { event =>
          sideEffectEvent(event)
        }
  }

  def inTransaction: Receive = {
    case Commit(DepositFunds(_, transactionId, amount)) =>
      persist(FundsDeposited(accountNumber, transactionId, amount)) { event =>
        sideEffectEvent(event)
        context.become(active)
        unstashAll()
      }

    case Commit(WithdrawFunds(_, transactionId, amount)) =>
      persist(FundsWithdrawn(accountNumber, transactionId, amount)) { event =>
        sideEffectEvent(event)
        context.become(active)
        unstashAll()
      }

    case Rollback(DepositFunds(_, transactionId, amount)) =>
      persist(FundsDepositedReversal(accountNumber, transactionId, amount)) { event =>
        balance = balance - amount
        sideEffectEvent(event)
        context.become(active)
        unstashAll()
      }

    case Rollback(WithdrawFunds(_, transactionId, amount)) =>
      persist(FundsWithdrawnReversal(accountNumber, transactionId, amount)) { event =>
        balance = balance + amount
        sideEffectEvent(event)
        context.become(active)
        unstashAll()
      }

    case _ =>
      stash()
  }

  override def receiveRecover: Receive = {
    case BankAccountCreated(_, accountNumber) =>
      this.accountNumber = accountNumber
      context.become(active)

    case FundsDepositedPending(_, _, amount) =>
      balance = balance + amount

    case FundsDepositedReversal(_, _, amount) =>
      balance = balance - amount

    case FundsWithdrawnPending(_, _, amount) =>
      balance = balance - amount

    case FundsWithdrawnReversal(_, _, amount) =>
      balance = balance + amount
  }

  private def sideEffectEvent(event: BankAccountEvent): Unit = {
    sender() ! event
  }
}
