// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*

/** A caller-supplied price per piece type, and the minimum price among the types present in an attacker set (#395).
  *
  * "The cheapest piece that attacks this square" is the classic input of a capture-threat or exchange heuristic: a
  * queen attacked by a pawn is in more trouble than a queen attacked by a rook. The *prices* are the consumer's
  * business — a safety-aware evaluator may deliberately value a knight above a rook, or price a king as an untouchable
  * sentinel — so this table carries whatever six integers the caller supplies and makes no ordering assumption. The
  * canonical engine scale [[MaterialValues]] is available as [[PieceValues.Canonical]] and is not changed by this
  * class.
  *
  * ## Why every present type is inspected
  *
  * A helper that walks the types in the familiar `Pawn < Knight = Bishop < Rook < Queen < King` order and stops at the
  * first present one is only correct for tables that happen to follow that order. [[minimum]] and [[minimumOrElse]]
  * inspect all six bits of the type mask and take the least price among those set, so an inverted or otherwise
  * non-monotone table yields the exact minimum. The mask is the one [[AttackerTypes.mask]] produces, so a diagonal
  * queen is priced as a queen, never as a bishop.
  *
  * ## Empty set
  *
  * When no type is present there is no minimum. [[minimum]] says so with `None`; the allocation-free [[minimumOrElse]]
  * returns the caller's own sentinel instead, which is the shape a hot evaluation loop wants (a sentinel above every
  * real price makes "nothing attacks" compare like "attacked by something unaffordable").
  *
  * ## Example
  *
  * ```scala
  * import dicechess.engine.domain.*
  * import dicechess.engine.search.{AttackerTypes, PieceValues}
  *
  * // The white queen on d5 and the white knight on b4 both attack the black pawn on c6.
  * val state  = FenParser.parse("4k3/8/2p5/3Q4/1N6/8/8/4K3 w - - 0 1").toOption.get
  * val target = Square.fromNotation("c6").get
  * val mask   = AttackerTypes.mask(state, target, Color.White)
  *
  * // On the canonical scale the knight is the cheaper attacker.
  * assert(PieceValues.Canonical.minimum(mask) == Some(300))
  *
  * // A consumer that prices knights above queens gets the queen — no ordering is assumed.
  * val inverted = PieceValues(pawn = 1, knight = 90, bishop = 30, rook = 50, queen = 20, king = 1000)
  * assert(inverted.minimum(mask) == Some(20))
  *
  * // Nothing attacks e8: the sentinel comes back, allocation-free.
  * val nothing = AttackerTypes.mask(state, Square.fromNotation("e8").get, Color.White)
  * assert(PieceValues.Canonical.minimum(nothing).isEmpty)
  * assert(PieceValues.Canonical.minimumOrElse(nothing, empty = Int.MaxValue) == Int.MaxValue)
  *
  * // Composed from a square set directly, with the king deliberately priced as a sentinel.
  * val prices = PieceValues(100, 300, 300, 500, 900, king = 10_000)
  * assert(prices.minimumOn(state, Bitboard.fromSquare(Square.fromNotation("d5").get), empty = 10_000) == 900)
  * ```
  *
  * ## A generic consumer
  *
  * The composition a safety-aware evaluator needs, with its own prices and its own weight — none of which the engine
  * sees:
  *
  * ```scala
  * import dicechess.engine.domain.*
  * import dicechess.engine.movegen.MoveGenerator
  * import dicechess.engine.search.{AttackerTypes, PieceSafety, PieceValues}
  *
  * final class HangRisk(prices: PieceValues, weight: Double):
  *   /** Expected loss `side` is exposed to on its hanging pieces, weighted by the chance to roll an attacker. */
  *   def apply(state: GameState, side: Color): Double =
  *     var risk      = 0.0
  *     var remaining = PieceSafety.hangingSquares(state, side).value
  *     while remaining != 0L do
  *       val victim = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(remaining))
  *       val mask   = AttackerTypes.mask(state, victim, side.opponent)
  *       val price  = prices.of(state.mailbox(victim).pieceType)
  *       risk += price * AttackerTypes.availabilityProbability(mask) * weight
  *       remaining &= remaining - 1
  *     risk
  *
  *   /** The cheapest piece with which `side.opponent` attacks `target`, or `empty` when nothing does. */
  *   def cheapestAttacker(state: GameState, target: Square, side: Color, empty: Int): Int =
  *     prices.minimumOn(state, MoveGenerator.allAttackers(state, target, side.opponent), empty)
  *
  * val risk  = HangRisk(PieceValues.Canonical, weight = 1.0)
  * val state = FenParser.parse("4k3/8/2p5/3Q4/8/8/8/4K3 w - - 0 1").toOption.get
  * // The white queen on d5 hangs to the c6 pawn: 900 * P(roll shows a pawn) = 900 * 91 / 216.
  * assert(risk(state, Color.White) == 900 * (91.0 / 216))
  * assert(risk.cheapestAttacker(state, Square.fromNotation("d5").get, Color.White, empty = -1) == 100)
  * ```
  *
  * @param pawn
  *   the price of a pawn
  * @param knight
  *   the price of a knight
  * @param bishop
  *   the price of a bishop
  * @param rook
  *   the price of a rook
  * @param queen
  *   the price of a queen
  * @param king
  *   the price of a king; a consumer that never wants a king to be "the cheapest attacker" supplies a value above its
  *   other prices (or above its empty sentinel)
  */
final case class PieceValues(pawn: Int, knight: Int, bishop: Int, rook: Int, queen: Int, king: Int) derives CanEqual:

  /** The price of one piece type. */
  def of(pieceType: PieceType): Int =
    if pieceType == PieceType.Pawn then pawn
    else if pieceType == PieceType.Knight then knight
    else if pieceType == PieceType.Bishop then bishop
    else if pieceType == PieceType.Rook then rook
    else if pieceType == PieceType.Queen then queen
    else king

  /** The least price among the types set in `typeMask` (an [[AttackerTypes]] mask), or `None` when no type is set.
    *
    * Every present type is inspected; the table's ordering is irrelevant.
    */
  def minimum(typeMask: Int): Option[Int] =
    if (typeMask & AttackerTypes.All) == 0 then None
    else Some(minimumOrElse(typeMask, empty = 0))

  /** The least price among the types set in `typeMask`, or `empty` when no type is set. Allocation-free. */
  def minimumOrElse(typeMask: Int, empty: Int): Int =
    var found                                                                 = false
    var best                                                                  = empty
    inline def consider(inline pieceType: PieceType, inline price: Int): Unit =
      if AttackerTypes.contains(typeMask, pieceType) then
        if !found || price < best then
          best = price
          found = true
    consider(PieceType.Pawn, pawn)
    consider(PieceType.Knight, knight)
    consider(PieceType.Bishop, bishop)
    consider(PieceType.Rook, rook)
    consider(PieceType.Queen, queen)
    consider(PieceType.King, king)
    best

  /** [[minimumOrElse]] over the types standing on `squares` (see [[AttackerTypes.mask]]). */
  def minimumOn(state: GameState, squares: Bitboard, empty: Int): Int =
    minimumOrElse(AttackerTypes.mask(state, squares), empty)

object PieceValues:

  /** The engine's own centipawn scale, [[MaterialValues]], as a table. */
  val Canonical: PieceValues = PieceValues(
    pawn = MaterialValues.Pawn,
    knight = MaterialValues.Knight,
    bishop = MaterialValues.Bishop,
    rook = MaterialValues.Rook,
    queen = MaterialValues.Queen,
    king = MaterialValues.King
  )
