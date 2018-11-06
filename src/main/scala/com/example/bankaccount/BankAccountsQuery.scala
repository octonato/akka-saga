package com.example.bankaccount

import akka.actor.{Actor, ActorLogging, Props}
import com.example.PersistentSagaActor.TransactionalEventEnvelope
import com.example.TaggedEventSubscription

object BankAccountsQuery {

  case class BankAccountProjection(accountNumber: AccountNumber, balance: BigDecimal)
  case object GetBankAccountProjections
  case class BankAccountProjections(projections: Seq[BankAccountProjection])
  case object Stop

  def props: Props = Props(classOf[BankAccountsQuery])
}

/**
  * This is a query side in-memory projection of Bank Accounts. If there gets to be a lot of bank accounts
  * consider using Cassandra as the query store. This is not durable across restarts.
  */
class BankAccountsQuery extends Actor with TaggedEventSubscription with ActorLogging {

  import BankAccountEvents._
  import BankAccountsQuery._
  import TaggedEventSubscription._

  override val eventTag = BankAccount.EntityName

  subscribeToEvents()

  private var bankAccounts: Seq[BankAccountProjection] = Seq.empty

  def receive: Receive = {
    case EventConfirmed(event) =>
      event match {
        case event: BankAccountCreated =>
          bankAccounts = bankAccounts :+ BankAccountProjection(event.accountNumber, 0)
        case envelope: TransactionalEventEnvelope =>
          val adjustment = envelope.event match {
            case d: FundsDeposited => d.amount
            case w: FundsWithdrawn => -w.amount
          }
          val projection = bankAccounts.find(_.accountNumber == envelope.entityId).get
          val adjusted = projection.copy(balance = projection.balance + adjustment)
          bankAccounts = bankAccounts.filterNot(_.accountNumber == envelope.entityId) :+ adjusted
      }

    case GetBankAccountProjections =>
      sender() ! BankAccountProjections(bankAccounts)
    case Stop =>
      context.stop(self)
  }
}
