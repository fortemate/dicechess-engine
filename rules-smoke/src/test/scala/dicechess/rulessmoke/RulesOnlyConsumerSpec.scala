// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.rulessmoke

import dicechess.engine.domain.*
import dicechess.engine.movegen.{Dfen, LegalMovesFilter, MoveGenerator}
import dicechess.engine.search.{
  ActiveDicePartitioner,
  AttackerTypes,
  BudgetedRules,
  CompactRoll,
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

  test("shared traversal compiles and runs as an external rules-only consumer (#442)"):
    val initial   = FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get
    val budget    = new BudgetedRules.Budget(1_000L)
    val traversal = new TurnGenerator.SharedTraversal(initial, Some(budget))
    val roll      = CompactRoll.fromSortedList(List(6))
    var count     = 0
    traversal.visit(roll) { (moves, length, afterTurn, capturesKing) =>
      assertEquals(length, 1)
      assertEquals(afterTurn, initial.makeMove(moves(0)).endTurn())
      assert(!capturesKing)
      count += 1
      true
    } match
      case BudgetedRules.Outcome.Complete(summary, used) =>
        assertEquals(summary.emittedPaths, count)
        assert(!summary.stoppedEarly)
        assertEquals(used, budget.used)
      case other => fail(s"rules-only traversal did not finish: $other")
    assert(count > 0)
    assert(traversal.work.generated > 0L)

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
    assertEquals(mask, AttackerTypes.bit(PieceType.Knight))
    assertEquals(AttackerTypes.availableRolls(mask), 216 - 5 * 5 * 5)
    assertEquals(DiceRolls.rollsShowingAnyOf(1), 216 - 5 * 5 * 5)
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
