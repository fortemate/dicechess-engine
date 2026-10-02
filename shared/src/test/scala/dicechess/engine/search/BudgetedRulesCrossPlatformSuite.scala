// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import BudgetedRules.*

/** Exercise the rules budget from the engine matrix, including the Wasm link (#316). */
class BudgetedRulesCrossPlatformSuite extends munit.FunSuite:
  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  test("budgeted special-move paths retain canonical order on each engine platform") {
    for fen <- List(
        "4k3/8/8/8/8/8/8/4K2R w K - 0 1",
        "4k3/P7/8/8/8/8/8/4K3 w - - 0 1",
        "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1"
      )
    do
      val state = parse(fen).withDicePool(List(1, 4, 6))
      turnPaths(state, new Budget(Long.MaxValue)) match
        case Outcome.Complete(paths, _) => assertEquals(paths, TurnGenerator.generateAllLegalTurnPaths(state))
        case _                          => fail("ample platform budget exhausted")
      assertEquals(turnPaths(state, new Budget(0)), Outcome.Incomplete(0L))
  }

  test("budgeted capture counts and interruption survive each engine platform link") {
    val state = parse("8/8/8/8/8/2k5/8/K7 w - - 0 1")
    kingCaptureRolls(state, Color.Black, new Budget(Long.MaxValue)) match
      case Outcome.Complete(count, _) =>
        assertEquals(count.toDouble / 216, KingCaptureProbability.kingCaptureProbability(state, Color.Black))
      case _ => fail("ample capture platform budget exhausted")
    assertEquals(kingCaptureRolls(state, Color.Black, new Budget(0)), Outcome.Incomplete(0L))
  }
