// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.rulessmoke

import dicechess.engine.domain.*
import dicechess.engine.movegen.{Dfen, LegalMovesFilter}
import dicechess.engine.search.{KingCaptureProbability, TurnGenerator}
import munit.FunSuite

/** A rules-only consumer: everything a server of record or a client needs — parse, enumerate legal turns, apply,
  * serialise, canonical key, exact capture odds — must work with `dicechess-rules` alone. The negative assertions pin
  * the boundary: no search algorithm, evaluator or ONNX runtime may be reachable from this classpath.
  */
class RulesOnlyConsumerSpec extends FunSuite:

  private def absent(className: String): Unit =
    intercept[ClassNotFoundException](Class.forName(className))

  test("parses, enumerates legal turns, applies a turn and serialises DFEN"):
    val state = FenParser.parse(FenParser.InitialPosition).toOption.get.withDicePool(List(1, 2, 1))
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    assert(paths.nonEmpty, "the initial position with dice PNP has legal turns")
    assert(LegalMovesFilter.filterMaximalMoves(state).nonEmpty)
    val after = paths.head.foldLeft(state)((s, m) => s.makeMove(m)).endTurn()
    assert(FenParser.serialize(after).nonEmpty)
    assert(Dfen.normalizedFen(after).split(" ").length == 4)

  test("exact king-capture probability is available without any evaluator"):
    val threatened = FenParser.parse("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1").toOption.get
    assertEqualsDouble(KingCaptureProbability.kingCaptureProbability(threatened, Color.White), 91.0 / 216.0, 1e-12)

  test("no search algorithm, evaluator, feature extractor or ONNX runtime is on the classpath"):
    for cls <- List(
        "dicechess.engine.search.ExpectimaxSearch",
        "dicechess.engine.search.GreedySearch",
        "dicechess.engine.search.Evaluator",
        "dicechess.engine.search.KcpFeatures",
        "dicechess.engine.search.OnnxEvalSearch",
        "dicechess.engine.jvmapi.JvmApi",
        "ai.onnxruntime.OrtEnvironment"
      )
    do absent(cls)
