// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** [[SearchControl]] contract (#393): check order, latching, the zero-syscall untimed path and budget sharing with
  * nested rules queries — all driven by injected clocks and deterministic work limits, never by elapsed time.
  */
class SearchControlSuite extends FunSuite:
  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  /** A clock that advances by one per read, so "the N-th check cancels" is a statement about reads, not time. */
  final private class StepClock:
    var reads               = 0
    def apply(): () => Long = () =>
      reads += 1
      reads.toLong

  test("Unbounded never cancels, reports zero work and reads no clock"):
    assert(!SearchControl.Unbounded.isCancelled)
    assert(!SearchControl.Unbounded.isTimed)
    assertEquals(SearchControl.Unbounded.workUsed, 0L)
    assertEquals(SearchControl.Unbounded.budget, None)

  test("the untimed path never invokes the clock, with or without a budget"):
    val poisoned: () => Long = () => fail("clock read on the untimed path")
    val noBudget             = SearchControl(None, clockNanos = poisoned)
    val budgeted             = SearchControl(Some(new BudgetedRules.Budget(10)), clockNanos = poisoned)
    for _ <- 1 to 3 do
      assert(!noBudget.isCancelled)
      assert(!budgeted.isCancelled)

  test("a deadline cancels at exactly the first check whose clock reading reaches it, and then latches"):
    val clock   = new StepClock
    val control = SearchControl.untilDeadline(deadlineNanos = 3L, clockNanos = clock())
    assert(control.isTimed)
    assert(!control.isCancelled) // reads 1
    assert(!control.isCancelled) // reads 2
    assert(control.isCancelled)  // reads 3 == deadline
    assertEquals(clock.reads, 3)
    assert(control.isCancelled) // latched: no further clock read
    assertEquals(clock.reads, 3)

  test("an already-passed deadline cancels at the very first check"):
    val control = SearchControl.untilDeadline(deadlineNanos = 0L, clockNanos = () => 1L)
    assert(control.isCancelled)

  test("extreme deadlines cannot overflow the comparison: Long.MinValue is already past, Long.MaxValue - 1 is not"):
    // A subtractive `now - deadline >= 0` check wraps for |deadline - now| >= 2^63 and would leave this control live.
    assert(SearchControl.untilDeadline(Long.MinValue, () => 0L).isCancelled)
    assert(SearchControl.untilDeadline(Long.MinValue, () => Long.MaxValue).isCancelled)
    assert(!SearchControl.untilDeadline(Long.MaxValue - 1, () => Long.MinValue).isCancelled)
    assert(!SearchControl.untilDeadline(Long.MaxValue - 1, () => 0L).isCancelled)

  test("a clock running backwards cannot revive a cancelled control"):
    var now     = 10L
    val control = SearchControl.untilDeadline(deadlineNanos = 5L, clockNanos = () => now)
    assert(control.isCancelled)
    now = 0L
    assert(control.isCancelled)

  test("an already-exhausted budget cancels at the first check, before any new work"):
    val budget = new BudgetedRules.Budget(0)
    val state  = parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1").withDicePool(List(6, 4))
    assertEquals(BudgetedRules.turnPaths(state, budget), BudgetedRules.Outcome.Incomplete(0L))
    assert(budget.exhausted)
    val control = SearchControl.withBudget(budget)
    assert(control.isCancelled)
    assertEquals(control.workUsed, 0L)

  test("a fresh zero budget is not cancelled until a nested query is denied its first admission"):
    // Budget(0) has nothing to spend but has denied nothing yet: cancellation is a consequence of attempted work.
    val budget  = new BudgetedRules.Budget(0)
    val control = SearchControl.withBudget(budget)
    assert(!control.isCancelled)
    val state = parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1").withDicePool(List(6, 4))
    assertEquals(BudgetedRules.turnPaths(state, budget), BudgetedRules.Outcome.Incomplete(0L))
    assert(control.isCancelled)

  test("the budget is shared with nested rules queries and exhaustion inside one cancels the whole control"):
    val state                                    = parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1").withDicePool(List(6, 4, 6))
    val BudgetedRules.Outcome.Complete(_, exact) =
      BudgetedRules.turnPaths(state, new BudgetedRules.Budget(Long.MaxValue)): @unchecked
    val budget  = new BudgetedRules.Budget(2 * exact - 1)
    val control = SearchControl.withBudget(budget)
    // First nested query completes and the control stays live with its work visible.
    assert(BudgetedRules.turnPaths(state, budget).isInstanceOf[BudgetedRules.Outcome.Complete[?]])
    assert(!control.isCancelled)
    assertEquals(control.workUsed, exact)
    // The second identical query is one unit short: it is interrupted and the control latches.
    assertEquals(BudgetedRules.turnPaths(state, budget), BudgetedRules.Outcome.Incomplete(2 * exact - 1))
    assert(control.isCancelled)
    assertEquals(control.workUsed, 2 * exact - 1)
    // Sticky exhaustion: a later query cannot complete on the same budget either.
    assert(BudgetedRules.turnPaths(state, budget).isInstanceOf[BudgetedRules.Outcome.Incomplete[?]])
    assert(control.isCancelled)

  test("budget and deadline combine: whichever fires first cancels"):
    val clock      = new StepClock
    val budget     = new BudgetedRules.Budget(Long.MaxValue)
    val byDeadline = SearchControl(Some(budget), deadlineNanos = 2L, clockNanos = clock())
    assert(!byDeadline.isCancelled)
    assert(byDeadline.isCancelled)
    val exhausted = new BudgetedRules.Budget(0)
    assertEquals(
      BudgetedRules.turnPaths(parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").withDicePool(List(6)), exhausted),
      BudgetedRules.Outcome.Incomplete(0L)
    )
    val byBudget = SearchControl(Some(exhausted), deadlineNanos = Long.MaxValue - 1, clockNanos = () => 0L)
    assert(byBudget.isCancelled)
