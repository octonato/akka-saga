//package com.example
//
//import akka.cluster.Cluster
//import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
//import akka.http.scaladsl.Http
//import akka.http.scaladsl.marshalling.Marshal
//import akka.http.scaladsl.model.{HttpMethods, HttpRequest, RequestEntity}
//import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
//import akka.stream.ActorMaterializer
//import com.example.BankAccountCommands.CreateBankAccount
//import com.typesafe.config.ConfigFactory
//import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
//
//import scala.concurrent.duration._
//import scala.util.{Failure, Success}
//
//object BankAccountSagaMultiJvmSpecConfigJsonSupport extends BankAccountJsonSupport {}
//
//object BankAccountSagaMultiJvmSpecConfig extends MultiNodeConfig {
//
//  val node1 = role("node1")
//  val node2 = role("node2")
//  val node3 = role("node3")
//
//  nodeConfig(node1)(ConfigFactory.parseString(
//    """
//      |akka-saga.http-port = 8081
//      |akka.remote.netty.tcp.port = "2555"
//    """.stripMargin))
//
//  nodeConfig(node2)(ConfigFactory.parseString(
//    """
//      |akka-saga.http-port = 8082
//      |akka.remote.netty.tcp.port = "2556"
//    """.stripMargin))
//
//  nodeConfig(node3)(ConfigFactory.parseString(
//    """
//      |akka-saga.http-port = 8083
//      |akka.remote.netty.tcp.port = "2557"
//    """.stripMargin))
//
//  commonConfig(ConfigFactory.parseString(
//    """
//      |akka.loglevel=INFO
//      |akka.actor.provider = cluster
//      |akka.remote.netty.tcp.hostname = "127.0.0.1"
//      |akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
//      |akka.coordinated-shutdown.terminate-actor-system = off
//      |akka.cluster.run-coordinated-shutdown-when-down = off
//      |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
//      |akka.persistence.journal.leveldb.dir = "target/shared"
//      |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
//      |akka.persistence.snapshot-store.local.dir = "target/snapshots"
//      |akka-saga.bank-account.shard-count = 3
//      |akka-saga.bank-account-saga.shard-count = 3
//    """.stripMargin))
//}
//
//abstract class BankAccountSagaMultiJvmSpec
//  extends MultiNodeSpec(BankAccountSagaMultiJvmSpecConfig) with WordSpecLike with Matchers with BeforeAndAfterAll {
//
//  import BankAccountSagaMultiJvmSpecConfig._
//  import BankAccountSagaMultiJvmSpecConfigJsonSupport._
//
//  override def beforeAll() = multiNodeSpecBeforeAll()
//
//  override def afterAll() = multiNodeSpecAfterAll()
//
//  override def initialParticipants: Int = 3
//
//  "a BankAccountSaga application" should {
//
//    implicit val materializer = ActorMaterializer()
//    implicit val ec = system.dispatcher
//    val http = Http(system)
//
//    "blah blah" in within(15.seconds) {
//
//      new AkkaSagaApp()(system)
//
//      Cluster(system).subscribe(testActor, classOf[MemberUp])
//      expectMsgClass(classOf[CurrentClusterState])
//      Cluster(system) join node(node1).address
//
//      receiveN(3).collect { case MemberUp(m) => m.address }.toSet should be(
//        Set(node(node1).address, node(node2).address, node(node3).address)
//      )
//
//      testConductor.enter("all-up")
//
////      val Command = CreateBankAccount("customerId1", "accountNumber1")
////      Marshal(Command).to[RequestEntity] flatMap { entity =>
////        val request = HttpRequest(method = HttpMethods.POST, uri = "http://localhost:8081/bank-accounts", entity = entity)
////        http.singleRequest(request)
////      }
//
//      Thread.sleep(5000)
//    }
//  }
//}
//
//class BankAccountSagaMultiJvmSpecNode1 extends BankAccountSagaMultiJvmSpec
//class BankAccountSagaMultiJvmSpecNode2 extends BankAccountSagaMultiJvmSpec
//class BankAccountSagaMultiJvmSpecNode3 extends BankAccountSagaMultiJvmSpec
