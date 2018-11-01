package com.example

import akka.NotUsed
import akka.actor.Actor
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source

/**
  * Companion.
  */
object TaggedEventSubscription {
  case class EventConfirmed(event: Any)
}

/**
  * Mix this trait in to subscribe to entity events.
  */
trait TaggedEventSubscription { this: Actor =>

  import TaggedEventSubscription._

  def eventTag: String

  protected def subscribeToEvents(): Unit = {
    if (context.system.settings.config.getString("akka.persistence.journal.plugin").contains("cassandra"))
      subscribeToCassandraEvents()
    else
      subscribeToLevelDbEvents()
  }

  private def subscribeToLevelDbEvents(): Unit = {
    implicit val materializer = ActorMaterializer()
    val readJournal = PersistenceQuery(context.system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
    val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(eventTag, Offset.noOffset)
    source.map(_.event).runForeach {
      case event => self ! EventConfirmed(event)
    }
  }

  private def subscribeToCassandraEvents(): Unit = {
    implicit val materializer = ActorMaterializer()
    val readJournal = PersistenceQuery(context.system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(eventTag, Offset.noOffset)
    source.map(_.event).runForeach {
      case event => self ! EventConfirmed(event)
    }
  }
}
