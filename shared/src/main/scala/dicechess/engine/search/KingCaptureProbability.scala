package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import scala.util.boundary, boundary.break

/** Probabilistic king (and queen) capture analysis for Dice Chess.
  *
  * Given a position, enumerates all 216 possible 3d6 outcomes (grouped into 56 weighted multisets) and determines
  * whether the opponent can capture the defender's king (or queen) on their next turn.
  *
  * King-capture paths are always legal regardless of the Maximum Micro-moves Rule, so a depth-first search with early
  * exit yields exact probabilities. Queen-capture paths respect the Maximum Micro-moves Rule; the returned probability
  * may therefore be slightly overestimated in edge cases where a queen capture is not part of any max-length sequence.
  */
object KingCaptureProbability:

  /** Returns the probability `[0.0, 1.0]` that the opponent can capture the defender's king on their next turn. */
  def kingCaptureProbability(state: GameState, defenderColor: Color): Double =
    captureProbability(state, defenderColor, state.kings)

  /** Returns the probability `[0.0, 1.0]` that the opponent can capture a defender's queen on their next turn.
    *
    * This method applies the same DFS used for king capture and may slightly overestimate the true probability in edge
    * cases where a queen capture is not part of any max‑length micro‑move sequence (Maximum Micro‑moves Rule).
    */
  def queenCaptureProbability(state: GameState, defenderColor: Color): Double =
    captureProbability(state, defenderColor, state.queens)

  private def captureProbability(state: GameState, defenderColor: Color, targetBB: Bitboard): Double = boundary {
    val defenderPieces = if defenderColor.isWhite then state.whitePieces else state.blackPieces
    val targets        = targetBB & defenderPieces
    if targets.isEmpty then break(0.0)

    val opponent       = defenderColor.opponent
    val captureDieMask = directCaptureDiceMask(state, targets, opponent)
    var count          = 0
    var i              = 0
    val scratch        = KcpScratchBoard.fromGameState(state)
    val rootFlags      = state.flags.withActiveColor(opponent)

    while i < DiceRolls.weighted.length do
      val (rolls, weight) = DiceRolls.weighted(i)
      if captureDieMask != 0 && (captureDieMask & diceMask(rolls)) != 0 then count += weight
      else
        scratch.flags = rootFlags.withDicePool(rolls)
        if captureDFS(scratch, targets, GameFlags.DiceSlots) then count += weight
      i += 1
    count.toDouble / DiceRolls.totalOrderedRolls
  }

  /** Dice for piece types that can capture any target in one micro-move from the root position.
    *
    * [[MoveGenerator.allAttackers]] intentionally merges queens into both slider geometries, so its result must be
    * intersected with each exact piece-type bitboard. Otherwise a diagonal queen would also enable a Bishop die, and an
    * orthogonal queen would enable a Rook die. Every target is inspected because promotion can create several queens of
    * the defended color.
    */
  private def directCaptureDiceMask(state: GameState, targets: Bitboard, attackerColor: Color): Int =
    var mask             = 0
    var remainingTargets = targets.value
    while remainingTargets != 0L do
      val target    = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(remainingTargets))
      val attackers = MoveGenerator.allAttackers(state, target, attackerColor)

      if !(attackers & state.pawns).isEmpty then mask |= dieBit(PieceType.Pawn)
      if !(attackers & state.knights).isEmpty then mask |= dieBit(PieceType.Knight)
      if !(attackers & state.bishops).isEmpty then mask |= dieBit(PieceType.Bishop)
      if !(attackers & state.rooks).isEmpty then mask |= dieBit(PieceType.Rook)
      if !(attackers & state.queens).isEmpty then mask |= dieBit(PieceType.Queen)
      if !(attackers & state.kings).isEmpty then mask |= dieBit(PieceType.King)

      remainingTargets &= remainingTargets - 1
    mask

  private inline def dieBit(pieceType: PieceType): Int = 1 << (pieceType.diceValue - 1)

  private def diceMask(dice: List[Int]): Int =
    var mask      = 0
    var remaining = dice
    while remaining.nonEmpty do
      mask |= 1 << (remaining.head - 1)
      remaining = remaining.tail
    mask

  /** Depth‑first search over all micro‑move sequences using an in-place mutable [[KcpScratchBoard]].
    *
    * Returns `true` as soon as '''any''' move in any sequence lands on a square in `targets`. Because king‑capture
    * paths are always legal regardless of the Maximum Micro‑moves Rule, an early exit is correct for kings. For queens
    * the result may slightly overestimate the true probability.
    *
    * @param remainingDice
    *   bounds the recursion structurally instead of relying on `scratch.flags` to actually empty out. It starts at
    *   [[GameFlags.DiceSlots]] and drops by the number of dice each move consumes, so the search cannot outlive a
    *   single turn's dice pool no matter what `makeMove` produces. The bound was introduced for #549: back then
    *   `MoveGenerator.tryCastle` checked only the castling-rights flag and transit emptiness, so castling rights that
    *   contradict piece placement (reachable only via untrusted input, never via legal play) made `makeMove` desync
    *   `mailbox` from the bitboards, and move generation on the desynced state yielded moves whose `fromSquare` is
    *   empty, whose `pieceType.diceValue` is `0`, and whose `removeDie(0)` is a no-op — the pool never emptied and the
    *   recursion never terminated. Since #594 `tryCastle` refuses to castle unless the king and rook stand on their
    *   home squares, which removes that desync at the source; the bound stays as an independent termination guarantee
    *   should any future generator/applier disagreement reappear.
    */
  private def captureDFS(scratch: KcpScratchBoard, targets: Bitboard, remainingDice: Int): Boolean = boundary {
    if remainingDice <= 0 then break(false)
    val flags = scratch.flags
    if flags.isDicePoolEmpty then break(false)

    val d1            = flags.diceSlot1
    val d2            = flags.diceSlot2
    val d3            = flags.diceSlot3
    val readOnlyState = scratch.toGameStateReadOnly

    if d1 != 0 && tryPieceMoves(scratch, targets, remainingDice, flags, readOnlyState, d1) then break(true)
    if d2 != 0 && d2 != d1 && tryPieceMoves(scratch, targets, remainingDice, flags, readOnlyState, d2) then break(true)
    if d3 != 0 && d3 != d1 && d3 != d2 && tryPieceMoves(scratch, targets, remainingDice, flags, readOnlyState, d3) then
      break(true)

    false
  }

  /** Applies `move`, recurses with the surviving dice, then restores the board. Returns the DFS result. */
  private inline def recurse(
      scratch: KcpScratchBoard,
      targets: Bitboard,
      move: Move,
      survived: GameFlags,
      nextRemaining: Int
  ): Boolean =
    val undo = scratch.makeMoveInPlace(move)
    scratch.flags = scratch.flags.withDiceSlotsOf(survived)
    val hit = captureDFS(scratch, targets, nextRemaining)
    scratch.undoMove(move, undo)
    hit

  private def tryPieceMoves(
      scratch: KcpScratchBoard,
      targets: Bitboard,
      remainingDice: Int,
      flags: GameFlags,
      readOnlyState: GameState,
      die: Int
  ): Boolean = boundary {
    val moves = MoveGenerator.generatePieceMoves(readOnlyState, PieceType(die))
    var i     = 0
    while i < moves.length do
      val move      = moves(i)
      val moverType = scratch.mailbox(move.fromSquare.index).pieceType
      val survived  = flags.consumeDiceFor(move, moverType)

      // Direct capture of a target piece
      if !(targets & Bitboard.fromSquare(move.toSquare)).isEmpty then break(true)

      // Recurse with surviving dice if required dice were available.
      if survived.isValid then
        val diceConsumed = if move.isCastling then 2 else 1
        if recurse(scratch, targets, move, survived, remainingDice - diceConsumed) then break(true)

      i += 1
    false
  }
