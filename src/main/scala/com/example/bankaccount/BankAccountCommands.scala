package com.example.bankaccount

import com.example.PersistentSagaActor.TransactionalCommand
import com.example._

object BankAccountCommands {

  case class CreateBankAccount(customerId: String, accountNumber: AccountNumber) extends BankAccountCommand

  case class DepositFunds(accountNumber: AccountNumber, amount: BigDecimal)
    extends BankAccountTransactionalCommand {
    override val entityId: EntityId = accountNumber
  }

  case class WithdrawFunds(accountNumber: AccountNumber, amount: BigDecimal)
    extends BankAccountTransactionalCommand {
    override val entityId: EntityId = accountNumber
  }

  case class GetBankAccount(accountNumber: AccountNumber) extends BankAccountCommand

  case class GetBankAccountState(accountNumber: AccountNumber) extends BankAccountCommand

  trait BankAccountCommand {
    def accountNumber: String
  }

  trait BankAccountTransactionalCommand extends BankAccountCommand with TransactionalCommand {
    def amount: BigDecimal
  }
}
