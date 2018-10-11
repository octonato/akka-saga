package com.example

import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.example.BankAccount.{BankAccountTransaction, InsufficientFunds}

object BankAccountEventSubscriber {
  def props(transactionId: String): Props = Props(classOf[BankAccountEventSubscriber], transactionId)
}

/**
  * Utility actor to get the last events for a series of persistent ids.
  */
class BankAccountEventSubscriber(transactionId: String) extends Actor {

  private val bankAccountSagaRegion: ActorRef = ClusterSharding(context.system).shardRegion("BankAccountSaga")
  val readJournal = PersistenceQuery(context.system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
  val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(transactionId, Offset.noOffset)
  implicit val mat = ActorMaterializer()

  override def receive: Receive = {
    case _ => Map.empty
  }

  source.map(_.event).runForeach {
    case evt @  (_: BankAccountTransaction | InsufficientFunds) => bankAccountSagaRegion ! evt
  }
}
