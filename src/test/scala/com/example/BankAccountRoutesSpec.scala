package com.example

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import com.example.BankAccount.{CreateBankAccount, WithdrawFunds}
import com.example.BankAccountSaga.StartTransaction

import scala.concurrent.duration._

class BankAccountRoutesSpec extends WordSpecLike
  with Matchers with ScalatestRouteTest with BankAccountHttpRoutes with BeforeAndAfterAll{

  override implicit val timeout: Timeout = 5.seconds
  override val clusterListener: ActorRef = null // not testing this

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  var IdToReturn: Option[String] = None
  override def transactionIdGenerator: TransactionIdGenerator = new TransactionIdGenerator {
    override def generateId: Option[String] = IdToReturn
  }

  // Mock bank account region and test probe
  val bankAccountRegionProbe: TestProbe = TestProbe()
  override val bankAccountRegion = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case cmd: CreateBankAccount => bankAccountRegionProbe.ref ! cmd
    }
  }))

  // Mock bank account saga region and test probe
  val bankAccountSagaRegionProbe: TestProbe = TestProbe()
  override val bankAccountSagaRegion = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case cmd: StartTransaction => bankAccountSagaRegionProbe.ref ! cmd
    }
  }))

  "The BankAccountRoutes" should {
    "return accepted with a post of the StartTransaction command and send the command to the saga region" in {

      IdToReturn = Some("transactionId1")

      val Command = StartTransaction(
        Seq(
          WithdrawFunds("theAccountNumber", BigDecimal.valueOf(1000l))
        )
      )

      Post("/bank-accounts", Command) ~> route ~> check {
        response.status should be(StatusCodes.Accepted)
      }

      bankAccountSagaRegionProbe.expectMsg(Command.copy(transactionId = IdToReturn))
    }

    "return accepted with a post of the CreateBankAccount command and send the command to the account region" in {

      val Command = CreateBankAccount("customerId1", "accountNumber1")

      Post("/bank-accounts", Command) ~> route ~> check {
        response.status should be(StatusCodes.Accepted)
      }


      bankAccountRegionProbe.expectMsg(Command)
    }
  }
}