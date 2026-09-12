// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*

/** Versioned, dice-independent 27-column feature contract (`kcp-mobility-27-v1`).
  *
  * Keeps [[KcpFeatures.extract]] as an unchanged 13-column prefix, followed by pseudo-legal move counts per moving
  * piece type for the evaluation color (`own_moves_p` through `own_moves_k`), the same six for the opponent
  * (`opp_moves_p` through `opp_moves_k`), and normalized piece diversity indices (`own_pdi`, `opp_pdi`).
  *
  * All features are evaluated from the explicit perspective of `color`, independent of active color and dice.
  */
object KcpMobilityFeatures:

  val schemaId: String = "kcp-mobility-27-v1"

  val columnNames: List[String] =
    KcpFeatures.columnNames ++ List(
      "own_moves_p",
      "own_moves_n",
      "own_moves_b",
      "own_moves_r",
      "own_moves_q",
      "own_moves_k",
      "opp_moves_p",
      "opp_moves_n",
      "opp_moves_b",
      "opp_moves_r",
      "opp_moves_q",
      "opp_moves_k",
      "own_pdi",
      "opp_pdi"
    )

  def extract(state: GameState, color: Color): Array[Float] =
    val kcp       = KcpFeatures.extract(state, color)
    val opponent  = color.opponent
    val ownCounts = PieceMobility.counts(state, color)
    val oppCounts = PieceMobility.counts(state, opponent)
    val ownPdi    = PieceDiversity.count(state, color).toFloat / 5f
    val oppPdi    = PieceDiversity.count(state, opponent).toFloat / 5f

    val result = new Array[Float](27)
    System.arraycopy(kcp, 0, result, 0, 13)
    var i = 0
    while i < 6 do
      result(13 + i) = ownCounts(i).toFloat
      result(19 + i) = oppCounts(i).toFloat
      i += 1
    result(25) = ownPdi
    result(26) = oppPdi
    result
