package dicechess.engine.search

import dicechess.engine.domain.*

/** Material-only feature extraction, byte-for-byte matching the private training pipeline's
  * `features.py::material_features` (with `include_dice=False`) — the model this feeds expects exactly these 7
  * features, in this exact order, computed the same way.
  *
  * Deliberately does NOT reuse [[Evaluator.scoreBitboard]]'s point values (100/300/300/500/900): that scale belongs to
  * the engine's own hand-tuned heuristic, not to the externally-trained model, which was fit on the raw 1/3/3/5/9
  * values below. Reusing the *bitboard-intersection* idiom is fine and intentional; reusing the *point values* would
  * silently retrain nothing while changing what every input means.
  */
object OnnxFeatures:

  private val PawnValue   = 1
  private val KnightValue = 3
  private val BishopValue = 3
  private val RookValue   = 5
  private val QueenValue  = 9

  /** The 7 features in the exact order the training pipeline's `MATERIAL_COLUMNS` expects:
    * `p_diff, n_diff, b_diff, r_diff, q_diff, material_diff, total_material` — all from `color`'s own perspective (own
    * minus opponent), not White's.
    */
  def extract(state: GameState, color: Color): Array[Float] =
    val own = if color.isWhite then state.whitePieces else state.blackPieces
    val opp = if color.isWhite then state.blackPieces else state.whitePieces

    val ownPawns   = (own & state.pawns).count
    val ownKnights = (own & state.knights).count
    val ownBishops = (own & state.bishops).count
    val ownRooks   = (own & state.rooks).count
    val ownQueens  = (own & state.queens).count

    val oppPawns   = (opp & state.pawns).count
    val oppKnights = (opp & state.knights).count
    val oppBishops = (opp & state.bishops).count
    val oppRooks   = (opp & state.rooks).count
    val oppQueens  = (opp & state.queens).count

    val ownMaterial =
      ownPawns * PawnValue + ownKnights * KnightValue + ownBishops * BishopValue +
        ownRooks * RookValue + ownQueens * QueenValue
    val oppMaterial =
      oppPawns * PawnValue + oppKnights * KnightValue + oppBishops * BishopValue +
        oppRooks * RookValue + oppQueens * QueenValue

    Array(
      (ownPawns - oppPawns).toFloat,
      (ownKnights - oppKnights).toFloat,
      (ownBishops - oppBishops).toFloat,
      (ownRooks - oppRooks).toFloat,
      (ownQueens - oppQueens).toFloat,
      (ownMaterial - oppMaterial).toFloat,
      (ownMaterial + oppMaterial).toFloat
    )
