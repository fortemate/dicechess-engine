package dicechess.engine.search

import dicechess.engine.domain.*

/** Versioned, dice-independent 31-column feature contract (`kcp-mobility-pawns-31-v1`).
  *
  * Keeps [[KcpMobilityFeatures.extract]] as an unchanged 27-column prefix, followed by passed-pawn counts
  * (`own_passed_pawns`, `opp_passed_pawns`) and the rank of the most advanced passed pawn (`own_passed_max_rank`,
  * `opp_passed_max_rank`), counted from that side's own back rank as 0 (so 1–6 for a pawn and 0 when none exists).
  *
  * All features are evaluated from the explicit perspective of `color`, independent of active color and dice.
  */
object KcpMobilityPawnsFeatures:

  val schemaId: String = "kcp-mobility-pawns-31-v1"

  val columnNames: List[String] =
    KcpMobilityFeatures.columnNames ++ List(
      "own_passed_pawns",
      "opp_passed_pawns",
      "own_passed_max_rank",
      "opp_passed_max_rank"
    )

  def extract(state: GameState, color: Color): Array[Float] =
    val prefix           = KcpMobilityFeatures.extract(state, color)
    val opponent         = color.opponent
    val ownPassedCount   = PassedPawns.count(state, color).toFloat
    val oppPassedCount   = PassedPawns.count(state, opponent).toFloat
    val ownPassedMaxRank = PassedPawns.maxRank(state, color).toFloat
    val oppPassedMaxRank = PassedPawns.maxRank(state, opponent).toFloat

    val result = new Array[Float](31)
    System.arraycopy(prefix, 0, result, 0, 27)
    result(27) = ownPassedCount
    result(28) = oppPassedCount
    result(29) = ownPassedMaxRank
    result(30) = oppPassedMaxRank
    result
