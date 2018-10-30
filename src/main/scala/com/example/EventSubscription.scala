package com.example

import akka.NotUsed
import akka.actor.Actor
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.example.PersistentSagaActor.TransactionalEventEnvelope

/**
  * Companion.
  */
object EventSubscription {
  case class TransactionalEventConfirmed(envelope: TransactionalEventEnvelope)
}

/**
  * Mix this trait in to subscribe to entity events.
  */
trait EventSubscription { this: Actor =>

  import EventSubscription._

  def persistenceId: String

  implicit private val materializer = ActorMaterializer()
  private val readJournal = PersistenceQuery(context.system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
  private val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(persistenceId, Offset.noOffset)
  source.map(_.event).runForeach {
    case envelope: TransactionalEventEnvelope => self ! TransactionalEventConfirmed(envelope)
  }

}
