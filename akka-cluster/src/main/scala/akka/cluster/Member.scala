/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.implicitConversions

import scala.collection.immutable
import scala.collection.GenTraversableOnce
import akka.actor.Address
import MemberStatus._

/**
 * Represents the address, current status, and roles of a cluster member node.
 *
 * Note: `hashCode` and `equals` are solely based on the underlying `Address`, not its `MemberStatus`
 * and roles.
 */
case class Member(val address: Address, val status: MemberStatus, roles: Set[String]) extends ClusterMessage {
  override def hashCode = address.##
  override def equals(other: Any) = other match {
    case m: Member ⇒ address == m.address
    case _         ⇒ false
  }
  override def toString = "Member(address = %s, status = %s)" format (address, status)

  def hasRole(role: String): Boolean = roles.contains(role)

  /**
   * Java API
   */
  def getRoles: java.util.Set[String] =
    scala.collection.JavaConverters.setAsJavaSetConverter(roles).asJava
}

/**
 * Module with factory and ordering methods for Member instances.
 */
object Member {

  val none = Set.empty[Member]

  /**
   * `Address` ordering type class, sorts addresses by host and port.
   */
  implicit val addressOrdering: Ordering[Address] = Ordering.fromLessThan[Address] { (a, b) ⇒
    // cluster node identifier is the host and port of the address; protocol and system is assumed to be the same
    if (a.host != b.host) a.host.getOrElse("").compareTo(b.host.getOrElse("")) < 0
    else if (a.port != b.port) a.port.getOrElse(0) < b.port.getOrElse(0)
    else false
  }

  /**
   * INTERNAL API
   * Orders the members by their address except that members with status
   * Joining, Exiting and Down are ordered last (in that order).
   */
  private[cluster] val leaderStatusOrdering: Ordering[Member] = Ordering.fromLessThan[Member] { (a, b) ⇒
    (a.status, b.status) match {
      case (as, bs) if as == bs ⇒ ordering.compare(a, b) <= 0
      case (Down, _)            ⇒ false
      case (_, Down)            ⇒ true
      case (Exiting, _)         ⇒ false
      case (_, Exiting)         ⇒ true
      case (Joining, _)         ⇒ false
      case (_, Joining)         ⇒ true
      case _                    ⇒ ordering.compare(a, b) <= 0
    }
  }

  /**
   * `Member` ordering type class, sorts members by host and port.
   */
  implicit val ordering: Ordering[Member] = new Ordering[Member] {
    def compare(a: Member, b: Member): Int = addressOrdering.compare(a.address, b.address)
  }

  def pickHighestPriority(a: Set[Member], b: Set[Member]): Set[Member] = {
    // group all members by Address => Seq[Member]
    val groupedByAddress = (a.toSeq ++ b.toSeq).groupBy(_.address)
    // pick highest MemberStatus
    (Member.none /: groupedByAddress) {
      case (acc, (_, members)) ⇒ acc + members.reduceLeft(highestPriorityOf)
    }
  }

  /**
   * Picks the Member with the highest "priority" MemberStatus.
   */
  def highestPriorityOf(m1: Member, m2: Member): Member = (m1.status, m2.status) match {
    case (Removed, _)       ⇒ m1
    case (_, Removed)       ⇒ m2
    case (Down, _)          ⇒ m1
    case (_, Down)          ⇒ m2
    case (Exiting, _)       ⇒ m1
    case (_, Exiting)       ⇒ m2
    case (Leaving, _)       ⇒ m1
    case (_, Leaving)       ⇒ m2
    case (Up, Joining)      ⇒ m2
    case (Joining, Up)      ⇒ m1
    case (Joining, Joining) ⇒ m1
    case (Up, Up)           ⇒ m1
  }

}

/**
 * Defines the current status of a cluster member node
 *
 * Can be one of: Joining, Up, Leaving, Exiting and Down.
 */
abstract class MemberStatus extends ClusterMessage

object MemberStatus {
  case object Joining extends MemberStatus
  case object Up extends MemberStatus
  case object Leaving extends MemberStatus
  case object Exiting extends MemberStatus
  case object Down extends MemberStatus
  case object Removed extends MemberStatus

  /**
   * Java API: retrieve the “joining” status singleton
   */
  def joining: MemberStatus = Joining

  /**
   * Java API: retrieve the “up” status singleton
   */
  def up: MemberStatus = Up

  /**
   * Java API: retrieve the “leaving” status singleton
   */
  def leaving: MemberStatus = Leaving

  /**
   * Java API: retrieve the “exiting” status singleton
   */
  def exiting: MemberStatus = Exiting

  /**
   * Java API: retrieve the “down” status singleton
   */
  def down: MemberStatus = Down

  /**
   * Java API: retrieve the “removed” status singleton
   */
  def removed: MemberStatus = Removed
}
