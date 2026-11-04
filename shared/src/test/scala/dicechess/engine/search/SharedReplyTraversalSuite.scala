// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.concurrent.duration.*

/** Weighted-class integration regressions for #442: canonical replies and independent 216-roll values survive lazy
  * cross-class reuse; cancellation and exhausted budgets never become a forced pass or an exact expectation.
  */
class SharedReplyTraversalSuite extends FunSuite:
  override val munitTimeout: Duration = 120.seconds

  private val fixtures = ChanceNodeOracle.fixtures ++ List(
    "castling"   -> parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1"),
    "en passant" -> parse("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
  )

  fixtures.foreach { (name, afterTurn) =>
    test(s"shared replies equal independent per-class replies in both class orders: $name"):
      val ample   = new BudgetedRules.Budget(Long.MaxValue)
      val shared  = new ChanceNode.ReplyTraversal(afterTurn, SearchControl.withBudget(ample))
      val classes = ActiveDicePartitioner.partitionsFor(afterTurn).toList
      for weighted <- classes ++ classes.reverse do
        val rolled        = weighted.roll.applyToState(afterTurn)
        val actual        = shared.generate(rolled).getOrElse(fail("ample shared traversal was interrupted"))
        val legacyControl = SearchControl.withBudget(new BudgetedRules.Budget(Long.MaxValue))
        val expected      =
          ChanceNode.Replies.generate(rolled, legacyControl).getOrElse(fail("ample legacy traversal was interrupted"))
        assertEquals(snapshot(actual), snapshot(expected), s"$name roll=${weighted.roll.toList}")
      assert(!ample.exhausted)
  }

  for (name, afterTurn) <- fixtures.take(8); perspective <- List(Color.White, Color.Black) do
    test(s"shared-reply chance policy equals the independent 216-roll oracle: $name for $perspective"):
      val bounds  = ExpectimaxSearch.utilityBounds(perspective)
      val classes = ActiveDicePartitioner.partitionsFor(afterTurn)
      val oracle  = ChanceNodeOracle.expectationOver216(afterTurn, bounds, Evaluator.evaluateMaterial)
      val orders  = List((0 until classes.length).toArray, (0 until classes.length).reverse.toArray)
      for order <- orders do
        val control = SearchControl.withBudget(new BudgetedRules.Budget(Long.MaxValue))
        val policy  = new SharedLeafPolicy(afterTurn, bounds, control)
        val result  =
          ChanceNode.expand(afterTurn, bounds, control, Double.NegativeInfinity, Double.PositiveInfinity, policy, order)
        assertEquals(result.outcome.exactOption, Some(oracle))
        assertEquals(result.classesResolved, classes.length)
        assertEquals(policy.decisions, classes.length)
        assertEquals(classes.iterator.map(_.weight).sum, DiceRolls.totalOrderedRolls)

  test("a terminal reply succeeds with less work than legacy full discovery and does not taint the control"):
    val afterTurn = parse("8/8/8/8/8/4k3/4R3/4K3 w - - 0 1")
    val rolled    = afterTurn.withDicePool(List(4, 4, 4))
    val measured  = new BudgetedRules.Budget(Long.MaxValue)
    val shared    = new ChanceNode.ReplyTraversal(afterTurn, SearchControl.withBudget(measured))
    assert(shared.generate(rolled).exists(_.capturesKing))
    val legacy = new BudgetedRules.Budget(Long.MaxValue)
    assert(ChanceNode.Replies.generate(rolled, SearchControl.withBudget(legacy)).exists(_.capturesKing))
    assert(measured.used < legacy.used)
    val exactBudget = new BudgetedRules.Budget(measured.used)
    val control     = SearchControl.withBudget(exactBudget)
    val exact       = new ChanceNode.ReplyTraversal(afterTurn, control)
    assert(exact.generate(rolled).exists(_.capturesKing))
    assert(!control.isCancelled)
    assert(
      ChanceNode.Replies.generate(rolled, SearchControl.withBudget(new BudgetedRules.Budget(measured.used))).isEmpty
    )

  test("zero budget cannot turn an interrupted normal class or a later empty class into a pass"):
    val afterTurn = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val control   = SearchControl.withBudget(new BudgetedRules.Budget(0))
    val traversal = new ChanceNode.ReplyTraversal(afterTurn, control)
    assert(traversal.generate(afterTurn.withDicePool(List(6))).isEmpty)
    assert(control.isCancelled)
    assert(traversal.generate(afterTurn).isEmpty)
    // An empty class needs no generation work when the invocation has not been interrupted.
    val emptyBudget = new BudgetedRules.Budget(0)
    val fresh       = new ChanceNode.ReplyTraversal(afterTurn, SearchControl.withBudget(emptyBudget))
    assert(fresh.generate(afterTurn).exists(_.isForcedPass))
    assertEquals(emptyBudget.used, 0L)
    assert(!emptyBudget.exhausted)

  test("a cancelled control returns no reply even for an empty class"):
    val afterTurn = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val control   = SearchControl.untilDeadline(1L, () => 2L)
    val traversal = new ChanceNode.ReplyTraversal(afterTurn, control)
    assert(traversal.generate(afterTurn).isEmpty)
    assert(traversal.generate(afterTurn.withDicePool(List(6))).isEmpty)

  test("a deadline during traversal discards the partial reply collection"):
    val afterTurn = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    var ticks     = 0L
    val control   = SearchControl.untilDeadline(7L, () => { ticks += 1; ticks })
    val traversal = new ChanceNode.ReplyTraversal(afterTurn, control)
    assert(traversal.generate(afterTurn.withDicePool(List(6, 6, 6))).isEmpty)
    assert(control.isCancelled)

  test("deadline clock reads are amortized while deterministic work and exact replies remain unchanged"):
    val afterTurn       = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val rolled          = afterTurn.withDicePool(List(6, 6, 6))
    val referenceBudget = new BudgetedRules.Budget(Long.MaxValue)
    val reference       = new ChanceNode.ReplyTraversal(afterTurn, SearchControl.withBudget(referenceBudget))
      .generate(rolled)
      .getOrElse(fail("unbounded reference was interrupted"))
    val budget     = new BudgetedRules.Budget(Long.MaxValue)
    var clockReads = 0L
    val control    = new SearchControl(Some(budget), 1L, () => { clockReads += 1; 0L })
    val actual     = new ChanceNode.ReplyTraversal(afterTurn, control)
      .generate(rolled)
      .getOrElse(fail("far-future deadline was interrupted"))
    assertEquals(snapshot(actual), snapshot(reference))
    assertEquals(budget.used, referenceBudget.used)
    assert(clockReads > 0L)
    assert(clockReads * 4L < budget.used, s"clock reads=$clockReads, admitted work=${budget.used}")

  test("an expired deadline is checked before a warmed class can admit any more work"):
    val afterTurn = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val rolled    = afterTurn.withDicePool(List(6, 6, 6))
    val budget    = new BudgetedRules.Budget(Long.MaxValue)
    var now       = 0L
    val control   = new SearchControl(Some(budget), 1L, () => now)
    val traversal = new ChanceNode.ReplyTraversal(afterTurn, control)
    assert(traversal.generate(rolled).nonEmpty)
    val used = budget.used
    now = 2L
    assert(traversal.generate(rolled).isEmpty)
    assertEquals(budget.used, used)
    assert(control.isCancelled)

  test("a deadline firing inside a class discards replies before all deterministic work is admitted"):
    val afterTurn  = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val rolled     = afterTurn.withDicePool(List(6, 6, 6))
    val fullBudget = new BudgetedRules.Budget(Long.MaxValue)
    assert(new ChanceNode.ReplyTraversal(afterTurn, SearchControl.withBudget(fullBudget)).generate(rolled).nonEmpty)
    val budget     = new BudgetedRules.Budget(Long.MaxValue)
    var clockReads = 0
    val control    = new SearchControl(Some(budget), 1L, () => { clockReads += 1; if clockReads == 1 then 0L else 2L })
    assert(new ChanceNode.ReplyTraversal(afterTurn, control).generate(rolled).isEmpty)
    assert(control.isCancelled)
    assert(budget.used > 0L && budget.used < fullBudget.used)

  test("a chance expectation is incomplete at insufficient work and exact at its measured boundary"):
    val afterTurn                                            = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val bounds                                               = ExpectimaxSearch.utilityBounds(Color.White)
    def run(budget: BudgetedRules.Budget): ChanceNode.Result =
      val control = SearchControl.withBudget(budget)
      ChanceNode.expand(
        afterTurn,
        bounds,
        control,
        Double.NegativeInfinity,
        Double.PositiveInfinity,
        new SharedLeafPolicy(afterTurn, bounds, control)
      )
    val measured  = new BudgetedRules.Budget(Long.MaxValue)
    val reference = run(measured)
    assert(reference.outcome.isExact)
    val needed = measured.used
    assert(needed > 1L)
    assertEquals(run(new BudgetedRules.Budget(needed)).outcome.exactOption, reference.outcome.exactOption)
    for limit <- List(0L, 1L, needed - 1L) do
      val interrupted = run(new BudgetedRules.Budget(limit))
      assert(interrupted.outcome.isIncomplete)
      assert(interrupted.classesResolved < interrupted.classes)

  test("independent nested nodes and controls cannot share cancellation or prefix state"):
    val outerState   = parse("4k3/8/8/8/8/8/PP6/4K3 w - - 0 1")
    val innerState   = parse("4k3/8/8/8/8/8/8/4K3 b - - 0 12")
    val outerControl = SearchControl.withBudget(new BudgetedRules.Budget(Long.MaxValue))
    val outer        = new ChanceNode.ReplyTraversal(outerState, outerControl)
    val rolled       = outerState.withDicePool(List(1, 1))
    val before       = snapshot(outer.generate(rolled).getOrElse(fail("outer generation failed")))
    val innerControl = SearchControl.withBudget(new BudgetedRules.Budget(0))
    val inner        = new ChanceNode.ReplyTraversal(innerState, innerControl)
    assert(inner.generate(innerState.withDicePool(List(6))).isEmpty)
    assert(innerControl.isCancelled)
    assert(!outerControl.isCancelled)
    val after = snapshot(outer.generate(rolled).getOrElse(fail("inner cancellation leaked")))
    assertEquals(after, before)
    val independentInner = new ChanceNode.ReplyTraversal(innerState, outerControl)
    val innerReplies     =
      independentInner.generate(innerState.withDicePool(List(6))).getOrElse(fail("independent node failed"))
    assert(innerReplies.states.forall(_.activeColor == Color.White))
    assertEquals(snapshot(outer.generate(rolled).getOrElse(fail("inner prefixes leaked"))), before)

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  private def snapshot(replies: ChanceNode.Replies): (Boolean, Boolean, List[(String, Long)]) =
    (
      replies.capturesKing,
      replies.isForcedPass,
      replies.states.toList.map(state => (FenParser.serialize(state), state.zobristHash))
    )

  private class SharedLeafPolicy(afterTurn: GameState, bounds: UtilityBounds, control: SearchControl)
      extends ChanceNode.Policy:
    private val traversal  = new ChanceNode.ReplyTraversal(afterTurn, control)
    private val maximizing = bounds.isMaximizing(afterTurn.activeColor)
    var decisions          = 0

    def lowestValue: Double  = bounds.terminalLoss
    def highestValue: Double = bounds.terminalWin

    def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
      decisions += 1
      traversal.generate(rolled) match
        case None                                  => ChanceNode.Value.incomplete(control)
        case Some(replies) if replies.capturesKing =>
          ChanceNode.Value.exact(bounds.terminalFor(rolled.activeColor), lossTainted = !maximizing)
        case Some(replies) if replies.isForcedPass =>
          ChanceNode.Value.exact(Evaluator.evaluateMaterial(rolled.endTurn(), bounds.perspective).toDouble)
        case Some(replies) =>
          val scores = replies.states.map(Evaluator.evaluateMaterial(_, bounds.perspective).toDouble)
          ChanceNode.Value.exact(if maximizing then scores.max else scores.min)
