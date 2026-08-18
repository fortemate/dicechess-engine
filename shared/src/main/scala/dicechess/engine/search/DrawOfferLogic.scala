package dicechess.engine.search

import dicechess.engine.domain.*
import scala.util.boundary, boundary.break

/** Mix-in that equips a [[SearchAlgorithm]] with draw-awareness based on insufficient material and evaluation
  * thresholds.
  *
  * Bots mixing in this trait will offer a draw when neither side can force a king capture — the win condition in Dice
  * Chess. This happens with king + at most one bishop each, no other pieces, kings safely separated and the defending
  * king on the opposite square colour from the enemy bishop. They will accept a draw offer when the position evaluation
  * drops below −200 centipawns (down material).
  *
  * The trait is designed to be reusable — any `SearchAlgorithm` can gain draw logic simply by declaring:
  * ```text
  * object MyBot extends SearchAlgorithm with DrawOfferLogic
  * ```
  *
  * ## Why separate from `SearchAlgorithm`
  *
  * The base [[SearchAlgorithm]] defaults both draw methods to `false` so that simple bots remain silent. This trait
  * packages a single, consistent draw policy that can be composed with any strategy without code duplication.
  */
trait DrawOfferLogic extends SearchAlgorithm:

  /** Offers a draw when the position is a theoretical dead draw: neither player can ever force a king capture — the win
    * condition in Dice Chess.
    *
    * The check requires all of the following:
    *   - No pawns, knights, rooks, or queens on the board.
    *   - Each side has at most one bishop.
    *   - The two kings are not on adjacent squares.
    *   - For every bishop on the board, the opposing king stands on a square of the opposite colour — making a diagonal
    *     attack impossible in either direction.
    *
    * These constraints capture the standard "insufficient material" cases:
    *   - `K vs K`
    *   - `K+B vs K` (defending king on correct colour)
    *   - `K+B vs K+B` (one bishop each, both kings safe)
    */
  override def shouldOfferDraw(state: GameState): Boolean =
    hasInsufficientDrawMaterial(state)

  /** Accepts a draw when the position is so unfavourable that playing on is worse than splitting the point.
    *
    * Uses the centipawn evaluation from [[Evaluator.evaluate]] from the active side's perspective. The threshold of
    * −200cp (a pawn's disadvantage) is deliberately conservative — it avoids accepting draws in roughly equal positions
    * while cutting losses in clearly lost ones.
    */
  override def shouldAcceptDraw(state: GameState): Boolean =
    Evaluator.evaluate(state, state.activeColor) < -200

  /** Returns `true` when [[state]] has no pieces that can force a king capture.
    *
    * Memory layout — the method inspects piece-type bitboards and king squares, producing no allocations beyond boxing
    * for the `Long` trailing-zero intrinsic.
    */
  private def hasInsufficientDrawMaterial(state: GameState): Boolean = boundary {
    val allPieces = state.pawns | state.knights | state.rooks | state.queens
    if allPieces != Bitboard.empty then break(false)

    val whiteBishops = (state.bishops & state.whitePieces).count
    val blackBishops = (state.bishops & state.blackPieces).count

    if whiteBishops > 1 || blackBishops > 1 then break(false)

    val wkSq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros((state.kings & state.whitePieces).value))
    val bkSq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros((state.kings & state.blackPieces).value))

    val fileDist = (wkSq.file - bkSq.file).abs
    val rankDist = (wkSq.rank - bkSq.rank).abs

    if fileDist <= 1 && rankDist <= 1 then break(false)

    // Symmetric colour check: no bishop can ever attack the opposing king.
    !sameSquareColor(wkSq, blackBishops, state.bishops & state.blackPieces) &&
    !sameSquareColor(bkSq, whiteBishops, state.bishops & state.whitePieces)
  }

  private def sameSquareColor(kingSq: Square, bishopCount: Int, bishops: Bitboard): Boolean =
    bishopCount == 1 && {
      val bishopSq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(bishops.value))
      ((kingSq.file - 'a' + kingSq.rank) & 1) == ((bishopSq.file - 'a' + bishopSq.rank) & 1)
    }
