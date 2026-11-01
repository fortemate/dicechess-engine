// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** [[SearchOutcome]] contract (#393): kinds, accessors, mapping and the lift from rules outcomes. */
class SearchOutcomeSuite extends FunSuite:
  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  test("only Exact is exact; bounds are bounds; Incomplete carries no value"):
    val exact: SearchOutcome[Double] = SearchOutcome.Exact(1.5, 10L)
    val lower: SearchOutcome[Double] = SearchOutcome.LowerBound(2.0, 11L)
    val upper: SearchOutcome[Double] = SearchOutcome.UpperBound(3.0, 12L)
    val none: SearchOutcome[Double]  = SearchOutcome.Incomplete(13L)
    assert(exact.isExact && !exact.isBound && !exact.isIncomplete)
    assert(!lower.isExact && lower.isBound && !lower.isIncomplete)
    assert(!upper.isExact && upper.isBound && !upper.isIncomplete)
    assert(!none.isExact && !none.isBound && none.isIncomplete)
    assertEquals(exact.exactOption, Some(1.5))
    assertEquals(lower.exactOption, None)
    assertEquals(upper.exactOption, None)
    assertEquals(none.exactOption, None)
    assertEquals(exact.valueOption, Some(1.5))
    assertEquals(lower.valueOption, Some(2.0))
    assertEquals(upper.valueOption, Some(3.0))
    assertEquals(none.valueOption, None)
    assertEquals(List(exact, lower, upper, none).map(_.workUsed), List(10L, 11L, 12L, 13L))

  test("map preserves the kind, direction and work count; withWorkUsed replaces only the count"):
    val outcomes: List[SearchOutcome[Int]] =
      List(
        SearchOutcome.Exact(1, 5L),
        SearchOutcome.LowerBound(2, 6L),
        SearchOutcome.UpperBound(3, 7L),
        SearchOutcome.Incomplete(8L)
      )
    assertEquals(
      outcomes.map(_.map(_ * 10)),
      List[SearchOutcome[Int]](
        SearchOutcome.Exact(10, 5L),
        SearchOutcome.LowerBound(20, 6L),
        SearchOutcome.UpperBound(30, 7L),
        SearchOutcome.Incomplete(8L)
      )
    )
    assertEquals(
      outcomes.map(_.withWorkUsed(0L)),
      List[SearchOutcome[Int]](
        SearchOutcome.Exact(1, 0L),
        SearchOutcome.LowerBound(2, 0L),
        SearchOutcome.UpperBound(3, 0L),
        SearchOutcome.Incomplete(0L)
      )
    )

  test("fromRules lifts Complete to Exact and Incomplete to Incomplete with the same work count"):
    val state = parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1").withDicePool(List(6, 4))
    val full  = SearchOutcome.fromRules(BudgetedRules.turnPaths(state, new BudgetedRules.Budget(Long.MaxValue)))
    assert(full.isExact)
    assertEquals(
      full.exactOption.map(_.map(_.toString)),
      Some(TurnGenerator.generateAllLegalTurnPaths(state).map(_.toString))
    )
    val cut = SearchOutcome.fromRules(BudgetedRules.turnPaths(state, new BudgetedRules.Budget(1)))
    assertEquals(cut, SearchOutcome.Incomplete(1L))
