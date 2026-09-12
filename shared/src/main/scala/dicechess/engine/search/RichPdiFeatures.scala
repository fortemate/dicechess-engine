// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*

/** Versioned, dice-independent input for PDI ablations. The rich-9 prefix is unchanged; the two absolute class counts
  * are normalized by five and ordered by the explicit evaluation perspective, not active color. Presence is not
  * mobility or expected usable rolls. Training enrichment and ONNX inference use this extractor.
  */
object RichPdiFeatures:
  val schemaId: String = "rich-pdi-11-v1"

  val columnNames: List[String] = RichFeatures.columnNames ++ List("own_pdi", "opponent_pdi")

  def extract(state: GameState, color: Color): Array[Float] =
    RichFeatures.extract(state, color) ++ Array(
      PieceDiversity.count(state, color).toFloat / 5f,
      PieceDiversity.count(state, color.opponent).toFloat / 5f
    )
