// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.search.ChanceNodeOracle.{fixtures, LeafPolicy}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*

/** The shared weighted chance node (#394) against independent oracles on the public synthetic fixtures F1–F4 and F7–F10
  * of the traversal and search contracts page: exact expectations over all 216 ordered rolls and the 56 canonical
  * weighted rolls, forced-pass mass preservation, pruning against a no-pruning oracle across windows and roll orders,
  * the mandatory-move bound invariant (F9), cancellation, and the preconditions the mechanism enforces.
  */
class ChanceNodeSuite extends ScalaCheckSuite:

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(60)

  private val material: (GameState, Color) => Int = Evaluator.evaluateMaterial

  /** A sharper leaf than material, so probes and exact values do not coincide everywhere: material plus a bonus for
    * every square the mover's pieces could move to — cheap, deterministic and inside the non-terminal range.
    */
  private val spread: (GameState, Color) => Int = (state, color) =>
    val own = if color.isWhite then state.whitePieces else state.blackPieces
    Evaluator.evaluateMaterial(state, color) + 3 * own.count - 2 * state.kings.count

  private def boundsFor(perspective: Color): UtilityBounds = ExpectimaxSearch.utilityBounds(perspective)

  private def expand(
      afterTurn: GameState,
      bounds: UtilityBounds,
      policy: ChanceNode.Policy,
      alpha: Double = Double.NegativeInfinity,
      beta: Double = Double.PositiveInfinity,
      control: SearchControl = SearchControl.Unbounded
  ): ChanceNode.Result =
    ChanceNode.expand(afterTurn, bounds, control, alpha, beta, policy)

  private def leafPolicy(bounds: UtilityBounds, afterTurn: GameState, leaf: (GameState, Color) => Int) =
    new LeafPolicy(bounds, bounds.isMaximizing(afterTurn.activeColor), leaf)

  // ---- Exact expectations against independent enumeration ----

  for (name, afterTurn) <- fixtures; perspective <- List(Color.White, Color.Black) do
    test(s"$name, perspective $perspective: the expectation equals the 216-roll and 56-roll oracles bit for bit"):
      val bounds = boundsFor(perspective)
      val policy = leafPolicy(bounds, afterTurn, material)
      val result = expand(afterTurn, bounds, policy)
      val exact  = result.outcome.exactOption.getOrElse(fail(s"expected an exact expectation, got $result"))
      assertEquals(exact, ChanceNodeOracle.expectationOver216(afterTurn, bounds, material))
      assertEquals(exact, ChanceNodeOracle.expectationOver56(afterTurn, bounds, material))
      assertEquals(result.classes, ActiveDicePartitioner.partitionsFor(afterTurn).length)
      assertEquals(result.classesResolved, result.classes, "an open window expands every class")
      assertEquals(policy.probeCalls, 0, "an open window never asks for probes")
      assertEquals(policy.decideCalls, result.classes)

  test("F4: every class without a legal turn is valued as a forced pass with its full weight"):
    // White's only pawn is blocked and its king and rook are free: the pawn-only classes (P, PP, PPP) have dice in
    // hand but no legal turn, and the empty class has none either. All of them must reach the policy as forced passes.
    val afterTurn = fixtures.find(_._1.startsWith("F4")).get._2
    val bounds    = boundsFor(Color.Black)
    val policy    = leafPolicy(bounds, afterTurn, material)
    val classes   = ActiveDicePartitioner.partitionsFor(afterTurn)
    val result    = expand(afterTurn, bounds, policy)
    assert(result.outcome.isExact)
    val passMass = policy.decidedClasses.toList.collect {
      case index if TurnGenerator.generateAllLegalTurnPaths(classes(index).roll.applyToState(afterTurn)).isEmpty =>
        classes(index).weight
    }.sum
    assertEquals(passMass, ChanceNodeOracle.forcedPassMass(afterTurn))
    assert(passMass > 1, s"F4 must have forced passes beyond the empty class, got mass $passMass")
    assertEquals(
      policy.forcedPassesSeen,
      policy.decidedClasses.toList.count { index =>
        TurnGenerator.generateAllLegalTurnPaths(classes(index).roll.applyToState(afterTurn)).isEmpty
      }
    )
    assertEquals(classes.map(_.weight).sum, DiceRolls.totalOrderedRolls)

  test("F7: a class whose mover can capture the king is terminal and taints the node for the losing perspective"):
    val afterTurn = fixtures.find(_._1.startsWith("F7")).get._2 // White to roll; Nb3xc5 takes the king on a knight die
    val forBlack  = expand(afterTurn, boundsFor(Color.Black), leafPolicy(boundsFor(Color.Black), afterTurn, material))
    val forWhite  = expand(afterTurn, boundsFor(Color.White), leafPolicy(boundsFor(Color.White), afterTurn, material))
    assert(forBlack.lossTainted, "Black loses its king on every knight class")
    assert(!forWhite.lossTainted, "White's own capture is a win, not a taint")
    val knightMass =
      ActiveDicePartitioner.partitionsFor(afterTurn).filter(e => (e.roll.dieMask & 2) != 0).map(_.weight).sum
    assertEquals(knightMass, 216 - 125, "a lone knight die appears in 91 of the 216 ordered rolls")
    val blackValue = forBlack.outcome.exactOption.get
    assert(
      blackValue < boundsFor(Color.Black).minNonTerminal,
      s"the loss mass must drag the expectation below the evaluator floor: $blackValue"
    )

  // ---- Pruning against the no-pruning oracle ----

  private val windowed: Gen[(Int, Color, Double, Double, Boolean, Boolean)] =
    for
      fixture     <- Gen.choose(0, fixtures.length - 1)
      perspective <- Gen.oneOf(Color.White, Color.Black)
      offsetA     <- Gen.frequency(3 -> Gen.const(Double.NegativeInfinity), 7 -> Gen.choose(-400.0, 400.0))
      offsetB     <- Gen.frequency(3 -> Gen.const(Double.PositiveInfinity), 7 -> Gen.choose(-400.0, 400.0))
      failSoft    <- Gen.oneOf(true, false)
      probing     <- Gen.oneOf(true, false)
    yield (fixture, perspective, offsetA, offsetB, failSoft, probing)

  private def checkAgainstOracle(
      oracle: Double,
      alpha: Double,
      beta: Double,
      result: ChanceNode.Result
  ): Prop =
    result.outcome match
      case SearchOutcome.Exact(value, _)      => (value ?= oracle) :| "exact must equal the oracle"
      case SearchOutcome.UpperBound(value, _) =>
        (oracle <= value) :| s"upper bound $value below the oracle $oracle" &&
        (value < alpha) :| s"a fail-low must be strictly below alpha $alpha, got $value"
      case SearchOutcome.LowerBound(value, _) =>
        (oracle >= value) :| s"lower bound $value above the oracle $oracle" &&
        (value > beta) :| s"a fail-high must be strictly above beta $beta, got $value"
      case SearchOutcome.Incomplete(_) => falsified :| "an unbounded control never cancels"

  property("every outcome under a window is exact or a proven strict bound, with or without probes and fail-soft"):
    forAll(windowed) { (fixture, perspective, offsetA, offsetB, failSoft, probing) =>
      val (_, afterTurn) = fixtures(fixture)
      val bounds         = boundsFor(perspective)
      val oracle         = ChanceNodeOracle.expectationOver56(afterTurn, bounds, spread)
      val alpha          = if offsetA.isInfinite then offsetA else oracle + offsetA
      val beta           = if offsetB.isInfinite then offsetB else math.max(alpha, oracle + offsetB)
      val maximizing     = bounds.isMaximizing(afterTurn.activeColor)
      val policy         = new LeafPolicy(bounds, maximizing, spread, failSoft = failSoft, probing = probing)
      val result         = expand(afterTurn, bounds, policy, alpha, beta)
      checkAgainstOracle(oracle, alpha, beta, result) &&
      (result.classesResolved <= result.classes) :| "resolved classes never exceed the partition"
    }

  property("a caller-owned visiting order never changes an exact expectation"):
    val gen = for
      fixture     <- Gen.choose(0, fixtures.length - 1)
      perspective <- Gen.oneOf(Color.White, Color.Black)
      seed        <- Gen.choose(0L, Long.MaxValue)
    yield (fixture, perspective, seed)
    forAll(gen) { (fixture, perspective, seed) =>
      val (_, afterTurn) = fixtures(fixture)
      val bounds         = boundsFor(perspective)
      val classes        = ActiveDicePartitioner.partitionsFor(afterTurn)
      val order          = new scala.util.Random(seed).shuffle((0 until classes.length).toList).toArray
      val maximizing     = bounds.isMaximizing(afterTurn.activeColor)
      val reference      = expand(afterTurn, bounds, new LeafPolicy(bounds, maximizing, spread))
      val reordered      = ChanceNode.expand(
        afterTurn,
        bounds,
        SearchControl.Unbounded,
        Double.NegativeInfinity,
        Double.PositiveInfinity,
        new LeafPolicy(bounds, maximizing, spread),
        order
      )
      (reordered.outcome ?= reference.outcome) :| "bit-identical under any order" &&
      (classes.toList ?= ActiveDicePartitioner.partitionsFor(afterTurn).toList) :| "the shared partition is untouched"
    }

  test("a Star2 probe sum below alpha settles the node before a single class is expanded"):
    val afterTurn = fixtures.find(_._1.startsWith("F10")).get._2
    val bounds    = boundsFor(Color.Black) // White (MIN for Black) is to roll
    val oracle    = ChanceNodeOracle.expectationOver56(afterTurn, bounds, spread)
    val policy    = new LeafPolicy(bounds, maximizing = false, spread, probing = true)
    val result    = expand(afterTurn, bounds, policy, alpha = oracle + 1000.0)
    assert(result.probePruned, s"expected a probe cut, got $result")
    assertEquals(result.classesResolved, 0)
    assertEquals(policy.decideCalls, 0, "a probe-pruned node never expands a class")
    assertEquals(policy.probeCalls, 1)
    result.outcome match
      case SearchOutcome.UpperBound(value, _) => assert(value >= oracle && value < oracle + 1000.0, s"$value")
      case other                              => fail(s"expected an upper bound, got $other")

  test("an exact probe settles its class: forced passes and terminal classes are never decided twice"):
    val afterTurn   = fixtures.find(_._1.startsWith("F7")).get._2
    val bounds      = boundsFor(Color.Black)
    val classes     = ActiveDicePartitioner.partitionsFor(afterTurn)
    val oracle      = ChanceNodeOracle.expectationOver56(afterTurn, bounds, spread)
    val policy      = new LeafPolicy(bounds, maximizing = false, spread, probing = true)
    val result      = expand(afterTurn, bounds, policy, alpha = oracle - 1.0) // bounded, but not prunable
    val settledMass = classes.indices.filter { index =>
      val rolled = classes(index).roll.applyToState(afterTurn)
      val paths  = TurnGenerator.generateAllLegalTurnPaths(rolled)
      paths.isEmpty || paths.exists(p => p.foldLeft(rolled)((s, m) => s.makeMove(m)).kings.count < 2)
    }
    assertEquals(result.outcome.exactOption, Some(oracle))
    assert(settledMass.nonEmpty, "F7 has terminal and forced-pass classes")
    assert(policy.decidedClasses.toSet.intersect(settledMass.toSet).isEmpty, "settled classes are not re-decided")
    assertEquals(policy.decideCalls, classes.length - settledMass.length)

  test("the static pass value is not a bound for a roll that admits a legal turn (mandatory knight move)"):
    // Every knight move from a8 lands on a square White's pawns attack, so with a knight die in hand Black must put
    // the knight en prise: a threat-aware evaluator scores that mandatory move above the pass Black is not allowed
    // to take. A probe that used the pass value as an upper bound on White's expectation would therefore bound the
    // knight classes too low — a weighted pass sum strictly below the true expectation — and prune a candidate that
    // is in fact best. The policy probes legal replies only, and stays exact or soundly bounded under every window.
    val afterTurn = ChanceNodeOracle.mandatoryKnight
    val bounds    = boundsFor(Color.White)
    val leaf      = ChanceNodeOracle.threatAware
    val classes   = ActiveDicePartitioner.partitionsFor(afterTurn)
    val worsened  = classes.indices.filter { index =>
      val rolled = classes(index).roll.applyToState(afterTurn)
      TurnGenerator.generateAllLegalTurnPaths(rolled).nonEmpty &&
      ChanceNodeOracle.decisionValue(rolled, bounds, leaf) > ChanceNodeOracle.passValue(rolled, bounds, leaf)
    }
    assert(
      worsened.nonEmpty,
      "the fixture must contain a class whose mandatory move is worse for the mover than passing"
    )
    val oracle  = ChanceNodeOracle.expectationOver56(afterTurn, bounds, leaf)
    val passSum = classes.indices.map { index =>
      val rolled = classes(index).roll.applyToState(afterTurn)
      classes(index).weight.toDouble * ChanceNodeOracle.passValue(rolled, bounds, leaf)
    }.sum / DiceRolls.totalOrderedRolls
    assert(passSum < oracle, s"a pass-based Star2 sum ($passSum) would undercut the true expectation ($oracle)")
    for alpha <- List(oracle - 50.0, (passSum + oracle) / 2, oracle, oracle + 0.5, oracle + 50.0) do
      val policy = new LeafPolicy(bounds, maximizing = false, leaf, probing = true)
      val result = expand(afterTurn, bounds, policy, alpha = alpha)
      result.outcome match
        case SearchOutcome.Exact(value, _)      => assertEquals(value, oracle, s"alpha $alpha")
        case SearchOutcome.UpperBound(value, _) => assert(oracle <= value && value < alpha, s"alpha $alpha: $result")
        case other                              => fail(s"alpha $alpha: unexpected $other")

  test("F9 keeps its mandatory rook move exact under a pruning window"):
    val afterTurn = fixtures.find(_._1.startsWith("F9")).get._2
    val bounds    = boundsFor(Color.White)
    val oracle    = ChanceNodeOracle.expectationOver56(afterTurn, bounds, ChanceNodeOracle.threatAware)
    for alpha <- List(oracle - 1.0, oracle, oracle + 1.0) do
      val policy = new LeafPolicy(bounds, maximizing = false, ChanceNodeOracle.threatAware, probing = true)
      val result = expand(afterTurn, bounds, policy, alpha = alpha)
      result.outcome match
        case SearchOutcome.Exact(value, _)      => assertEquals(value, oracle, s"alpha $alpha")
        case SearchOutcome.UpperBound(value, _) => assert(oracle <= value && value < alpha, s"alpha $alpha: $result")
        case other                              => fail(s"alpha $alpha: unexpected $other")

  test("a bounded child that does not settle the parent is re-searched with an open window"):
    // Without probes the unexpanded classes are bounded by the evaluator's ceiling, so only the last class can fail
    // low against an alpha just above the expectation. A policy that answers that fail-low with the widest bound still
    // true never settles the parent, so the class is searched a second time with an open window and the node still
    // ends exact.
    val afterTurn = fixtures.find(_._1.startsWith("F10")).get._2
    val bounds    = boundsFor(Color.Black)
    val oracle    = ChanceNodeOracle.expectationOver56(afterTurn, bounds, spread)
    val alpha     = oracle + 10.0
    val policy    = new LeafPolicy(bounds, maximizing = false, spread, failSoft = true, looseBounds = true)
    val result    = expand(afterTurn, bounds, policy, alpha = alpha)
    assert(policy.decideCalls > policy.decidedClasses.toSet.size, s"some class must have been searched twice: $policy")
    result.outcome match
      case SearchOutcome.Exact(value, _)      => assertEquals(value, oracle)
      case SearchOutcome.UpperBound(value, _) => assert(oracle <= value && value < alpha, s"$result")
      case other                              => fail(s"unexpected $other")

  // ---- Cancellation ----

  test("a control that is already cancelled yields Incomplete before any callback"):
    val afterTurn = fixtures.head._2
    val bounds    = boundsFor(Color.Black)
    val expired   = SearchControl.untilDeadline(deadlineNanos = 1L, clockNanos = () => 5L)
    val policy    = new LeafPolicy(bounds, maximizing = false, material, probing = true)
    val result    = expand(afterTurn, bounds, policy, alpha = 0.0, control = expired)
    assert(result.outcome.isIncomplete)
    assertEquals(policy.probeCalls + policy.decideCalls, 0)

  test("a budget that runs out inside a reply generation ends the node as Incomplete, never as a forced pass"):
    val afterTurn = fixtures.find(_._1.startsWith("F10")).get._2
    val bounds    = boundsFor(Color.Black)
    val ample     = new BudgetedRules.Budget(Long.MaxValue)
    val reference = expand(
      afterTurn,
      bounds,
      new LeafPolicy(bounds, false, material, SearchControl.withBudget(ample)),
      control = SearchControl.withBudget(ample)
    )
    assert(reference.outcome.isExact)
    val exact = ample.used
    assert(exact > 0L)
    val atExact = new BudgetedRules.Budget(exact)
    val same    = expand(
      afterTurn,
      bounds,
      new LeafPolicy(bounds, false, material, SearchControl.withBudget(atExact)),
      control = SearchControl.withBudget(atExact)
    )
    assertEquals(same.outcome.exactOption, reference.outcome.exactOption)
    assert(!atExact.exhausted)
    val short   = new BudgetedRules.Budget(exact - 1)
    val control = SearchControl.withBudget(short)
    val policy  = new LeafPolicy(bounds, false, material, control)
    val cut     = expand(afterTurn, bounds, policy, control = control)
    assert(cut.outcome.isIncomplete, s"expected Incomplete, got $cut")
    assert(short.exhausted && control.isCancelled)
    assert(cut.classesResolved < cut.classes)
    assertEquals(policy.forcedPassesSeen, 0, "an interrupted generation is not a pass")

  test("a clock that expires between classes stops the loop and reports the classes already resolved"):
    val afterTurn = fixtures.find(_._1.startsWith("F1")).get._2
    val bounds    = boundsFor(Color.Black)
    var reads     = 0
    val clock     = () =>
      reads += 1
      reads.toLong
    // Check 1 at entry, check 2 before class 0, check 3 before class 1 → cancelled before the second class.
    val control = SearchControl.untilDeadline(deadlineNanos = 3L, clockNanos = clock)
    val policy  = new LeafPolicy(bounds, maximizing = false, material, control)
    val result  = expand(afterTurn, bounds, policy, control = control)
    assert(result.outcome.isIncomplete)
    assertEquals(result.classesResolved, 1)
    assertEquals(policy.decideCalls, 1)

  // ---- Preconditions ----

  test("a chance node refuses a position that still holds dice, or lacks a king"):
    val bounds = boundsFor(Color.White)
    val rolled = fixtures.head._2.withDicePool(List(6, 6, 6))
    intercept[IllegalArgumentException](expand(rolled, bounds, leafPolicy(bounds, rolled, material)))
    val kingless = ChanceNodeOracle.parse("8/8/8/8/8/8/8/4K3 w - - 0 1")
    intercept[IllegalArgumentException](expand(kingless, bounds, leafPolicy(bounds, kingless, material)))

  test("a policy whose declared range leaves the utility scale, or a bad visiting order, is rejected"):
    val afterTurn = fixtures.head._2
    val bounds    = boundsFor(Color.Black)
    val tooWide   = new ChanceNode.Policy:
      def lowestValue: Double  = bounds.terminalLoss - 1.0
      def highestValue: Double = bounds.terminalWin
      def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
        ChanceNode.Value.exact(0.0)
    intercept[IllegalArgumentException](expand(afterTurn, bounds, tooWide))
    val policy = leafPolicy(bounds, afterTurn, material)
    val n      = ActiveDicePartitioner.partitionsFor(afterTurn).length
    for bad <- List(Array(0), Array.fill(n)(0), (0 until n).toArray.map(_ + 1)) do
      intercept[IllegalArgumentException](
        ChanceNode.expand(
          afterTurn,
          bounds,
          SearchControl.Unbounded,
          Double.NegativeInfinity,
          Double.PositiveInfinity,
          policy,
          bad
        )
      )

  test("a probe answer of the wrong length is rejected, and a wrong-direction probe bound is ignored"):
    val afterTurn = fixtures.find(_._1.startsWith("F8")).get._2
    val bounds    = boundsFor(Color.Black)
    val oracle    = ChanceNodeOracle.expectationOver56(afterTurn, bounds, material)
    val tooShort  = new LeafPolicy(bounds, maximizing = false, material):
      override def probe(rolled: IArray[GameState]) = Some(Array(ChanceNode.Value.exact(0.0)))
    intercept[IllegalArgumentException](expand(afterTurn, bounds, tooShort, alpha = oracle))
    val wrongWay = new LeafPolicy(bounds, maximizing = false, material):
      override def probe(rolled: IArray[GameState]) =
        Some(Array.fill(rolled.length)(ChanceNode.Value(SearchOutcome.LowerBound(-1e6, 0L))))
    val result = expand(afterTurn, bounds, wrongWay, alpha = oracle - 1.0)
    assertEquals(result.outcome.exactOption, Some(oracle), "a useless probe leaves the node exact")
    assert(!result.probePruned)

  test("Replies.generate reports king captures, forced passes and interrupted budgets distinctly"):
    val f7             = fixtures.find(_._1.startsWith("F7")).get._2
    val knight         = f7.withDicePool(List(2, 1, 1))
    val Some(captured) = ChanceNode.Replies.generate(knight, SearchControl.Unbounded): @unchecked
    assert(captured.capturesKing && !captured.isForcedPass && captured.count == 0)
    val f4            = fixtures.find(_._1.startsWith("F4")).get._2
    val Some(blocked) = ChanceNode.Replies.generate(f4.withDicePool(List(1, 1, 1)), SearchControl.Unbounded): @unchecked
    assert(blocked.isForcedPass)
    val Some(empty) = ChanceNode.Replies.generate(f4, SearchControl.Unbounded): @unchecked
    assert(empty.isForcedPass, "no dice at all is a forced pass without a generator call")
    val Some(normal) = ChanceNode.Replies.generate(f4.withDicePool(List(6, 4, 1)), SearchControl.Unbounded): @unchecked
    assertEquals(normal.count, TurnGenerator.generateAllLegalTurnPaths(f4.withDicePool(List(6, 4, 1))).length)
    assert(normal.states.forall(s => s.activeColor == Color.Black && s.dicePool.isEmpty))
    val starved = new BudgetedRules.Budget(1)
    assertEquals(ChanceNode.Replies.generate(f4.withDicePool(List(6, 4, 1)), SearchControl.withBudget(starved)), None)
    assert(starved.exhausted)
