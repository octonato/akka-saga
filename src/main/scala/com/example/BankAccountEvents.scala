package com.example

import com.example.PersistentSagaActor.TransactionalEvent

object BankAccountEvents {

  case class BankAccountCreated(customerId: String, accountNumber: AccountNumber) extends BankAccountEvent

  case class FundsDeposited(accountNumber: AccountNumber, amount: BigDecimal) extends BankAccountTransactionalEvent {
    override val entityId: EntityId = accountNumber
  }

  case class FundsWithdrawn(accountNumber: AccountNumber, amount: BigDecimal) extends BankAccountTransactionalEvent {
    override val entityId: EntityId = accountNumber
  }

  case class InsufficientFunds(accountNumber: AccountNumber, balance: BigDecimal, attemptedWithdrawal: BigDecimal)
    extends BankAccountTransactionalExceptionEvent {
    override val entityId: EntityId = accountNumber
  }

  trait BankAccountEvent {
    def accountNumber: AccountNumber
  }
  trait BankAccountTransactionalEvent extends BankAccountEvent with TransactionalEvent {
    def amount: BigDecimal
  }

  trait BankAccountTransactionalExceptionEvent extends BankAccountEvent with TransactionalEvent
}
