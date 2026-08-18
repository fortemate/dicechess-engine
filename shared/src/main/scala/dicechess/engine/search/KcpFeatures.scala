package dicechess.engine.search

import dicechess.engine.domain.*

/** [[RichFeatures]] plus the king/queen **capture probabilities** — the feature that encodes the win condition of Dice
  * Chess (capturing the king) directly, rather than through a material or activity proxy.
  *
  * Each probability is integrated over the opponent's 216 possible dice rolls (see [[KingCaptureProbability]]), so like
  * the rest of the set it is mover-perspective and **dice-independent** — it describes the *next* roll, not the current
  * pool — matching the dice-free leaf the search evaluates. Kept separate from [[RichFeatures]] on purpose, so the
  * cheap set the 2-ply search uses stays unchanged.
  *
  * @note
  *   Cost: every capture probability is a 216-outcome DFS (millisecond-scale), far heavier than [[RichFeatures]]' two
  *   move-generation passes. This set targets training enrichment and **one-ply** inference only — a 2-ply search's
  *   hundreds of thousands of leaves per move would make it thousands of seconds per move.
  */
object KcpFeatures:

  /** [[RichFeatures.columnNames]] followed by the capture-probability columns, mover-perspective:
    *   - `*_attack` — probability the mover captures the opponent's king/queen on the mover's next roll;
    *   - `*_danger` — probability the opponent captures the mover's own king/queen on the opponent's next roll.
    */
  val columnNames: List[String] =
    RichFeatures.columnNames ++ List(
      "king_capture_attack",
      "king_capture_danger",
      "queen_capture_attack",
      "queen_capture_danger"
    )

  /** Extracts the [[columnNames]] vector from `color`'s perspective: the [[RichFeatures]] block, then the king and
    * queen capture probabilities. `KingCaptureProbability.*CaptureProbability(state, defenderColor)` is the chance the
    * *defender's* piece falls to its opponent's next roll, so `attack` passes the opponent as defender (the mover is
    * the attacker) and `danger` passes the mover itself.
    */
  def extract(state: GameState, color: Color): Array[Float] =
    val opponent    = color.opponent
    val kingAttack  = KingCaptureProbability.kingCaptureProbability(state, opponent).toFloat
    val kingDanger  = KingCaptureProbability.kingCaptureProbability(state, color).toFloat
    val queenAttack = KingCaptureProbability.queenCaptureProbability(state, opponent).toFloat
    val queenDanger = KingCaptureProbability.queenCaptureProbability(state, color).toFloat
    RichFeatures.extract(state, color) ++ Array(kingAttack, kingDanger, queenAttack, queenDanger)
