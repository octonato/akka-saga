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
  import PersistentSagaActor._

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
    case StartTransaction(transactionId, cmd)  =>
      cmd match {
        case DepositFunds(accountNumber, amount, _) =>
          persist(Tagged(TransactionStarted(transactionId, FundsDeposited(accountNumber, amount)),
            Set(transactionId))) { evt =>
              state = state.copy(pendingBalance = state.balance + amount)
              transitionToInTransaction(evt.payload.asInstanceOf[TransactionalEventEnvelope])
          }
        case WithdrawFunds(accountNumber, amount, _) =>
          if (state.balance - amount >= 0)
            persist(Tagged(TransactionStarted(transactionId, FundsWithdrawn(accountNumber, amount)),
              Set( transactionId))) { evt =>
              state = state.copy(pendingBalance = state.balance - amount)
              transitionToInTransaction(evt.payload.asInstanceOf[TransactionalEventEnvelope])
            }
          else {
            persist(Tagged(InsufficientFunds(accountNumber, state.balance, amount), Set(transactionId)))
            { _ =>
              transitionToActive()
            }
          }
      }
  }

  def inTransaction(processing: TransactionalEvent): Receive = {
    case CommitTransaction(transactionId, _) =>
      persist(Tagged(TransactionCleared(transactionId, processing), Set(transactionId))) { _ =>
        state = state.copy(balance = state.pendingBalance, pendingBalance = 0)
        transitionToActive()
      }

    case RollbackTransaction(transactionId, _) =>
      persist(Tagged(TransactionReversed(transactionId, processing), Set(transactionId))) { _ =>
        state = state.copy(pendingBalance = 0)
        transitionToActive()
      }
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
  private def transitionToInTransaction(processing: TransactionalEventEnvelope): Unit = {
    state = state.copy(currentState = InTransaction)
    context.become(inTransaction(processing.event).orElse(stateReporting).orElse { case _ => stash })
  }

  override def receiveRecover: Receive = {
    case _: BankAccountCreated =>
      transitionToActive()

    case started @ TransactionStarted(_, evt) =>
      transitionToInTransaction(started)
      val amount = evt.asInstanceOf[BankAccountTransactionalEvent].amount

      evt match {
        case _: FundsDeposited =>
          state = state.copy(pendingBalance = state.balance + amount)
        case _: FundsWithdrawn =>
          state = state.copy(pendingBalance = state.balance - amount)
      }

    case cleared :TransactionCleared =>
      transitionToInTransaction(cleared)
      state = state.copy(balance = state.pendingBalance, pendingBalance = 0)

    case _: TransactionReversed =>
      state = state.copy(pendingBalance = 0)
      transitionToActive()

  }
}
