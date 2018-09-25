package com.example

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.example.BankAccount.{BankAccountTransactionalCommand, CreateBankAccount}
import com.example.BankAccountSaga.StartTransaction
import spray.json.{DefaultJsonProtocol, JsValue, RootJsonFormat}

import scala.concurrent.{ExecutionContext, Future}

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit object BankAccountTransactionalCommandFormat extends RootJsonFormat[BankAccountTransactionalCommand] {
    override def read(json: JsValue): BankAccountTransactionalCommand = ???

    override def write(t: BankAccountTransactionalCommand): JsValue = ???
  }

  implicit val startTransactionFormat = jsonFormat2(StartTransaction)
  implicit val createBankAccountFormat = jsonFormat2(CreateBankAccount)
}

class BankAccountHttpServer(
  host: String,
  port: Int,
  bankAccountRegion: ActorRef,
  bankAccountSagaRegion: ActorRef
)(implicit system: ActorSystem) extends JsonSupport {

  val route: Route =
    path("bank-account") {
      get {
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Hello!</h1"))
      } ~
      post {
        entity(as[StartTransaction]) { cmd =>
          val withId = cmd.copy(transactionId = Some(UUID.randomUUID().toString))
          bankAccountSagaRegion ! withId
          complete(StatusCodes.Accepted, s"Transaction accepted with id: ${withId.transactionId.get}")
        }
      }~
      post {
        entity(as[CreateBankAccount]) { cmd =>
          bankAccountRegion ! cmd
          complete(StatusCodes.Accepted, s"CreateBankAccount accepted with number: ${cmd.accountNumber}")
        }
      }
    }

  implicit val dispatcher: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val binding: Future[Http.ServerBinding] =
    Http().bindAndHandle(route, host, port)
}
