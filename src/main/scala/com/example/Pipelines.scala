//package com.example
//
//import java.util.UUID
//
//import akka.actor.{Actor, ActorRef, ActorSystem, Props, ReceiveTimeout, Stash}
//import akka.cluster.sharding.ClusterSharding
//import akka.persistence.PersistentActor
//
//import scala.concurrent.duration._
//
//class TransactionCoordinator extends Actor {
//
//
//}
//
///**
//  * A persistent actor taking part in a pipeline.
//  * @param region The shard region for this entity.
//  * @param persistenceId
//  */
//case class PersistentPipe (
//  region: ActorRef,
//  persistenceId: PersistenceId
//)
//
///**
//  * Represents an application taking part in a transaction, containing it's sequence of persistence ids taking part
//  * in the pipeline.
//  * @param applicationEndPoint the actor entry point into this application.
//  * @param persistenceIds Seq[PersistenceId] the sequence of persistent actor ids to act as a pipeline.
//  */
//case class ApplicationBoundary (
//  applicationEndPoint: ActorRef,
//  persistentPipes: Seq[PersistentPipe]
//) {
//
//  private var remaining: Seq[PersistentPipe] = persistentPipes
//
//  def nextPipe: Option[ActorRef] = {
//    val next = remaining.headOption
//    remaining = remaining.tail
//
//  }
//}
//
///**
//  * This represents a sequential transaction that may occur over application boundaries in a cluster.
//  * @param transactionId UUID unique id for transaction, may be used for correlation.
//  * @param replyTo ActorRef originator of request, receives final response(s).
//  * @param autoComplete Either[AutoCommit, AutoRollback] whether commit or rollback is automatic within a
//  *                     configured time.
//  * @param applicationBoundaries Seq[ApplicationBoundary] ordered sequence of applications taking part in this
//  *                              transaction.
//  */
//final case class PipelineTransaction (
//  transactionId: UUID = UUID.randomUUID(),
//  replyTo: ActorRef,
//  autoComplete: Either[AutoCommit, AutoRollback],
//  boundaries: Seq[ApplicationBoundary],
//) {
//
//  private var currentApplication: ApplicationBoundary = boundaries.head
//
//  //private var currentPipe: PersistenceId = pipes.head
//
//  /**
//    * Here we return either the next pipe in the current application, the next application, and if we are done
//    * we reply to the originator.
//    */
//  def nextActorRef()(implicit system: ActorSystem): ActorRef =
//    nextPipe match {
//      case Some(pipe) => pipe
//      case None => nextApplication match {
//        case Some(application) => application
//        case None => replyTo
//      }
//    }
//
//  private def nextPipe: Option[ActorRef] =
//    pipes.drop(pipes.indexOf(currentPipe)) match {
//      case Nil => None
//      case remaining => Some(actorRefFor(remaining.head))
//    }
//
//  private def nextApplication: Option[ActorRef] =
//    applications.drop(applications.indexOf(currentApplication)) match {
//      case Nil => None
//
//      case remaining => Some(remaining.head)
//    }
//
//  private def actorRefFor(persistenceId: PersistenceId)(implicit system: ActorSystem): ActorRef =
//    ClusterSharding(system).shardRegion(persistenceId.split("-").drop(1).head)
//}
//
//object PipelinePersistenceActor {
//
//  final case class PipelineCommand(cmd: Any)
//
//  final case class PipelineCommit(transactionId: UUID)
//
//  final case class PipelineRollback(transactionId: UUID)
//}
//
//abstract class PipelinePersistenceActor(implicit transaction: PipelineTransaction) extends PersistentActor with Stash {
//
//  import PipelinePersistenceActor._
//
//  context.setReceiveTimeout(10.seconds)
//
//  final override def receive: Receive = available
//
//  /**
//    * The available state. Here we await new transaction pipelines.
//    */
//  def available: Receive = {
//
//    case PipelineCommand(cmd) =>
//      if (validateCommand(cmd)) {
//        context.system.eventStream.subscribe(self, classOf[PipelineCommit])
//        context.system.eventStream.subscribe(self, classOf[PipelineRollback])
//        context.become(inTransaction)
//        pipelineCommandHandler(cmd)
//      }
//      else
//        transaction.replyTo ! s"Invalid Command ${cmd.toString}"
//  }
//
//  /**
//    * The in-transaction state. Here we await a commit or rollback and in a timeout situation default to one
//    * or the other.
//    */
//  def inTransaction: Receive = {
//
//    case PipelineCommit(transactionId) =>
//      pipelineCommitHandler()
//      context.become(available)
//
//    case PipelineRollback(transactionId) =>
//      pipelineRollbackHandler()
//      context.become(available)
//
//    case ReceiveTimeout =>
//      if (transaction.autoCommit)
//        pipelineCommitHandler()
//      else
//        pipelineRollbackHandler()
//
//      context.become(available)
//
//    case _ =>
//      stash()
//  }
//
//  def validateCommand(cmd: Any): Boolean
//
//  def pipelineCommandHandler(cmd: Any): Unit
//
//  def pipelineCommitHandler(): Unit
//
//  def pipelineRollbackHandler(): Unit
//}
//
//object CreditIncreasePipe {
//
//  def props(message: String, printerActor: ActorRef): Props = Props(new Greeter(message, printerActor))
//
//  final case class WhoToGreet(who: String)
//  case object Greet
//}
