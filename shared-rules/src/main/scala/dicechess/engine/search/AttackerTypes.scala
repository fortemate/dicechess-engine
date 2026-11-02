// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator

/** Exact piece-*type* view of an attacker set, and the single-roll dice availability it implies (#395).
  *
  * Dice Chess ties every move to a die: a piece may only move when one of the three rolled dice shows its type. A
  * threat therefore has two independent components — the *geometry* (which squares attack the target, answered by
  * [[dicechess.engine.movegen.MoveGenerator.allAttackers]]) and the *dice* (whether the mover can roll any of the
  * attacking types this turn). Only the second depends on the distinct piece **types** among the attackers, not on how
  * many attackers of each type there are, and this object is the canonical reduction from squares to types.
  *
  * ## Layout of a type mask
  *
  * A type mask is an `Int` with one bit per [[dicechess.engine.domain.PieceType]] at `diceValue - 1`, so it is also a
  * mask of the die faces that serve the attack:
  *
  * | Bit | Type   | Die |
  * |----:|:-------|----:|
  * |   0 | Pawn   |   1 |
  * |   1 | Knight |   2 |
  * |   2 | Bishop |   3 |
  * |   3 | Rook   |   4 |
  * |   4 | Queen  |   5 |
  * |   5 | King   |   6 |
  *
  * Bits above 5 are never set by this object and are ignored by [[count]], [[availableRolls]] and
  * [[availabilityProbability]]. The layout is shared with the private die mask of [[KingCaptureProbability]] and with
  * [[PlayableDice]].
  *
  * ## The queen rule
  *
  * [[dicechess.engine.movegen.MoveGenerator.allAttackers]] deliberately merges queens into both slider geometries, so a
  * queen on a diagonal lands in the "bishop-like" attack set and a queen on a file in the "rook-like" one. A mask built
  * from that square set must therefore intersect it with each exact piece-type bitboard (`state.pawns` through
  * `state.kings`): a diagonal queen sets only the Queen bit, never the Bishop bit, because only a 5 lets her move.
  * [[mask]] does exactly that, and the property tests pin it against an independent mailbox scan.
  *
  * ## Availability
  *
  * With `k` distinct attacking types, a single ordered roll of three dice misses all of them only when every die shows
  * one of the other `6 - k` faces, so exactly `216 - (6 - k)^3` of the [[DiceRolls.totalOrderedRolls]] ordered rolls
  * make the threat collectable. [[availableRolls]] returns that integer numerator and [[availabilityProbability]] the
  * normalised value; both are [[DiceRolls.rollsShowingAnyOf]] and [[DiceRolls.probabilityOfAnyOf]] applied to
  * [[count]]. What a consumer does with the probability — weight a hanging-piece penalty, scale a capture threat, gate
  * a tactical bonus — stays in the consumer: this artifact carries facts, not weights.
  *
  * ## Example
  *
  * ```scala
  * import dicechess.engine.domain.*
  * import dicechess.engine.search.{AttackerTypes, DiceRolls}
  *
  * // A white queen on d5 attacks the black pawn on c6 diagonally; the white knight on b4 attacks it too.
  * val state  = FenParser.parse("4k3/8/2p5/3Q4/1N6/8/8/4K3 w - - 0 1").toOption.get
  * val target = Square.fromNotation("c6").get
  * val mask   = AttackerTypes.mask(state, target, Color.White)
  *
  * assert(AttackerTypes.contains(mask, PieceType.Queen))
  * assert(AttackerTypes.contains(mask, PieceType.Knight))
  * assert(!AttackerTypes.contains(mask, PieceType.Bishop)) // the queen is not a bishop
  * assert(AttackerTypes.count(mask) == 2)
  * assert(AttackerTypes.availableRolls(mask) == 216 - 4 * 4 * 4)
  * assert(AttackerTypes.availabilityProbability(mask) == 152.0 / DiceRolls.totalOrderedRolls)
  *
  * // Nothing attacks the black king on e8.
  * val safe = AttackerTypes.mask(state, Square.fromNotation("e8").get, Color.White)
  * assert(safe == AttackerTypes.Empty && AttackerTypes.availableRolls(safe) == 0)
  * ```
  */
object AttackerTypes:

  /** The mask with no type set: no attacker, no serving die. */
  val Empty: Int = 0

  /** The mask with all six types set — every roll serves it. */
  val All: Int = 0x3f

  /** The bit of `pieceType` inside a type mask: `1 << (diceValue - 1)`, the die face that moves the piece. */
  inline def bit(pieceType: PieceType): Int = 1 << (pieceType.diceValue - 1)

  /** The exact set of piece types standing on `squares`, as a type mask.
    *
    * `squares` is usually an attacker set from [[dicechess.engine.movegen.MoveGenerator.allAttackers]], but any square
    * set works: each occupied square contributes the bit of the piece actually standing there, so a queen contributes
    * the Queen bit however it attacks. Empty squares contribute nothing.
    */
  def mask(state: GameState, squares: Bitboard): Int =
    var result = Empty
    if !(squares & state.pawns).isEmpty then result |= bit(PieceType.Pawn)
    if !(squares & state.knights).isEmpty then result |= bit(PieceType.Knight)
    if !(squares & state.bishops).isEmpty then result |= bit(PieceType.Bishop)
    if !(squares & state.rooks).isEmpty then result |= bit(PieceType.Rook)
    if !(squares & state.queens).isEmpty then result |= bit(PieceType.Queen)
    if !(squares & state.kings).isEmpty then result |= bit(PieceType.King)
    result

  /** The exact set of piece types with which `attackerColor` attacks `target`.
    *
    * Composes [[dicechess.engine.movegen.MoveGenerator.allAttackers]] with [[mask]], so the result reflects the static
    * attack geometry of the position regardless of whose turn it is or which dice are in the pool. Whether the target
    * is defended plays no role: this is the attacking side's view, and a defender term is a second, independent call
    * with the colours swapped.
    */
  def mask(state: GameState, target: Square, attackerColor: Color): Int =
    mask(state, MoveGenerator.allAttackers(state, target, attackerColor))

  /** The number of distinct types in `typeMask` — the `k` of the availability formula. */
  inline def count(typeMask: Int): Int = Integer.bitCount(typeMask & All)

  /** Whether `pieceType` is one of the types in `typeMask`. */
  inline def contains(typeMask: Int, pieceType: PieceType): Boolean = (typeMask & bit(pieceType)) != 0

  /** The number of ordered single rolls (out of [[DiceRolls.totalOrderedRolls]]) that show at least one of the types in
    * `typeMask`: `216 - (6 - k)^3` for `k = count(typeMask)`.
    */
  inline def availableRolls(typeMask: Int): Int = DiceRolls.rollsShowingAnyOf(count(typeMask))

  /** [[availableRolls]] normalised to a probability in `[0.0, 1.0]`. */
  inline def availabilityProbability(typeMask: Int): Double = DiceRolls.probabilityOfAnyOf(count(typeMask))
