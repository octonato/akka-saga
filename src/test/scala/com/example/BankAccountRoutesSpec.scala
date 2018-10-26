package com.example

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import com.example.PersistentSagaActor.StartSaga

import scala.concurrent.duration._

class BankAccountRoutesSpec extends WordSpecLike
  with Matchers with ScalatestRouteTest with BankAccountRoutes with BeforeAndAfterAll{

  import BankAccountCommands._

  override implicit val timeout: Timeout = 5.seconds
  override val clusterListener: ActorRef = null // not testing this

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  var IdToReturn: String = ""
  override def transactionIdGenerator: TransactionIdGenerator = new TransactionIdGenerator {
    override def generateId: String = IdToReturn
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
      case cmd: StartSaga =>  bankAccountSagaRegionProbe.ref ! cmd
    }
  }))

  "The BankAccountRoutes" should {
    "return accepted with a post of the StartTransaction command and send the command to the saga region" in {

      IdToReturn = "transactionId1"

      val dto = StartBankAccountTransaction(
        Seq(
          DepositFundsDto("theAccountNumber", 2000)
        ),
        Seq(
          WithdrawFundsDto("theAccountNumber", 1000)
        )
      )

      Post("/bank-accounts", dto) ~> route ~> check {
        response.status should be(StatusCodes.Accepted)
      }

      val ExpectedCommands = Seq(
        DepositFunds("theAccountNumber", 2000),
        WithdrawFunds("theAccountNumber", 1000)
      )
      bankAccountSagaRegionProbe.expectMsg(StartSaga(transactionIdGenerator.generateId, ExpectedCommands))
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
