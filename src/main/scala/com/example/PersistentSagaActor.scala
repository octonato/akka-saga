package com.example

import akka.NotUsed
import akka.actor.{ActorLogging, ActorRef, Props, ReceiveTimeout}
import akka.persistence.{PersistentActor, SnapshotOffer}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source

import scala.concurrent.duration._

/**
  * Companion object.
  */
object PersistentSagaActor {

  // Commands
  case class StartSaga(transactionId: TransactionId, commands: Seq[TransactionalCommand])
  case object GetBankAccountSagaState

  // Transactional command wrappers.
  case class StartTransaction(transactionId: TransactionId, command: TransactionalCommand)
  case class CommitTransaction(transactionId: TransactionId, entityId: EntityId)
  case class RollbackTransaction(transactionId: TransactionId, entityId: EntityId)

  // Transactional event wrappers.
  case class TransactionStarted(transactionId: TransactionId, event: TransactionalEvent) extends TransactionalEventEnvelope
  case class TransactionCleared(transactionId: TransactionId, event: TransactionalEvent) extends TransactionalEventEnvelope
  case class TransactionReversed(transactionId: TransactionId, event: TransactionalEvent) extends TransactionalEventEnvelope

  trait PersistentActorSagaCommand {
    def transactionId: TransactionId
  }

  // Trait for any entity commands participating in a saga.
  trait TransactionalCommand {
    def entityId: EntityId
  }

  // Trait for any entity events participating in a saga.
  trait TransactionalEvent {
    def entityId: EntityId
  }

  // Trait for any exception entity events.
  trait TransactionalExceptionEvent {
    def entityId: EntityId
  }

  // Envelope to wrap events.
  trait TransactionalEventEnvelope {
    def transactionId: TransactionId
    def event: TransactionalEvent
  }

  // States of a saga
  object SagaStates  {
    val Uninitialized = "uninitialized"
    val Pending = "pending"
    val Committing = "committing"
    val RollingBack = "rollingBack"
    val Complete = "complete"
  }

  import SagaStates._

  case class SagaState(
    transactionId: String,
    currentState: String = Uninitialized,
    commands: Seq[TransactionalCommand] = Seq.empty,
    pendingConfirmed: Seq[PersistenceId] = Seq.empty,
    commitConfirmed: Seq[PersistenceId] = Seq.empty,
    rollbackConfirmed: Seq[PersistenceId] = Seq.empty,
    exceptions: Seq[TransactionalExceptionEvent] = Seq.empty)

  /**
    * Props factory method.
    * @param persistentEntityRegion ActorRef shard region containing the entities.
    * @return Props
    */
  def props(persistentEntityRegion: ActorRef): Props =
    Props(classOf[PersistentSagaActor], persistentEntityRegion: ActorRef)
}

/**
  * This is effectively a long lived saga that operates within an Akka cluster. Classic saga patterns will be followed,
  * such as retrying rollback over and over as well as retry of transactions over and over if necessary, before
  * rollback.
  */
class PersistentSagaActor(persistentEntityRegion: ActorRef) extends PersistentActor with ActorLogging {

  import PersistentSagaActor._
  import SagaStates._

  override def persistenceId: String = self.path.name
  val transactionId = persistenceId

  /**
    * How long to stick around for reporting purposes after completion.
    */
  private val completedSagaTimeout: FiniteDuration = 5.minutes

  /**
    * Current state of the saga.
    */
  private var state: SagaState = SagaState(persistenceId)


