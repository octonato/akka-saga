package com.example

import com.example.BankAccount.{DepositFundsTransactionType, WithdrawFundsTransactionType}

object BankAccountCommands {

  case class CreateBankAccount(customerId: String, accountNumber: String) extends BankAccountCommand

  case class DepositFunds(accountNumber: String, amount: BigDecimal,
    final val transactionType: String = DepositFundsTransactionType) extends BankAccountTransactionalCommand

  case class WithdrawFunds(accountNumber: String, amount: BigDecimal,
    final val transactionType: String = WithdrawFundsTransactionType) extends BankAccountTransactionalCommand

  case class GetBankAccount(accountNumber: String) extends BankAccountCommand

  case object GetBankAccountState

  trait BankAccountCommand {
    def accountNumber: String
  }

  trait BankAccountTransactionalCommand extends BankAccountCommand {
    def amount: BigDecimal
    def transactionType: String
  }
}
