// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import BudgetedRules.*

/** Ordered parity and internal cutoff regressions for #316. */
class BudgetedRulesSuite extends munit.FunSuite:
  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get
  private val positions                     = List(
    "4k3/8/8/8/8/8/8/4K3 w - - 0 1",
    "4k3/8/8/8/8/8/8/4K2R w K - 0 1",
    "r3k3/8/8/8/8/8/8/4K3 b q - 0 1",
    "4k3/P7/8/8/8/8/8/4K3 w - - 0 1",
    "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
    "8/8/8/8/8/2k5/8/K7 w - - 0 1"
  )

  test("ordered paths agree with complete rules for special moves and dice permutations") {
    for
      fen  <- positions;
      dice <- List(
        Nil,
        List(1),
        List(4),
        List(6),
        List(1, 6),
        List(6, 4),
        List(4, 6),
        List(6, 4, 6),
        List(6, 1, 1),
        List(1, 1, 6),
        List(1, 4, 6),
        List(6, 4, 1)
      )
    do
      val s = parse(fen).withDicePool(dice)
      turnPaths(s, new Budget(Long.MaxValue)) match
        case Outcome.Complete(value, _) => assertEquals(value, TurnGenerator.generateAllLegalTurnPaths(s))
        case _                          => fail("ample path budget exhausted")
  }

  test("generation cuts off inside the tree and exact completed boundaries suffice") {
    val s                                = parse(positions(1)).withDicePool(List(6, 4, 6))
    val Outcome.Complete(expected, used) = turnPaths(s, new Budget(Long.MaxValue)): @unchecked
    assert(used > 3)
    assertEquals(turnPaths(s, new Budget(used)), Outcome.Complete(expected, used))
    for limit <- List(0L, 1L, 2L, used - 1) do assertEquals(turnPaths(s, new Budget(limit)), Outcome.Incomplete(limit))
  }

  test("complete pass and missing target retain their distinct work semantics") {
    val s = parse(positions.head).withDicePool(List(4))
    assertEquals(turnPaths(s, new Budget(1)), Outcome.Complete(Nil, 1L))
    assertEquals(
      kingCaptureRolls(parse("8/8/8/8/8/8/8/K7 w - - 0 1"), Color.Black, new Budget(0)),
      Outcome.Complete(0, 0L)
    )
    intercept[IllegalArgumentException](new Budget(-1))
  }

  test("capture counts match published golden scenarios independent of input side and dice") {
    for fixture <- KingCaptureFixtures.cases do
      val s = parse(fixture.fen).withActiveColor(fixture.defenderColor).withDicePool(List(1))
      kingCaptureRolls(s, fixture.defenderColor, new Budget(Long.MaxValue)) match
        case Outcome.Complete(value, _) =>
          assertEquals(value, fixture.winningRolls, fixture.name)
          assertEquals(value.toDouble / 216, KingCaptureProbability.kingCaptureProbability(s, fixture.defenderColor))
        case _ => fail("ample capture budget exhausted")
  }

  test("capture counts match independent ordered full-path enumeration and color mirrors") {
    for fen <- List(positions(3), positions(4), positions(5)) do
      val s     = parse(fen)
      val count = (for a <- 1 to 6; b <- 1 to 6; c <- 1 to 6 yield
        val rolled = s.withDicePool(List(a, b, c))
        if TurnGenerator
            .generateAllLegalTurnPaths(rolled)
            .exists(p => p.exists(m => s.isKingCapture(m)))
        then 1
        else 0
      ).sum
      for (state, defender) <- List((s, Color.Black), (Symmetry.colorFlip(s), Color.White)) do
        kingCaptureRolls(state, defender, new Budget(Long.MaxValue)) match
          case Outcome.Complete(value, _) => assertEquals(value, count)
          case _                          => fail("ample independent capture budget exhausted")
  }

  test("capture cutoff and shared exhaustion never expose a partial exact value") {
    val s                                = parse(positions(5))
    val Outcome.Complete(expected, used) = kingCaptureRolls(s, Color.Black, new Budget(Long.MaxValue)): @unchecked
    assertEquals(kingCaptureRolls(s, Color.Black, new Budget(used)), Outcome.Complete(expected, used))
    assertEquals(kingCaptureRolls(s, Color.Black, new Budget(used - 1)), Outcome.Incomplete(used - 1))
    val shared = new Budget(2)
    assertEquals(turnPaths(parse(positions.head).withDicePool(List(4)), shared), Outcome.Complete(Nil, 1L))
    assertEquals(kingCaptureRolls(s, Color.Black, shared), Outcome.Incomplete(2L))
    assertEquals(turnPaths(s, shared), Outcome.Incomplete(2L))
    assertEquals(kingCaptureRolls(s, Color.Black, shared), Outcome.Incomplete(2L))
  }
