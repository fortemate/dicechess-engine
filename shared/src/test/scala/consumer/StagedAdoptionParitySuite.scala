// SPDX-License-Identifier: AGPL-3.0-only
package consumer

import dicechess.engine.domain.*
import dicechess.engine.search.*
import munit.FunSuite

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

/** External-package (`package consumer`) staged adoption and fixed-work parity suite for Epic #390 (#398).
  *
  * Compiles and runs on JVM, Scala.js, and WebAssembly against public APIs only, verifying:
  *   - Wave 1: `PassedPawns.bitboard`, `AttackerTypes` / `DiceRolls` / `PieceValues`, and `EvaluationCache` vs
  *     `TranspositionTable` ownership boundaries.
  *   - Wave 2: `TurnGenerator.forEachLegalTurnState` and `BudgetedRules.visitTurnStates` / `foldTurnStates` `endTurn()`
  *     ownership (no double-`endTurn()` on normal turns, no `endTurn()` on king captures) and `SearchOutcome` /
  *     `SearchControl` root fallback priority.
  *   - Wave 3: `ChanceNode.expand` with consumer-owned policy, urgency ordering in invocation-local scratch, and sound
  *     legal-reply Star2 bounds (mandatory-move invariant).
  *   - Wave 4: Fixed-work before/after parity between legacy consumer traversal/accumulation loops and the shared
  *     engine APIs across score, selected legal path, candidate multiplicity, post-call `scala.util.Random` state,
  *     budget boundaries (`0`, `1`, `exact - 1`, `exact`), and concurrent independent invocations.
  */
