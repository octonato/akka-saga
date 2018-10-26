package com.example

import akka.actor.{ActorLogging, Props, Stash}
import akka.persistence.PersistentActor
import akka.persistence.journal.Tagged

/**
  * Bank account companion object.
  */
case object BankAccount {

  // States of a bank account.
  object BankAccountStates  {
    val Uninitialized = "uninitialized"
    val Active = "active"
    val InTransaction = "inTransaction"
  }

  /**
    * The state of a bank account in time. This may be made private, but for convenience in testing I left it
    * sharable via asking the actor.
    * @param currentState the current transactional state.
    * @param balance the actual balance of the bank account.
    * @param pendingBalance the balance that reflects any pending transaction.
    */
  case class BankAccountState(
    currentState: String =  BankAccountStates.Uninitialized,
    balance: BigDecimal = 0,
    pendingBalance: BigDecimal = 0)

  /**
    * Factory method for BankAccount actor.
    * @return Props
    */
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

  /**
    * Here though the actor is instantiated, it is awaiting its first domain creation command to make it an
    * actual bank account.
    */
  def default: Receive = {
    case CreateBankAccount(customerId, accountNumber) =>
      persist(BankAccountCreated(customerId, accountNumber)) { _ =>
        log.info(s"Creating BankAccount with persistenceId $persistenceId")
        transitionToActive()
      }
  }

  /**
    * In the active state I am ready for a new transaction. This can be modified to handle non-transactional
    * behavior in addition if appropriate.
    */
  def active: Receive = {
    case StartTransaction(transactionId, cmd)  =>
      cmd match {
        case DepositFunds(accountNumber, amount) =>
          persist(Tagged(TransactionStarted(transactionId, FundsDeposited(accountNumber, amount)),
            Set(transactionId))) { evt =>
              state = state.copy(pendingBalance = state.balance + amount)
              transitionToInTransaction(evt.payload.asInstanceOf[TransactionalEventEnvelope])
          }
        case WithdrawFunds(accountNumber, amount) =>
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

  /**
    * When in a transaction I can only handle commits and rollbacs.
    * @param processing TransactionalEvent the event that was the start of this transaction.
    */
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
