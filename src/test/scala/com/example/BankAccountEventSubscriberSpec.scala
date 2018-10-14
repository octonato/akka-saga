//package com.example
//
//import akka.actor.{ActorSystem, Props}
//import akka.testkit.{TestKit, TestProbe}
//import akka.util.Timeout
//import com.typesafe.config.ConfigFactory
//import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
//
//import scala.concurrent.duration._
//
//class BankAccountEventSubscriberSpec extends TestKit(ActorSystem("EventCheckerSpec",
//  ConfigFactory.parseString(BankAccountSpec.Config))) with WordSpecLike with Matchers with BeforeAndAfterAll {
//
//  override def afterAll: Unit = {
//    TestKit.shutdownActorSystem(system)
//  }
//
//  implicit val timeout = Timeout(5 seconds)
//
//  "the EventChecker" should {
//
//    import BankAccount._
//
//    val CustomerNumber = "customerNumber"
//    val TransactionId = "transactionId1"
//    val bankAccount1 = system.actorOf(Props(classOf[BankAccount]), "accountNumber1")
//    val bankAccount2 = system.actorOf(Props(classOf[BankAccount]), "accountNumber2")
//    val bankAccount3 = system.actorOf(Props(classOf[BankAccount]), "accountNumber3")
//    val probe1 = TestProbe()
//    val probe2 = TestProbe()
//    val probe3 = TestProbe()
//
//    "properly pick up latest events for all entities on initial pass" in {
//      bankAccount1 ! CreateBankAccount(CustomerNumber, "accountNumber1")
//      bankAccount2 ! CreateBankAccount(CustomerNumber, "accountNumber2")
//      bankAccount3 ! CreateBankAccount(CustomerNumber, "accountNumber3")
//
//      probe1.send(bankAccount1, GetBankAccountState)
//      probe1.expectMsg(BankAccountState("active", 0, 0))
//      probe2.send(bankAccount1, GetBankAccountState)
//      probe2.expectMsg(BankAccountState("active", 0, 0))
//      probe3.send(bankAccount1, GetBankAccountState)
//      probe3.expectMsg(BankAccountState("active", 0, 0))
//
//      val cmd1 = PendingTransaction(DepositFunds("accountNumber1", 10), TransactionId)
//      val cmd2 = PendingTransaction(DepositFunds("accountNumber2", 10), TransactionId)
//      val cmd3 = PendingTransaction(WithdrawFunds("accountNumber3", 10), TransactionId)
//
//      bankAccount1 ! cmd1
//      bankAccount2 ! cmd2
//      bankAccount3 ! cmd3
//    }
//  }
//}
