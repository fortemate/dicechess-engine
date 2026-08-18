package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator

/** Boolean piece-safety primitives: which pieces stand *en prise* (attacked and undefended).
  *
  * This is the building block behind the hang telemetry in the arena (`dicechess.engine.bench.BotMatchRunner`, a
  * separate sbt project not linkable from here) and is deliberately shared so a safety-aware pre-ranker or feature
  * extractor can later reuse the exact same notion of "hanging" — a metric and the signal it motivates must agree on
  * definitions, or the metric can't validate the fix.
  *
  * The definition is the *deterministic-chess* one, kept intentionally simple:
  *   - attack and defense are boolean ([[dicechess.engine.movegen.MoveGenerator.isSquareAttacked]]), with no exchange
  *     evaluation — a piece defended once but attacked twice does not count as hanging;
  *   - a king "defends" its neighbours like any other piece, even when recapturing would be suicidal against a defended
  *     attacker;
  *   - en passant is not modeled (the capture lands on a square the victim does not occupy);
  *   - kings are never "hanging" — an attacked king is check, a different concept with its own machinery
  *     ([[Evaluator.evaluateKingSafety]], [[KingCaptureProbability]]).
  *
  * Dice probabilities are also deliberately absent: whether the opponent can *roll* the attacker's die is a separate,
  * composable concern (the chance of punishing a hang is `1 - ((6 - k) / 6)^3` for `k` distinct attacking piece types),
  * and keeping this primitive dice-free lets one implementation serve both a raw blunder count and a dice-weighted risk
  * feature.
  */
object PieceSafety:

  /** Squares of `side`'s non-king pieces that are attacked by the opponent and not defended by `side`.
    *
    * @param state
    *   the position to inspect (whose side is to move is irrelevant — attack geometry is static)
    * @param side
    *   the owner of the potentially hanging pieces
    */
  def hangingSquares(state: GameState, side: Color): Bitboard =
    val own       = if side.isWhite then state.whitePieces else state.blackPieces
    var remaining = (own & ~state.kings).value
    var hanging   = Bitboard.empty
    while remaining != 0 do
      val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(remaining))
      if !MoveGenerator.isSquareAttacked(state, sq, side.opponent).isEmpty
        && MoveGenerator.isSquareAttacked(state, sq, side).isEmpty
      then hanging = hanging.add(sq)
      remaining &= remaining - 1
    hanging

  /** Total centipawn value of the pieces standing on `squares`, priced from [[MaterialValues]] — the same scale
    * [[Evaluator.evaluateMaterial]] works in, which is what makes this number comparable against an evaluation. Kings
    * are absent from the sum on purpose: [[hangingSquares]] never yields one, so [[MaterialValues.King]] would only be
    * reachable by a caller passing arbitrary squares, and pricing a king as hanging material is meaningless anyway.
    */
  def materialOn(state: GameState, squares: Bitboard): Int =
    (squares & state.pawns).count * MaterialValues.Pawn +
      (squares & state.knights).count * MaterialValues.Knight +
      (squares & state.bishops).count * MaterialValues.Bishop +
      (squares & state.rooks).count * MaterialValues.Rook +
      (squares & state.queens).count * MaterialValues.Queen

  /** Convenience composition: the centipawn value `side` currently has en prise. */
  def hangingMaterial(state: GameState, side: Color): Int =
    materialOn(state, hangingSquares(state, side))
