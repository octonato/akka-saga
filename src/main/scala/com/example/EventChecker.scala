package com.example

import akka.NotUsed
import akka.actor.ActorRefFactory
import akka.persistence.query.{EventEnvelope, Offset}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.example.BankAccount.{BankAccountTransactionPending, _}
import com.example.EventChecker.EventResults

object EventChecker {

  case class EventResults(pendingConfirmed: Seq[String], commitConfirmed: Seq[String], rollbackConfirmed: Seq[String],
                          exceptions: Seq[InsufficientFunds])
}

/**
  * Utility to get the last events for a series of persistent ids.
  */
class EventChecker() {

  def checkEvents(transactionId: String, readJournal: LeveldbReadJournal, ids: Seq[String],
                  pendingConfirmed: Seq[String], commitConfirmed: Seq[String], rollbackConfirmed: Seq[String],
                  exceptions: Seq[InsufficientFunds])
                 (implicit arr: ActorRefFactory): EventResults = {

    //TODO: the sink foreach is not able to update my collections. Hrmph.
    implicit val mat = ActorMaterializer()

    var pc: Seq[String] = Seq.empty
    var cc: Seq[String] = Seq.empty
    var rc: Seq[String] = Seq.empty
    var ex: Seq[InsufficientFunds] = Seq.empty

    val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(transactionId, Offset.noOffset)

    source.map(_.event).runWith(Sink.foreach {
      case p: BankAccountTransactionPending    => pc = pc :+ p.accountNumber
      case c: BankAccountTransactionCommitted  => cc = cc :+ c.accountNumber
      case r: BankAccountTransactionRolledBack => rc = rc :+ r.accountNumber
      case e: InsufficientFunds                => ex = ex :+ e
    })

    // Bad I know...until I find another way. I probably need to wrap this in an actor.
    Thread.sleep(2000)

    EventResults(pc.union(pendingConfirmed), commitConfirmed.union(cc), rollbackConfirmed.union(rc),
      exceptions.union(ex))
  }
}
