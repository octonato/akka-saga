package com.example

import akka.actor.{ActorLogging, Props, Stash}
import akka.persistence.PersistentActor
import akka.persistence.journal.Tagged

/**
  * Bank account companion object.
  */
case object BankAccount {

  final val DepositFundsTransactionType: String = "DepositFunds"
  final val WithdrawFundsTransactionType: String = "WithdrawFunds"

  // States
  object BankAccountStates  {
    val Uninitialized = "uninitialized"
    val Active = "active"
    val InTransaction = "inTransaction"
  }

  import BankAccountStates._
  case class BankAccountState(
    currentState: String = Uninitialized,
    balance: BigDecimal = 0,
    pendingBalance: BigDecimal = 0)

  def props(): Props = Props(classOf[BankAccount])
}

/**
  * I am a bank account modeled as persistent actor.
  */
class BankAccount extends PersistentActor with ActorLogging with Stash {

  import BankAccount._
  import BankAccountStates._
  import BankAccountCommands._
  import BankAccountEvents._
  import BankAccountSaga._

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

    case PendingTransaction(WithdrawFunds(accountNumber, amount, _), transactionId) =>
      if (state.balance - amount >= 0)
        persist(Tagged(FundsWithdrawnPending(persistenceId, transactionId, amount), Set( transactionId))) { evt =>
          state = state.copy(pendingBalance = state.balance - amount)
          transitionToInTransaction(evt.payload.asInstanceOf[BankAccountTransaction])
        }
      else {
        persist(Tagged(InsufficientFunds(accountNumber, transactionId, state.balance, amount), Set(transactionId)))
          { e =>
            transitionToActive()
          }
      }
  }

  def inTransaction(processing: BankAccountTransaction): Receive = {
    case transaction @ CommitTransaction(DepositFunds(_, amount, transactionType), transactionId) =>
      if (amount == processing.amount && transactionType == DepositFundsTransactionType)
        persist(Tagged(FundsDeposited(persistenceId, transactionId, amount), Set(transactionId))) { _ =>
          state = state.copy(balance = state.pendingBalance, pendingBalance = 0)
          transitionToActive()
        }
      else
        log.error(s"Attempt to commit ${transaction.command.getClass.getSimpleName}($persistenceId, $amount) " +
          s" with ${processing.getClass.getSimpleName}($persistenceId, ${processing.amount}) outstanding.")

    case transaction @ CommitTransaction(WithdrawFunds(_, amount, transactionType), transactionId) =>
      if (amount == processing.amount && transactionType == WithdrawFundsTransactionType)
        persist(Tagged(FundsWithdrawn(persistenceId, transactionId, amount), Set(transactionId))) { _ =>
          state = state.copy(balance = state.pendingBalance, pendingBalance = 0)
          transitionToActive()
        }
      else
        log.error(s"Attempt to commit ${transaction.command.getClass.getSimpleName}($persistenceId, $amount) " +
          s" with ${processing.getClass.getSimpleName}($persistenceId, ${processing.amount}) outstanding.")

    case transaction @ RollbackTransaction(DepositFunds(_, amount, transactionType), transactionId) =>
      if (amount == processing.amount && transactionType == DepositFundsTransactionType)
        persist(Tagged(FundsDepositedReversal(persistenceId, transactionId, amount), Set(transactionId))) { _ =>
          state = state.copy(pendingBalance = 0)
          transitionToActive()
        }
      else
        log.error(s"Attempt to rollback ${transaction.command.getClass.getSimpleName}($persistenceId, $amount) " +
          s" with ${processing.getClass.getSimpleName}($persistenceId, ${processing.amount}) outstanding.")

    case transaction @ RollbackTransaction(WithdrawFunds(_, amount, transactionType), transactionId) =>
      if (amount == processing.amount && transactionType == WithdrawFundsTransactionType)
        persist(Tagged(FundsWithdrawnReversal(persistenceId, transactionId, amount), Set(transactionId))) { _ =>
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
  }

  /**
    * Transition to active state, ready to process any stashed messages, if any.
    */
  private def transitionToActive(): Unit = {
    state = state.copy(currentState = Active)
    context.become(active.orElse(stateReporting))
    unstashAll()
  }

  /**
    * Transition to this in transaction state. When in a transaction we stash incoming messages that are not
    * part of the current transaction in process.
    */
  private def transitionToInTransaction(processing: BankAccountTransaction): Unit = {
    state = state.copy(currentState = InTransaction)
    context.become(inTransaction(processing).orElse(stateReporting).orElse { case _ => stash })
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
