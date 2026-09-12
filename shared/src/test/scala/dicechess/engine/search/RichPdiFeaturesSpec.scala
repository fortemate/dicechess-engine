// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class RichPdiFeaturesSpec extends FunSuite:
  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  test("versioned contract preserves all nine rich columns and appends own then opponent PDI"):
    assertEquals(RichPdiFeatures.schemaId, "rich-pdi-11-v1")
    assertEquals(
      RichPdiFeatures.columnNames,
      List(
        "p_diff",
        "n_diff",
        "b_diff",
        "r_diff",
        "q_diff",
        "material_diff",
        "total_material",
        "mobility_diff",
        "king_safety_diff",
        "own_pdi",
        "opponent_pdi"
      )
    )
    for
      fen   <- List(FenParser.InitialPosition, "4k3/pp6/8/8/3Q4/8/PP6/R3K3 b - - 0 1")
      color <- List(Color.White, Color.Black)
    do
      val state    = parse(fen)
      val features = RichPdiFeatures.extract(state, color)
      assertEquals(features.length, 11)
      assertEquals(features.take(9).toList, RichFeatures.extract(state, color).toList)

  test("explicit perspective swaps absolute PDI values even when active color differs"):
    val state = parse("4k3/pp6/8/8/3Q4/8/PP6/R3K3 b - - 0 1")
    assertEquals(RichPdiFeatures.extract(state, Color.White).drop(9).toList, List(0.6f, 0.2f))
    assertEquals(RichPdiFeatures.extract(state, Color.Black).drop(9).toList, List(0.2f, 0.6f))

  test("normalization endpoints exclude kings and ignore duplicate pieces"):
    val initial = parse(FenParser.InitialPosition)
    assertEquals(RichPdiFeatures.extract(initial, Color.White).drop(9).toList, List(1f, 1f))
    val kings = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    assertEquals(RichPdiFeatures.extract(kings, Color.White).drop(9).toList, List(0f, 0f))
    val queens = parse("4k3/8/8/8/3QQ3/8/8/4K3 w - - 0 1")
    assertEquals(RichPdiFeatures.extract(queens, Color.White).drop(9).toList, List(0.2f, 0f))

  test("dice pool does not change the vector"):
    val state = parse(FenParser.InitialPosition)
    assertEquals(
      RichPdiFeatures.extract(state.withDicePool(List(1)), Color.White).toList,
      RichPdiFeatures.extract(state.withDicePool(List(5, 4)), Color.White).toList
    )
