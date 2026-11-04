// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.concurrent.duration.*

/** Lazy scratch and explicit probe opt-out regressions for #444. Numerical references replay all 216 ordered rolls. */
class ChanceNodeScratchSuite extends FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private val sparse = ChanceNodeOracle.fixtures.head._2

  for
    (name, state) <- List(ChanceNodeOracle.fixtures.head, ChanceNodeOracle.fixtures.last)
    perspective   <- List(Color.White, Color.Black)
  do
    test(s"probe opt-out preserves exhaustive outcomes and strict windows: $name $perspective"):
      val bounds     = ExpectimaxSearch.utilityBounds(perspective)
      val maximizing = bounds.isMaximizing(state.activeColor)
      val oracle     = ChanceNodeOracle.expectationOver216(state, bounds, Evaluator.evaluateMaterial)
      val count      = ActiveDicePartitioner.partitionsFor(state).length
      val windows    = List(
        (Double.NegativeInfinity, Double.PositiveInfinity),
        (oracle, oracle),
        (oracle + 10.0, Double.PositiveInfinity),
        (Double.NegativeInfinity, oracle - 10.0)
      )
      for
        order         <- List((0 until count).toArray, (0 until count).reverse.toArray)
        (alpha, beta) <- windows
      do
        val legacy   = new ChanceNodeOracle.LeafPolicy(bounds, maximizing, Evaluator.evaluateMaterial, failSoft = true)
        val optedOut = new ChanceNodeOracle.LeafPolicy(bounds, maximizing, Evaluator.evaluateMaterial, failSoft = true):
          override def probingEnabled: Boolean = false
        val expected = ChanceNode.expand(state, bounds, SearchControl.Unbounded, alpha, beta, legacy, order)
        val actual   = ChanceNode.expand(state, bounds, SearchControl.Unbounded, alpha, beta, optedOut, order)
        assertEquals(actual, expected)
        assertEquals(optedOut.probeCalls, 0)
        val unboundedForRole = if maximizing then beta == Double.PositiveInfinity else alpha == Double.NegativeInfinity
        assertEquals(legacy.probeCalls, if unboundedForRole then 0 else 1)
        actual.outcome match
          case SearchOutcome.Exact(value, _)      => assertEquals(value, oracle)
          case SearchOutcome.UpperBound(value, _) => assert(oracle <= value && value < alpha)
          case SearchOutcome.LowerBound(value, _) => assert(oracle >= value && value > beta)
          case SearchOutcome.Incomplete(_)        => fail("unbounded invocation interrupted")

  test("existing probe overrides retain visiting order, callback frequency and rolled-state identity"):
    val bounds    = ExpectimaxSearch.utilityBounds(Color.Black)
    val classes   = ActiveDicePartitioner.partitionsFor(sparse)
    val order     = (0 until classes.length).reverse.toArray
    var offered   = Option.empty[IArray[GameState]]
    var probes    = 0
    var decisions = 0
    val policy    = new ChanceNode.Policy:
      def lowestValue: Double                                                        = 0.0
      def highestValue: Double                                                       = 10.0
      override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]] =
        probes += 1
        offered = Some(rolled)
        for position <- order.indices do
          assertEquals(rolled(position), classes(order(position)).roll.applyToState(sparse))
        Some(Array.fill(rolled.length)(ChanceNode.Value(SearchOutcome.UpperBound(10.0, 0L))))
      def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
        assert(rolled eq offered.get(classIndex))
        assertEquals(classIndex, decisions)
        decisions += 1
        ChanceNode.Value.exact(2.0)
    val result = ChanceNode.expand(sparse, bounds, SearchControl.Unbounded, 0.0, Double.PositiveInfinity, policy, order)
    assertEquals(result.outcome.exactOption, Some(2.0))
    assertEquals(probes, 1)
    assertEquals(decisions, classes.length)

  test("probe snapshots retain settled values when the caller later mutates its returned array"):
    val bounds  = ExpectimaxSearch.utilityBounds(Color.Black)
    val classes = ActiveDicePartitioner.partitionsFor(sparse)
    val values  = Array.tabulate(classes.length)(i => ChanceNode.Value.exact(i.toDouble))
    values(0) = ChanceNode.Value(SearchOutcome.UpperBound(10.0, 0L))
    val policy = new ChanceNode.Policy:
      def lowestValue: Double                                                                       = 0.0
      def highestValue: Double                                                                      = 10.0
      override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]]                = Some(values)
      def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
        assertEquals(classIndex, 0)
        values(1) = ChanceNode.Value(SearchOutcome.Incomplete(0L), lossTainted = true)
        ChanceNode.Value.exact(0.0)
    val expected = classes.indices.map(i => classes(i).weight.toDouble * i).sum / DiceRolls.totalOrderedRolls
    val result   = ChanceNode.expand(sparse, bounds, SearchControl.Unbounded, 0.0, Double.PositiveInfinity, policy)
    assertEquals(result.outcome.exactOption, Some(expected))
    assertEquals(result.classesResolved, classes.length)
    assert(!result.lossTainted)

  test("already-cancelled entries preserve telemetry and never consult probe capability or callbacks"):
    val bounds = ExpectimaxSearch.utilityBounds(Color.Black)
    val count  = ActiveDicePartitioner.partitionsFor(sparse).length
    val budget = new BudgetedRules.Budget(0)
    assert(!budget.admit())
    for control <- List(SearchControl.withBudget(budget), SearchControl.untilDeadline(1L, () => 2L)) do
      val policy = new ChanceNode.Policy:
        def lowestValue: Double              = 0.0
        def highestValue: Double             = 10.0
        override def probingEnabled: Boolean = fail("cancelled entry cannot request probe capability")
        override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]] = fail("cancelled probe")
        def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
          fail("cancelled decision")
      val result = ChanceNode.expand(sparse, bounds, control, 0.0, Double.PositiveInfinity, policy)
      assertEquals(result, ChanceNode.Result(SearchOutcome.Incomplete(0L), false, count, 0, false))

  test("cancellation during present or absent probes returns no value and resolves no classes"):
    for
      present <- List(false, true)
      alpha   <- List(0.0, 11.0)
    do
      var now     = 0L
      val control = SearchControl.untilDeadline(1L, () => now)
      val policy  = new ChanceNode.Policy:
        def lowestValue: Double                                                        = 0.0
        def highestValue: Double                                                       = 10.0
        override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]] =
          now = 2L
          if present then Some(Array.fill(rolled.length)(ChanceNode.Value.exact(2.0))) else None
        def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
          fail("cancelled decision")
      val result = ChanceNode.expand(
        sparse,
        ExpectimaxSearch.utilityBounds(Color.Black),
        control,
        alpha,
        Double.PositiveInfinity,
        policy
      )
      assert(result.outcome.isIncomplete)
      assertEquals(result.classesResolved, 0)

  test("nested independent expansions cannot replace outer scratch or change exact probe settlements"):
    val bounds = ExpectimaxSearch.utilityBounds(Color.Black)
    val inner  = new ChanceNode.Policy:
      override def probingEnabled: Boolean                                                          = false
      def lowestValue: Double                                                                       = 0.0
      def highestValue: Double                                                                      = 10.0
      def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
        ChanceNode.Value.exact(3.0)
    val outer = new ChanceNode.Policy:
      def lowestValue: Double                                                        = 0.0
      def highestValue: Double                                                       = 10.0
      override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]] =
        val nested = ChanceNode.expand(sparse, bounds, SearchControl.Unbounded, 0.0, Double.PositiveInfinity, inner)
        assertEquals(nested.outcome.exactOption, Some(3.0))
        Some(Array.fill(rolled.length)(ChanceNode.Value.exact(2.0)))
      def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
        fail("settled by probes")
    val result = ChanceNode.expand(sparse, bounds, SearchControl.Unbounded, 0.0, Double.PositiveInfinity, outer)
    assertEquals(result.outcome.exactOption, Some(2.0))
    assertEquals(result.classesResolved, result.classes)

  test("nested decisions preserve live outer suffixes and transformed child windows"):
    val bounds     = ExpectimaxSearch.utilityBounds(Color.Black)
    val outerState = ChanceNodeOracle.fixtures.last._2
    val inner      = new ChanceNode.Policy:
      override def probingEnabled: Boolean                                                          = false
      def lowestValue: Double                                                                       = -20.0
      def highestValue: Double                                                                      = 30.0
      def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
        ChanceNode.Value.exact(3.0)
    def one(nested: Boolean): (ChanceNode.Result, List[(Int, Double, Double)]) =
      val windows = scala.collection.mutable.ListBuffer.empty[(Int, Double, Double)]
      val outer   = new ChanceNode.Policy:
        def lowestValue: Double                                                        = 0.0
        def highestValue: Double                                                       = 10.0
        override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]] =
          Some(Array.fill(rolled.length)(ChanceNode.Value(SearchOutcome.UpperBound(5.0, 0L))))
        def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
          windows += ((classIndex, alpha, beta))
          if nested then
            val result = ChanceNode.expand(sparse, bounds, SearchControl.Unbounded, 0.0, 4.0, inner)
            assertEquals(result.outcome.exactOption, Some(3.0))
          ChanceNode.Value.exact(2.0)
      val result = ChanceNode.expand(outerState, bounds, SearchControl.Unbounded, 1.5, 2.5, outer)
      (result, windows.toList)
    val expected = one(false)
    assertEquals(expected._1.outcome.exactOption, Some(2.0))
    assertEquals(expected._2.length, expected._1.classes)
    assertEquals(one(true), expected)
