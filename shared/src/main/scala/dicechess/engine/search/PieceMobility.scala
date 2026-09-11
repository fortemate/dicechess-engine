package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator

/** Shared-core primitive computing per-piece-type pseudo-legal move counts for an explicit perspective color.
  *
  * Moves are computed on `state.withActiveColor(color)` with castling dice present (King and Rook), exactly matching
  * the move-generation operands of [[RichFeatures.mobility_diff]]. The move counts follow dice-value order: Pawn (1),
  * Knight (2), Bishop (3), Rook (4), Queen (5), and King (6).
  *
  * The sum over all six piece types equals the total pseudo-legal move count for `color`, preserving the invariant:
  * `sum(own_moves_*) - sum(opp_moves_*) == mobility_diff` across every board position.
  */
object PieceMobility:

  /** Returns an array of the six per-piece-type pseudo-legal move counts for `color` in dice order: Pawn, Knight,
    * Bishop, Rook, Queen, King.
    */
  def counts(state: GameState, color: Color): Array[Int] =
    val prep = state
      .withActiveColor(color)
      .withDicePool(List(PieceType.King.diceValue, PieceType.Rook.diceValue))
    val result = new Array[Int](6)
    result(0) = MoveGenerator.generatePieceMoves(prep, PieceType.Pawn).size
    result(1) = MoveGenerator.generatePieceMoves(prep, PieceType.Knight).size
    result(2) = MoveGenerator.generatePieceMoves(prep, PieceType.Bishop).size
    result(3) = MoveGenerator.generatePieceMoves(prep, PieceType.Rook).size
    result(4) = MoveGenerator.generatePieceMoves(prep, PieceType.Queen).size
    result(5) = MoveGenerator.generatePieceMoves(prep, PieceType.King).size
    result

  /** Returns the pseudo-legal move count for a single piece type of `color`. */
  def count(state: GameState, color: Color, pieceType: PieceType): Int =
    val prep = state
      .withActiveColor(color)
      .withDicePool(List(PieceType.King.diceValue, PieceType.Rook.diceValue))
    MoveGenerator.generatePieceMoves(prep, pieceType).size

  /** Returns the total pseudo-legal move count for `color` across all piece types. */
  def total(state: GameState, color: Color): Int =
    MoveGenerator.generateAllMoves(state.withActiveColor(color)).size
