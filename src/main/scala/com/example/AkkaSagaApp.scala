package com.example

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

import scala.util.Properties

/**
  * This is the runtime app, which wraps the abstract BaseApp
  */
object AkkaSagaApp {
  def main(args: Array[String]): Unit = {
    val config = Properties.envOrNone("RP" + "_PLATFORM")  match {
      case Some(_) =>   ConfigFactory.load()
      case None =>      ConfigFactory.load("dev-application.conf")
    }

    implicit val system: ActorSystem = ActorSystem("akka-saga-app", config)

    val app: AkkaSagaApp = new AkkaSagaApp()
    app.run()
  }
}

class AkkaSagaApp(implicit system: ActorSystem) extends BaseApp()
