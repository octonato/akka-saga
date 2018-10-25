package com.example

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{as, complete, entity, path, post}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import spray.json._
import BankAccountCommands._
import PersistentSagaActor._

/**
  * Json support for BankAccountHttpRoutes.
  */
trait BankAccountJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val createBankAccountFormat = jsonFormat2(CreateBankAccount)
  implicit val withdrawFundsFormat = jsonFormat3(WithdrawFunds)
  implicit val depositFundsFormat = jsonFormat3(DepositFunds)

  implicit val bankAccountTransactionalCommandFormat = new JsonFormat[BankAccountTransactionalCommand] {
    override def write(obj: BankAccountTransactionalCommand): JsValue = obj match {
        case w: WithdrawFunds => JsObject("accountNumber" -> w.accountNumber.toJson, "amount" -> w.amount.toJson,
          "transactionType" -> w.transactionType.toJson)
        case d: DepositFunds  => JsObject("accountNumber" -> d.accountNumber.toJson, "amount" -> d.amount.toJson,
          "transactionType" -> d.transactionType.toJson)
      }

    override def read(json: JsValue): BankAccountTransactionalCommand = json.asJsObject.fields.get("transactionType") match {
        case Some(JsString("WithdrawFunds")) => json.asJsObject.convertTo[WithdrawFunds]
        case Some(JsString("DepositFunds")) => json.asJsObject.convertTo[DepositFunds]
        case _ => throw new RuntimeException(s"Invalid json format: $json")
      }
  }

  implicit val startTransactionFormat = jsonFormat1(StartBankAccountTransaction)
}

/**
  * Makes it easier to test this thing. Using this we can assert a known value for transaction id at test time
  * and randomly generate them at runtime.
  */
trait TransactionIdGenerator {
  def generateId: String
}

/**
  * Runtime, default impl for above trait.
  */
class TransactionIdGeneratorImpl extends TransactionIdGenerator {
  override def generateId: String = UUID.randomUUID().toString
}

case class StartBankAccountTransaction(commands: Seq[BankAccountTransactionalCommand])

/**
  * Http routes for bank account.
  */
trait BankAccountRoutes extends BankAccountJsonSupport {

  def bankAccountSagaRegion: ActorRef
  def bankAccountRegion: ActorRef
  def clusterListener: ActorRef
  def transactionIdGenerator: TransactionIdGenerator = new TransactionIdGeneratorImpl

  implicit val system: ActorSystem
  implicit def timeout: Timeout

  val route: Route =
    path("bank-accounts") {
      post {
        entity(as[StartBankAccountTransaction]) { cmd =>
          val start = StartSaga(transactionIdGenerator.generateId, cmd.commands)
          bankAccountSagaRegion ! start
          complete(StatusCodes.Accepted, s"Transaction accepted with id: ${start.transactionId}")
        }
      } ~
      post {
        entity(as[CreateBankAccount]) { cmd =>
          bankAccountRegion ! cmd
          complete(StatusCodes.Accepted, s"CreateBankAccount accepted with number: ${cmd.accountNumber}")
        }
      }
    }
}
