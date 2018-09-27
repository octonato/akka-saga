package com.example

import akka.actor._
import akka.cluster._
import ClusterEvent._

/**
  * Companion.
  */
object SimpleClusterListener {
  case object GetMembers
  case class MemberList(members: List[String])
}

/**
  * The purpose of this listener is to allow any http node to be hit and to reply with a list of nodes.
  */
class SimpleClusterListener extends Actor with ActorLogging {
  import SimpleClusterListener._

  val cluster = Cluster(context.system)

  private var members = List.empty[String]

  override def preStart(): Unit = {
    cluster.subscribe(
      self,
      initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent],
      classOf[UnreachableMember])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  override def receive: Receive = {
    case GetMembers =>
      sender() ! MemberList(members)

    case MemberUp(member) =>
      log.info("Member is Up: {}", member.address)

      members = member.address.toString :: members

    case UnreachableMember(member) =>
      log.info("Member detected as unreachable: {}", member)

    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}", member.address, previousStatus)

      members = members.filterNot(_ == member.address.toString)

    case _: MemberEvent =>
  }
}
