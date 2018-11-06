package com.example

import akka.actor.{ActorLogging, ActorRef, Props, ReceiveTimeout}
import akka.persistence.{PersistentActor, SnapshotOffer}

import scala.concurrent.ExecutionContext
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
  case class TransactionStarted(transactionId: TransactionId, entityId: EntityId, event: TransactionalEvent) extends TransactionalEventEnvelope
  case class TransactionException(transactionId: TransactionId, entityId: EntityId, event: TransactionalEvent) extends TransactionalEventEnvelope
  case class TransactionCleared(transactionId: TransactionId, entityId: EntityId, event: TransactionalEvent) extends TransactionalEventEnvelope
  case class TransactionReversed(transactionId: TransactionId, entityId: EntityId, event: TransactionalEvent) extends TransactionalEventEnvelope

  trait PersistentActorSagaCommand {
    def transactionId: TransactionId
  }

  // Trait for any entity commands participating in a saga.
  trait TransactionalCommand {
    def entityId: EntityId
  }

  // The base trait for any event for an entity as part of a saga.
  trait TransactionalEntityEvent

  // Trait for any entity events participating in a saga.
  trait TransactionalEvent extends TransactionalEntityEvent

  // Envelope to wrap events.
  trait TransactionalEventEnvelope {
    def transactionId: TransactionId
    def entityId: EntityId
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
    exceptions: Seq[TransactionalEventEnvelope] = Seq.empty)

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
class PersistentSagaActor(persistentEntityRegion: ActorRef)
  extends PersistentActor with TaggedEventSubscription with ActorLogging {

  import PersistentSagaActor._
  import SagaStates._
  import TaggedEventSubscription._

  implicit def ec: ExecutionContext = context.system.dispatcher

  override def persistenceId: String = self.path.name
  val transactionId = persistenceId
  override val eventTag = transactionId

  /**
    * How long to stick around for reporting purposes after completion.
    */
  private val keepAliveAfterCompletion: FiniteDuration =
    Duration.fromNanos(context.system.settings.config
      .getDuration("akka-saga.bank-account.saga.keep-alive-after-completion").toNanos())

  /**
    * How often to retry transactions on an entity when no confirmation received.
    */
  private val retryAfter: FiniteDuration =
    Duration.fromNanos(context.system.settings.config
      .getDuration("akka-saga.bank-account.saga.retry-after").toNanos())

  private case object Retry

  /**
    * Current state of the saga.
    */
  private var state: SagaState = SagaState(persistenceId)

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
      context.system.scheduler.scheduleOnce(retryAfter, self, Retry)
      subscribeToEvents()

      commands.foreach { cmd =>
        persistentEntityRegion ! StartTransaction(state.transactionId, cmd)
      }
  }

  /**
    * The pending state. No commit OR rollback will occur until all pending events are in place, as per a Saga.
    * Here we receive event subscription messages applicable to "pending".
    */
  private def pending: Receive = {
    case EventConfirmed(payload) =>
      payload match {
        case envelope: TransactionalEventEnvelope =>
          envelope match {
            case _: TransactionStarted =>
              if (!state.pendingConfirmed.contains(envelope.entityId)) {
                state = state.copy(pendingConfirmed = state.pendingConfirmed :+ envelope.entityId)
                saveSnapshot(state)
                pendingTransitionCheck()
              }
            case _: TransactionException =>
              if (!state.exceptions.contains(envelope.entityId)) {
                state = state.copy(exceptions = state.exceptions :+ envelope)
                log.error(s"Transaction rolling back when possible due to exception on account ${envelope.entityId}.")
                saveSnapshot(state)
                pendingTransitionCheck()
              }
          }
        case _ => // Ignore non-transactional event.
      }

    case Retry =>
      state.commands.diff(state.pendingConfirmed).foreach( c => persistentEntityRegion ! c)
      context.system.scheduler.scheduleOnce(retryAfter, self, Retry)
  }

  /**
    * The committing state. When in this state we can only repeatedly attempt to commit. This transaction will remain
    * alive until commits have occurred across the board.
    * Here we receive event subscription messages applicable to "committing".
    */
  private def committing: Receive = {
    case EventConfirmed(payload) =>
      payload match {
        case envelope: TransactionalEventEnvelope =>
          if (!state.commitConfirmed.contains(envelope.entityId)) {
            state = state.copy(commitConfirmed = state.commitConfirmed :+ envelope.entityId)
            saveSnapshot(state)
          }

          // Check if done here
          if (state.commitConfirmed.size == state.commands.size) {
            log.info(s"Bank account saga completed successfully for transactionId: ${state.transactionId}")
            state = state.copy(currentState = Complete)
            saveSnapshot(state)
            context.setReceiveTimeout(keepAliveAfterCompletion)
            context.become(stateReporting.orElse {
              case ReceiveTimeout =>
                context.stop(self)
            })
          }
        case _ => // Ignore non-transactional event.
      }

    case Retry =>
      state.commands.diff(state.pendingConfirmed).foreach( c => persistentEntityRegion ! c)
      context.system.scheduler.scheduleOnce(retryAfter, self, Retry)
  }

  /**
    * The rolling back state. When in this state we can only repeatedly attempt to rollback. This transaction will remain
    * alive until rollbacks have occurred across the board.
    * Here we receive event subscription messages applicable to "rollingBack".
    */
  private def rollingBack: Receive = {
    case EventConfirmed(payload) =>
      payload match {
        case envelope: TransactionalEventEnvelope =>
          if (!state.commitConfirmed.contains(envelope.entityId)) {
            state = state.copy(rollbackConfirmed = state.rollbackConfirmed :+ envelope.entityId)
            saveSnapshot(state)
          }

          // Check if done here
          if (state.rollbackConfirmed.size == state.commands.size - state.exceptions.size) {
            log.info(s"Bank account saga rolled back successfully for transactionId: ${state.transactionId}")
            state = state.copy(currentState = Complete)
            saveSnapshot(state)
            context.setReceiveTimeout(keepAliveAfterCompletion)
            context.become(stateReporting.orElse {
              case ReceiveTimeout =>
                context.stop(self)
            })
          }
        case _ => // Ignore non-transactional event.
      }

    case Retry =>
      state.commands.diff(state.pendingConfirmed).foreach( c => persistentEntityRegion ! c)
      context.system.scheduler.scheduleOnce(retryAfter, self, Retry)
  }

  /**
    * Transition from pending to either commit or rollback if possible.
    */
  private def pendingTransitionCheck(): Unit = {
    if (state.pendingConfirmed.size + state.exceptions.size == state.commands.size) {
      if (state.exceptions.isEmpty) {
        // Transition to commit.
        state = state.copy(currentState = Committing)
        saveSnapshot(state)
        context.become(committing.orElse(stateReporting))

        state.commands.foreach(c =>
          persistentEntityRegion ! CommitTransaction(transactionId, c.entityId)
        )
      }
      else {
        // Transition to rollback.
        state = state.copy(currentState = RollingBack)
        saveSnapshot(state)
        context.become(rollingBack.orElse(stateReporting))

        state.pendingConfirmed.foreach(p =>
          persistentEntityRegion ! RollbackTransaction(transactionId, p)
        )
      }

      context.system.scheduler.scheduleOnce(retryAfter, self, Retry)
    }
  }

  /**
    * Report current state for ease of testing.
    */
  private def stateReporting: Receive = {
    case GetBankAccountSagaState => sender() ! state
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
          context.setReceiveTimeout(keepAliveAfterCompletion)
          context.become(stateReporting)
      }
  }
}
