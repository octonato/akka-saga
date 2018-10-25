package com.example

object BankAccountEvents {

  case class BankAccountCreated(customerId: String, accountNumber: String) extends BankAccountEvent

  case class FundsDepositedPending(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransaction

  case class FundsDepositedReversal(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransaction

  case class FundsDeposited(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransaction

  case class InsufficientFunds(accountNumber: String, transactionId: String, balance: BigDecimal, attemptedWithdrawal: BigDecimal)
    extends BankAccountException

  case class FundsWithdrawnPending(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransaction

  case class FundsWithdrawnReversal(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransaction

  case class FundsWithdrawn(accountNumber: String, transactionId: String, amount: BigDecimal)
    extends BankAccountTransaction

  trait BankAccountEvent {
    def accountNumber: String
  }

  trait BankAccountTransaction extends BankAccountEvent {
    def transactionId: String
    def amount: BigDecimal
  }

  trait BankAccountException extends BankAccountEvent {
    def transactionId: String
  }
}