  // Subscribe to event log for all events for this transaction and call self back with confirmed event.
  private case class TransactionalEventConfirmed(evt: TransactionalEvent)
  implicit private val materializer = ActorMaterializer()
  private val readJournal = PersistenceQuery(context.system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
  private val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(persistenceId, Offset.noOffset)

  source.map(_.event).runForeach {
    case evt: TransactionalEvent => self ! TransactionalEventConfirmed(evt)
  }

  final override def receiveCommand: Receive = uninitialized.orElse(stateReporting)

  /**
    * In this state we are hobbled until we are sent the start message. Instantiation of this actor has to be in two
    * steps since the edge, in this case the restful route, must assign the transactionId, which automatically
    * becomes the persistentId. Since cluster sharding only allows construction with objects known when the app
    * starts, we have to send the commands as a second step.
    */
  private def uninitialized: Receive = {
    case StartSaga(transactionId, commands) =>
      log.info(s"starting new saga with transactionId: $transactionId")
      state = state.copy(currentState = Pending, commands = commands)
      saveSnapshot(state)
      context.become(pending.orElse(stateReporting))

      commands.foreach { cmd =>
        persistentEntityRegion ! StartTransaction(state.transactionId, cmd)
      }
  }

  /**
    * The pending state. No commit OR rollback will occur until all pending events are in place, as per a Saga.
    */
  private def pending: Receive = {
    case TransactionalEventConfirmed(evt) =>
      evt match {
        case ex: TransactionalExceptionEvent =>
          if (!state.exceptions.contains(ex.entityId)) {
            state = state.copy(exceptions = state.exceptions :+ ex)
            val txt = s"Transaction rolling back when possible due to exception on account ${ex.entityId}."
            log.error(txt)
            saveSnapshot(state)
            pendingTransitionCheck()
          }

        case evt: TransactionalEvent =>
          if (!state.pendingConfirmed.contains(evt.entityId)) {
            state = state.copy(pendingConfirmed = state.pendingConfirmed :+ evt.entityId)
            saveSnapshot(state)
            pendingTransitionCheck()
          }
      }
  }

  /**
    * The committing state. When in this state we can only repeatedly attempt to commit. This transaction will remain
    * alive until commits have occurred across the board.
    */
  private def committing: Receive = {
    case TransactionalEventConfirmed(evt) =>
      if (!state.commitConfirmed.contains(evt.entityId)) {
        state = state.copy(commitConfirmed = state.commitConfirmed :+ evt.entityId)
        saveSnapshot(state)
      }

      // Check if done here
      if (state.commitConfirmed.size == state.commands.size) {
        log.info(s"Bank account saga completed successfully for transactionId: ${state.transactionId}")
        state = state.copy(currentState = Complete)
        saveSnapshot(state)
        context.setReceiveTimeout(completedSagaTimeout)
        context.become(stateReporting) // Stick around for a bit for the sake of reporting.
      }
  }

  /**
    * The rolling back state. When in this state we can only repeatedly attempt to rollback. This transaction will remain
    * alive until rollbacks have occurred across the board.
    */
  private def rollingBack: Receive = {
    case TransactionalEventConfirmed(evt) =>
      if (!state.commitConfirmed.contains(evt.entityId)) {
        state = state.copy(rollbackConfirmed = state.rollbackConfirmed :+ evt.entityId)
        saveSnapshot(state)
      }

      // Check if done here
      if (state.rollbackConfirmed.size == state.commands.size - state.exceptions.size) {
        log.info(s"Bank account saga rolled back successfully for transactionId: ${state.transactionId}")
        state = state.copy(currentState = Complete)
        saveSnapshot(state)
        context.setReceiveTimeout(completedSagaTimeout)
        context.become(stateReporting) // Stick around for a bit for the sake of reporting.
      }
  }

  /**
    * Transition from pending to either commit or rollback if possible.
    */
  private def pendingTransitionCheck(): Unit = {
    if (state.pendingConfirmed.size + state.exceptions.size == state.commands.size)
      if (state.exceptions.isEmpty) {
        // Transition to commit.
        state = state.copy(currentState = Committing)
        saveSnapshot(state)
        context.become(committing.orElse(stateReporting))

        state.commands.foreach( c =>
          persistentEntityRegion ! CommitTransaction(c.entityId, state.transactionId)
        )
      }
      else {
        // Transition to rollback.
        state = state.copy(currentState = RollingBack)
        saveSnapshot(state)
        context.become(rollingBack.orElse(stateReporting))

        state.pendingConfirmed.foreach( p =>
          persistentEntityRegion ! RollbackTransaction(transactionId, p)
        )
      }
  }

  /**
    * Report current state for ease of testing.
    */
  private def stateReporting: Receive = {
    case GetBankAccountSagaState => sender() ! state
    case ReceiveTimeout =>
      // It is possible for this saga to be started just for state reporting, so let's not stay in memory.
      context.stop(self)
  }

  final override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot: SagaState) =>
      state = snapshot

      state.currentState match {
        case Uninitialized => context.become(uninitialized.orElse(stateReporting))
        case Pending       => context.become(pending.orElse(stateReporting))
        case Committing    => context.become(rollingBack.orElse(stateReporting))
        case RollingBack   => context.become(committing.orElse(stateReporting))
        case Complete      =>
          context.setReceiveTimeout(completedSagaTimeout)
          context.become(stateReporting)
      }
  }
}
