// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.rulessmoke

import dicechess.engine.domain.*
import dicechess.engine.movegen.{Dfen, LegalMovesFilter, MoveGenerator}
import dicechess.engine.search.{
  ActiveDicePartitioner,
  AttackerTypes,
  BudgetedRules,
  DiceRolls,
  KingCaptureProbability,
  TurnGenerator
}
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

  test(
    "Epic #390 rules primitives (ActiveDicePartitioner, TurnState visitors, BudgetedRules, AttackerTypes) work on dicechess-rules alone"
  ):
    val f8     = FenParser.parse("4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP").toOption.get
    val budget = new BudgetedRules.Budget(1_000L)
    val folded = BudgetedRules.foldTurnStates(f8, Vector.empty[(List[Move], GameState, Boolean)], budget) {
      (acc, path, afterState, isKingCapture) =>
        (acc :+ (path, afterState, isKingCapture), true)
    }
    val entries = folded match
      case BudgetedRules.Outcome.Complete(res, _) => res.value
      case other                                  => fail(s"expected Complete, got $other")
    assert(entries.nonEmpty && entries.forall(!_._3))
    val postTurn   = entries.head._2
    val partitions = ActiveDicePartitioner.partitionsFor(postTurn)
    assertEquals(partitions.map(_.weight).sum, DiceRolls.totalOrderedRolls)

    val f7   = FenParser.parse("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP").toOption.get
    val mask = AttackerTypes.mask(f7, Square('c', 5), Color.White)
    assert(AttackerTypes.contains(mask, PieceType.Knight))
    assertEquals(
      AttackerTypes.availableRolls(mask),
      DiceRolls.rollsShowingAnyOf(AttackerTypes.count(mask))
    )
    assert(!MoveGenerator.allAttackers(f7, Square('c', 5), Color.White).isEmpty)

  test("no search algorithm, evaluator, feature extractor or ONNX runtime is on the classpath"):
    for cls <- List(
        "dicechess.engine.search.ExpectimaxSearch",
        "dicechess.engine.search.GreedySearch",
        "dicechess.engine.search.Evaluator",
        "dicechess.engine.search.EvaluationCache",
        "dicechess.engine.search.TranspositionTable",
        "dicechess.engine.search.ChanceNode",
        "dicechess.engine.search.SearchControl",
        "dicechess.engine.search.SearchOutcome",
        "dicechess.engine.search.PieceValues",
        "dicechess.engine.search.PassedPawns",
        "dicechess.engine.search.KcpFeatures",
        "dicechess.engine.search.OnnxEvalSearch",
        "dicechess.engine.jvmapi.JvmApi",
        "ai.onnxruntime.OrtEnvironment"
      )
    do absent(cls)