class StagedAdoptionParitySuite extends FunSuite:

  private given ExecutionContext = ExecutionContext.global

  private def parse(dfen: String): GameState = FenParser.parse(dfen).fold(fail(_), identity)

  /** Consumer-owned synthetic evaluator combining material, passed-pawn rank bonuses, and hanging-piece risk.
    *
    * All prices, rank bonuses, and weights live in the consumer (`package consumer`), never in the shared engine.
    */
  final private class SyntheticConsumerEvaluator(
      prices: PieceValues,
      passedBonusByAdv: IArray[Int],
      hangWeightNum: Int
  ):
    def evaluate(state: GameState, perspective: Color): Int =
      val mat      = Evaluator.evaluateMaterial(state, perspective)
      val pawnTerm = passedBonus(state, perspective) - passedBonus(state, perspective.opponent)
      val hangTerm = hangingPenalty(state, perspective.opponent) - hangingPenalty(state, perspective)
      mat + pawnTerm + hangTerm

    private def passedBonus(state: GameState, side: Color): Int =
      var bits  = PassedPawns.bitboard(state, side).value
      var total = 0
      while bits != 0L do
        val sq   = java.lang.Long.numberOfTrailingZeros(bits)
        val rank = sq / 8 + 1
        val adv  = if side.isWhite then rank - 1 else 8 - rank
        total += passedBonusByAdv(adv)
        bits &= bits - 1L
      total

    private def hangingPenalty(state: GameState, victimSide: Color): Int =
      var remaining = PieceSafety.hangingSquares(state, victimSide).value
      var penalty   = 0
      while remaining != 0L do
        val sq    = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(remaining))
        val mask  = AttackerTypes.mask(state, sq, victimSide.opponent)
        val rolls = AttackerTypes.availableRolls(mask)
        val cheap = prices.minimumOrElse(mask, empty = 0)
        penalty += (rolls * hangWeightNum - cheap) / DiceRolls.totalOrderedRolls
        remaining &= remaining - 1L
      penalty

  private val syntheticEvaluator = new SyntheticConsumerEvaluator(
    prices = PieceValues(pawn = 100, knight = 310, bishop = 330, rook = 500, queen = 900, king = 10_000),
    passedBonusByAdv = IArray(0, 5, 12, 25, 45, 80, 140),
    hangWeightNum = 60
  )

  private val rootFixtures: List[String] = List(
    "4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP",                                   // F8 commuting pawn pushes (White)
    "4k3/p6p/8/8/8/8/8/4K3 b - - 0 1 PP",                                   // F8 mirrored commuting pawn pushes (Black)
    "4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RP",                                // F6 inherited en-passant target
    "4k3/8/8/8/8/8/P7/R3K2R w KQ - 0 1 KR",                                 // F5 castling consumes 2 dice
    "8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP",                                 // F7 immediate king capture
    "4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1 PPP",                                 // F4 forced pass
    "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP" // F10 opening tree
  )

  private def pickBestWithTieBreak(scored: Seq[ScoredSequence], rng: Random): (ScoredSequence, Int) =
    val bestScore = scored.map(_.score).max
    val shortlist = scored.filter(_.score == bestScore)
    val chosen    = shortlist(rng.nextInt(shortlist.size))
    (chosen, shortlist.size)

  /** Legacy (pre-adoption) 1-ply consumer root scorer using `generateAllLegalTurnPaths` + manual `foldLeft` replay. */
  private def legacyRootSelect(
      state: GameState,
      evalFn: (GameState, Color) => Int,
      rng: Random
  ): Option[(ScoredSequence, Int)] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then None
    else
      val mover  = state.activeColor
      val scored = paths.map { path =>
        val beforeLast = path.init.foldLeft(state)(_.makeMove(_))
        if beforeLast.isKingCapture(path.last) then ScoredSequence(path, SearchScoring.TerminalWinScore)
        else ScoredSequence(path, evalFn(beforeLast.makeMove(path.last).endTurn(), mover))
      }
      Some(pickBestWithTieBreak(scored, rng))

  private def scoreCandidatesUntilCancelled(
      candidates: Vector[(List[Move], GameState, Boolean)],
      mover: Color,
      cache: EvaluationCache,
      control: SearchControl
  ): Vector[ScoredSequence] =
    val completed = Vector.newBuilder[ScoredSequence]
    val it        = candidates.iterator
    while it.hasNext && !control.isCancelled do
      val (path, afterTurn, _) = it.next()
      completed += ScoredSequence(path, cache(afterTurn, mover))
    completed.result()

  private def unbudgetedRootCandidates(state: GameState): Vector[(List[Move], GameState, Boolean)] =
    val b = Vector.newBuilder[(List[Move], GameState, Boolean)]
    TurnGenerator.forEachLegalTurnState(state) { (moves, len, afterState, isKingCapture) =>
      b += ((moves.take(len).toList, afterState, isKingCapture))
      true
    }
    b.result()

  private def selectFromFoldedCandidates(
      candidates: Vector[(List[Move], GameState, Boolean)],
      mover: Color,
      cache: EvaluationCache,
      control: SearchControl,
      rng: Random
  ): Option[(ScoredSequence, Int, EvaluationCache)] =
    if candidates.isEmpty then None
    else
      // Fallback priority 1: immediate root king capture before any control/cache check
      val wins = candidates.collect { case (path, _, true) => ScoredSequence(path, SearchScoring.TerminalWinScore) }
      if wins.nonEmpty then
        val (chosen, count) = pickBestWithTieBreak(wins, rng)
        Some((chosen, count, cache))
      else
        val scored = scoreCandidatesUntilCancelled(candidates, mover, cache, control)
        if scored.isEmpty then
          // Fallback priority 3: first canonical legal turn when zero candidates completed
          Some((ScoredSequence(candidates.head._1, 0), 1, cache))
        else
          // Fallback priority 2: best completed (Exact) candidate with seeded tie-breaking
          val (chosen, count) = pickBestWithTieBreak(scored, rng)
          Some((chosen, count, cache))

  /** Adopted (Wave 1 + Wave 2) consumer root selector using `BudgetedRules.foldTurnStates`, `EvaluationCache`, and
    * `SearchControl`, falling back to unbudgeted root legal-turn enumeration if the traversal budget expires so the
    * 4-tier `ControlledSearch` root fallback contract always holds.
    */
  private def adoptedRootSelect(
      state: GameState,
      evalFn: (GameState, Color) => Int,
      control: SearchControl,
      rng: Random
  ): Option[(ScoredSequence, Int, EvaluationCache)] =
    val cache  = new EvaluationCache(evalFn)
    val budget = control.budget.getOrElse(new BudgetedRules.Budget(Long.MaxValue))
    val folded = BudgetedRules.foldTurnStates(state, Vector.empty[(List[Move], GameState, Boolean)], budget) {
      (acc, path, afterState, isKingCapture) =>
        (acc :+ (path, afterState, isKingCapture), true)
    }
    val candidates = folded match
      case BudgetedRules.Outcome.Complete(res, _) => res.value
      case BudgetedRules.Outcome.Incomplete(_)    => unbudgetedRootCandidates(state)
    selectFromFoldedCandidates(candidates, state.activeColor, cache, control, rng)

  test(
    "Wave 1 & Wave 2: adopted visitor + EvaluationCache matches legacy replay on score, path, multiplicity and RNG under unbounded and finite fixed-work budgets"
  ):
    val controlFactories: List[() => SearchControl] = List(
      () => SearchControl.Unbounded,
      () => SearchControl.withBudget(new BudgetedRules.Budget(16_384L))
    )
    assertEquals(controlFactories(1)().deadlineNanos, Long.MaxValue)
    for
      dfen      <- rootFixtures
      mkControl <- controlFactories
      seed      <- 0L until 10L
    do
      val state      = parse(dfen)
      val legacyRng  = new Random(seed)
      val adoptedRng = new Random(seed)
      val legacyRes  = legacyRootSelect(state, syntheticEvaluator.evaluate, legacyRng)
      val adoptedRes = adoptedRootSelect(state, syntheticEvaluator.evaluate, mkControl(), adoptedRng)
      assertEquals(adoptedRes.map(t => (t._1, t._2)), legacyRes, s"mismatch on $dfen seed $seed")
      assertEquals(adoptedRng.nextLong(), legacyRng.nextLong(), s"RNG state diverged on $dfen seed $seed")

    // Verify deterministic budget boundary behaviour: 0, 1, exact - 1 fall back to first canonical turn with 0 evals; exact matches legacy
    val f8            = parse("4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP")
    val canonicalHead = TurnGenerator.generateAllLegalTurnPaths(f8).head
    val exactUsed     =
      BudgetedRules.visitTurnStates(f8, new BudgetedRules.Budget(Long.MaxValue))((_, _, _, _) => true) match
        case BudgetedRules.Outcome.Complete(_, used) => used
        case other                                   => fail(s"expected Complete, got $other")
    assert(exactUsed > 1L)
    for limit <- List(0L, 1L, exactUsed - 1L) do
      val tightControl = SearchControl.withBudget(new BudgetedRules.Budget(limit))
      val fallback     = adoptedRootSelect(f8, syntheticEvaluator.evaluate, tightControl, new Random(1)).get
      assertEquals(fallback._1, ScoredSequence(canonicalHead, 0))
      assertEquals(fallback._3.evaluatorCalls, 0L)
    val exactControl = SearchControl.withBudget(new BudgetedRules.Budget(exactUsed))
    assertEquals(
      adoptedRootSelect(f8, syntheticEvaluator.evaluate, exactControl, new Random(7)).map(t => (t._1, t._2)),
      legacyRootSelect(f8, syntheticEvaluator.evaluate, new Random(7))
    )

  test(
    "Wave 2: visitor callback states never double-apply endTurn() on normal turns or apply endTurn() on king captures"
  ):
    val f6        = parse("4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RP")
    var sawNormal = false
    TurnGenerator.forEachLegalTurnState(f6) { (moves, len, afterState, isKingCapture) =>
      val path     = moves.take(len).toList
      val replayed = path.foldLeft(f6)(_.makeMove(_)).endTurn()
      assert(!isKingCapture)
      sawNormal = true
      assertEquals(afterState, replayed)
      assertEquals(afterState.activeColor, Color.Black)
      assert(afterState.flags.isDicePoolEmpty)
      true
    }
    assert(sawNormal)

    val f7         = parse("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP")
    var sawCapture = false
    TurnGenerator.forEachLegalTurnState(f7) { (moves, len, afterState, isKingCapture) =>
      val path = moves.take(len).toList
      if isKingCapture then
        sawCapture = true
        val rawReplay = path.foldLeft(f7)(_.makeMove(_))
        assertEquals(afterState, rawReplay)
        assertEquals(afterState.activeColor, Color.White, "king capture must not call endTurn()")
        assertEquals((afterState.kings & afterState.blackPieces).value, 0L)
      true
    }
    assert(sawCapture)

  test("Wave 2: root fallback priority under exhausted budget and mid-candidate deadline cancellation (F4, F7, F10)"):
    val f4               = parse("4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1 PPP")
    val f7               = parse("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP")
    val f10              = parse("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP")
    val canonicalF10Head = TurnGenerator.generateAllLegalTurnPaths(f10).head

    for mkCancelled <- List(
        () => SearchControl.withBudget(new BudgetedRules.Budget(0L)),
        () => SearchControl.untilDeadline(deadlineNanos = 0L, clockNanos = () => 1L)
      )
    do
      // Priority 4: forced pass returns None
      assertEquals(adoptedRootSelect(f4, syntheticEvaluator.evaluate, mkCancelled(), new Random(1)), None)

      // Priority 1: immediate king capture wins even when budget or deadline is already exhausted at entry
      val winRes = adoptedRootSelect(f7, syntheticEvaluator.evaluate, mkCancelled(), new Random(1))
      assertEquals(winRes.map(_._1.score), Some(SearchScoring.TerminalWinScore))
      assertEquals(winRes.map(_._3.evaluatorCalls), Some(0L))

      // Priority 3: exhausted before first candidate evaluation falls back to first canonical turn without touching cache
      val fallbackRes = adoptedRootSelect(f10, syntheticEvaluator.evaluate, mkCancelled(), new Random(1)).get
      assertEquals(fallbackRes._1.moves, canonicalF10Head)
      assertEquals(fallbackRes._1.score, 0)
      assertEquals(fallbackRes._3.evaluatorCalls, 0L)

    // Priority 2: clock expires after 3 candidates; only completed candidates are ranked
    val firstThree = TurnGenerator.generateAllLegalTurnPaths(f10).take(3)
    val expected   = firstThree.map { p =>
      (p, syntheticEvaluator.evaluate(p.foldLeft(f10)(_.makeMove(_)).endTurn(), f10.activeColor))
    }
    val bestScore   = expected.map(_._2).max
    var clockReads  = 0
    val stepControl = SearchControl.untilDeadline(
      deadlineNanos = 4L,
      clockNanos = () => {
        clockReads += 1
        clockReads.toLong
      }
    )
    val partialRes = adoptedRootSelect(f10, syntheticEvaluator.evaluate, stepControl, new Random(1)).get
    assertEquals(partialRes._3.evaluatorCalls, 3L)
    assertEquals(partialRes._1.score, bestScore)
    assert(
      expected.contains((partialRes._1.moves, bestScore)),
      "selected move must be a best-scoring candidate among the completed prefix"
    )

  private def legacyRollValue(
      rolled: GameState,
      perspective: Color,
      bounds: UtilityBounds,
      maximizing: Boolean
  ): Double =
    val paths = TurnGenerator.generateAllLegalTurnPaths(rolled)
    if paths.isEmpty then syntheticEvaluator.evaluate(rolled.endTurn(), perspective).toDouble
    else if paths.exists(p => p.init.foldLeft(rolled)(_.makeMove(_)).isKingCapture(p.last)) then
      bounds.terminalFor(rolled.activeColor)
    else
      val scores = paths.map(p => syntheticEvaluator.evaluate(p.foldLeft(rolled)(_.makeMove(_)).endTurn(), perspective))
      (if maximizing then scores.max else scores.min).toDouble

  private def makeConsumerChancePolicy(
      perspective: Color,
      bounds: UtilityBounds,
      maximizing: Boolean
  ): ChanceNode.Policy =
    new ChanceNode.Policy:
      def lowestValue: Double  = if maximizing then bounds.minNonTerminal else bounds.terminalLoss
      def highestValue: Double = if !maximizing then bounds.maxNonTerminal else bounds.terminalWin
      def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
        ChanceNode.Replies.generate(rolled, SearchControl.Unbounded) match
          case None                                  => ChanceNode.Value.incomplete(SearchControl.Unbounded)
          case Some(replies) if replies.capturesKing =>
            ChanceNode.Value.exact(bounds.terminalFor(rolled.activeColor), lossTainted = !maximizing)
          case Some(replies) if replies.isForcedPass =>
            ChanceNode.Value.exact(syntheticEvaluator.evaluate(rolled.endTurn(), perspective).toDouble)
          case Some(replies) =>
            val scores = replies.states.map(syntheticEvaluator.evaluate(_, perspective))
            val best   = if maximizing then scores.max else scores.min
            ChanceNode.Value.exact(best.toDouble)

  test(
    "Wave 3 & Wave 4: ChanceNode.expand matches legacy 56-roll expectation and preserves caller urgency order in local scratch"
  ):
    val fixtures = List(
      "4k3/8/8/8/8/8/8/4K3 b - - 0 1",    // F1 king-only 4 classes
      "4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1", // F4 blocked-piece forced-pass mass
      "8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1", // F7 knight-die king captures
      "4k3/8/8/8/3r4/2P5/8/4K3 b - - 0 1" // F9 mandatory disadvantageous rook move
    )
    for dfen <- fixtures; perspective <- List(Color.White, Color.Black) do
      val afterTurn  = parse(dfen)
      val bounds     = ExpectimaxSearch.utilityBounds(perspective)
      val maximizing = bounds.isMaximizing(afterTurn.activeColor)
      val legacySum  = DiceRolls.byWeightDescending.foldLeft(0.0) { case (acc, (roll, weight)) =>
        acc + weight.toDouble * legacyRollValue(afterTurn.withDicePool(roll), perspective, bounds, maximizing)
      }
      val legacyExpectation = legacySum / DiceRolls.totalOrderedRolls.toDouble
      val policy            = makeConsumerChancePolicy(perspective, bounds, maximizing)

      val defaultResult = ChanceNode.expand(
        afterTurn,
        bounds,
        SearchControl.Unbounded,
        Double.NegativeInfinity,
        Double.PositiveInfinity,
        policy
      )
      assertEquals(defaultResult.outcome, SearchOutcome.Exact(legacyExpectation, 0L))

      // Consumer urgency ordering in invocation-local scratch (reverse class order) without mutating shared partitions
      val sharedPartitions = ActiveDicePartitioner.partitionsFor(afterTurn)
      val snapshotBefore   = sharedPartitions.toList
      val reverseOrder     = Array.tabulate(sharedPartitions.length)(i => sharedPartitions.length - 1 - i)
      val reorderedResult  = ChanceNode.expand(
        afterTurn,
        bounds,
        SearchControl.Unbounded,
        Double.NegativeInfinity,
        Double.PositiveInfinity,
        policy,
        reverseOrder
      )
      assertEquals(sharedPartitions.toList, snapshotBefore, "shared partition table must remain immutable")
      assertEquals(reorderedResult.outcome, defaultResult.outcome)

  test("Wave 4: concurrent independent consumer invocations match sequential fixed-work runs bit-for-bit"):
    val tasks = for
      dfen <- rootFixtures
      seed <- 0L until 4L
    yield (dfen, seed)

    val sequential = tasks.map { case (dfen, seed) =>
      val state   = parse(dfen)
      val rng     = new Random(seed)
      val control = SearchControl.withBudget(new BudgetedRules.Budget(1_000_000L))
      val res     = adoptedRootSelect(state, syntheticEvaluator.evaluate, control, rng).map(t => (t._1, t._2))
      (dfen, seed, res, rng.nextLong())
    }

    Future
      .traverse(tasks) { case (dfen, seed) =>
        Future {
          val state   = parse(dfen)
          val rng     = new Random(seed)
          val control = SearchControl.withBudget(new BudgetedRules.Budget(1_000_000L))
          val res     = adoptedRootSelect(state, syntheticEvaluator.evaluate, control, rng).map(t => (t._1, t._2))
          (dfen, seed, res, rng.nextLong())
        }
      }
      .map { concurrent =>
        assertEquals(concurrent, sequential)
      }
