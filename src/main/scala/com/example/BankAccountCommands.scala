package com.example

import com.example.BankAccount.{DepositFundsTransactionType, WithdrawFundsTransactionType}
import com.example.PersistentSagaActor.TransactionalCommand

object BankAccountCommands {

  case class CreateBankAccount(customerId: String, accountNumber: AccountNumber) extends BankAccountCommand

  case class DepositFunds(accountNumber: AccountNumber, amount: BigDecimal,
    final val transactionType: String = DepositFundsTransactionType) extends BankAccountTransactionalCommand

  case class WithdrawFunds(accountNumber: AccountNumber, amount: BigDecimal,
    final val transactionType: String = WithdrawFundsTransactionType) extends BankAccountTransactionalCommand

  case class GetBankAccount(accountNumber: AccountNumber) extends BankAccountCommand

  case class GetBankAccountState(accountNumber: AccountNumber) extends BankAccountCommand

  trait BankAccountCommand {
    def accountNumber: String
  }

  trait BankAccountTransactionalCommand extends TransactionalCommand with BankAccountCommand {
    def amount: BigDecimal
    def transactionType: String
    override val entityId: EntityId = accountNumber
  }
}
