// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

/** A turn's dice after drawing, before the board engine sees the playable pool. */
sealed trait PreparedRoll

object PreparedRoll:
  final class Pool private[domain] (val dice: List[Int]) extends PreparedRoll

  /** Ordered pairs stay distinct even when they have the same faces. */
  final class Offers private[domain] (val first: List[Int], val second: List[Int]) extends PreparedRoll:
    /** Select offer index 0 or 1 before playing. Either offer is valid even if it leads to a forced pass. */
    def select(index: Int): Either[String, Selection] =
      index match
        case 0 => Right(new Selection(this, index, first))
        case 1 => Right(new Selection(this, index, second))
        case _ => Left(s"Offer index must be 0 or 1, got $index")

  final class Selection private[domain] (val offers: Offers, val index: Int, val dice: List[Int])
