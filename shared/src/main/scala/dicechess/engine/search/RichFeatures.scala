package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator

/** The material feature set ([[OnnxFeatures]]) plus cheap, dice-free positional features — the signal a material-only
  * model is blind to (it cannot tell two positions of equal material apart).
  *
  * Kept separate from [[OnnxFeatures]] on purpose: that 7-feature set is the input contract of the currently deployed
  * model, so its shape must not change. `RichFeatures` reuses it for the material block and appends the positional
  * columns, giving a distinct input for a model trained on the richer set. Computing features here — in the engine, the
  * single source of truth for move generation — means training-data enrichment and inference share this exact code,
  * with no Python/Scala re-implementation drift.
  *
  * All features are from `color`'s own perspective (own minus opponent) and independent of the dice pool, matching the
  * dice-free leaf the search evaluates.
  *
  * @note
  *   Cost: `mobility_diff` runs two full [[dicechess.engine.movegen.MoveGenerator.generateAllMoves]] passes, so this
  *   set is **not** cheap enough to evaluate on every leaf of a 2-ply search (hundreds of thousands per move). It
  *   targets training enrichment and one-ply / shallow inference — the "one-ply + rich features vs. two-ply + material"
  *   comparison.
  */
object RichFeatures:

  /** Column names in the exact order [[extract]] returns values: the material block (aligned with the training
    * pipeline's `MATERIAL_COLUMNS`) followed by the positional columns. The enrichment step writes CSV headers from
    * this list, so training and serving agree on layout.
    */
  val columnNames: List[String] =
    List(
      "p_diff",
      "n_diff",
      "b_diff",
      "r_diff",
      "q_diff",
      "material_diff",
      "total_material",
      "mobility_diff",
      "king_safety_diff"
    )

  /** Extracts the rich feature vector from `color`'s perspective. Layout is [[columnNames]]:
    *   - the 7 material features, identical to [[OnnxFeatures.extract]];
    *   - `mobility_diff` — pseudo-legal move count (dice-independent) for `color` minus the opponent;
    *   - `king_safety_diff` — [[Evaluator.evaluateKingSafety]] for `color` minus the opponent (negative when our king
    *     is the one under attack).
    */
  def extract(state: GameState, color: Color): Array[Float] =
    val opponent       = color.opponent
    val myMoves        = MoveGenerator.generateAllMoves(state.withActiveColor(color)).size
    val oppMoves       = MoveGenerator.generateAllMoves(state.withActiveColor(opponent)).size
    val mobilityDiff   = (myMoves - oppMoves).toFloat
    val kingSafetyDiff =
      (Evaluator.evaluateKingSafety(state, color) - Evaluator.evaluateKingSafety(state, opponent)).toFloat
    OnnxFeatures.extract(state, color) ++ Array(mobilityDiff, kingSafetyDiff)
