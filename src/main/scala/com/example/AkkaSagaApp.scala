package com.example

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

import scala.util.Properties

object AkkaSagaApp {
  def main(args: Array[String]): Unit = {
    val config = Properties.envOrNone("RP" + "_PLATFORM")  match {
      case Some(_) =>   ConfigFactory.load()
      case None =>      ConfigFactory.load("dev-application.conf")
    }

    val system: ActorSystem = ActorSystem("akka-saga-app", config)

    val app: AkkaSagaApp = new AkkaSagaApp(system)
    app.run()
  }
}

class AkkaSagaApp(system: ActorSystem) extends BaseApp(system)