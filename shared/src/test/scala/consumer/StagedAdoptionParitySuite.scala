// SPDX-License-Identifier: AGPL-3.0-only
package consumer

import dicechess.engine.domain.*
import dicechess.engine.search.*
import munit.FunSuite

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
  *     engine APIs across score, selected legal path, candidate multiplicity, and post-call `scala.util.Random` state.
  */
class StagedAdoptionParitySuite extends FunSuite:

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
        val isWin      = beforeLast.isKingCapture(path.last)
        if isWin then ScoredSequence(path, SearchScoring.TerminalWinScore)
        else
          val afterTurn = beforeLast.makeMove(path.last).endTurn()
          ScoredSequence(path, evalFn(afterTurn, mover))
      }
      val bestScore = scored.map(_.score).max
      val shortlist = scored.filter(_.score == bestScore)
      val chosen    = shortlist(rng.nextInt(shortlist.size))
      Some((chosen, shortlist.size))

  /** Adopted (Wave 1 + Wave 2) consumer root selector using `BudgetedRules.foldTurnStates`, `EvaluationCache`, and
    * `SearchControl`, preserving path multiplicity and RNG consumption.
    */
  private def adoptedRootSelect(
      state: GameState,
      evalFn: (GameState, Color) => Int,
      control: SearchControl,
      rng: Random
  ): Option[(ScoredSequence, Int, EvaluationCache)] =
    val mover  = state.activeColor
    val cache  = new EvaluationCache(evalFn)
    val budget = control.budget.getOrElse(new BudgetedRules.Budget(Long.MaxValue))
    val folded = BudgetedRules.foldTurnStates(state, Vector.empty[(List[Move], GameState, Boolean)], budget) {
      (acc, path, afterState, isKingCapture) =>
        (acc :+ (path, afterState, isKingCapture), true)
    }
    folded match
      case BudgetedRules.Outcome.Incomplete(_)    => None
      case BudgetedRules.Outcome.Complete(res, _) =>
        val candidates = res.value
        if candidates.isEmpty then None
        else
          // Fallback priority 1: immediate root king capture before any control/cache check
          val wins = candidates.collect { case (path, _, true) => path }
          if wins.nonEmpty then
            val scoredWins = wins.map(p => ScoredSequence(p, SearchScoring.TerminalWinScore))
            val chosen     = scoredWins(rng.nextInt(scoredWins.size))
            Some((chosen, scoredWins.size, cache))
          else
            val completed = Vector.newBuilder[ScoredSequence]
            val it        = candidates.iterator
            while it.hasNext && !control.isCancelled do
              val (path, afterTurn, _) = it.next()
              completed += ScoredSequence(path, cache(afterTurn, mover))
            val scored = completed.result()
            if scored.isEmpty then
              // Fallback priority 3: first canonical legal turn when zero candidates completed
              val firstPath = candidates.head._1
              Some((ScoredSequence(firstPath, 0), 1, cache))
            else
              // Fallback priority 2: best completed (Exact) candidate with seeded tie-breaking
              val bestScore = scored.map(_.score).max
              val shortlist = scored.filter(_.score == bestScore)
              val chosen    = shortlist(rng.nextInt(shortlist.size))
              Some((chosen, shortlist.size, cache))

  test("Wave 1 & Wave 2: adopted visitor + EvaluationCache matches legacy replay on score, path, multiplicity and RNG"):
    val fixtures = List(
      "4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP",                                   // F8 commuting pawn pushes
      "4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RP",                                // F6 inherited en-passant target
      "4k3/8/8/8/8/8/P7/R3K2R w KQ - 0 1 KR",                                 // F5 castling consumes 2 dice
      "8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP",                                 // F7 immediate king capture
      "4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1 PPP",                                 // F4 forced pass
      "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP" // F10 opening tree
    )
    for dfen <- fixtures; seed <- 0L until 10L do
      val state      = parse(dfen)
      val legacyRng  = new Random(seed)
      val adoptedRng = new Random(seed)
      val legacyRes  = legacyRootSelect(state, syntheticEvaluator.evaluate, legacyRng)
      val adoptedRes = adoptedRootSelect(state, syntheticEvaluator.evaluate, SearchControl.Unbounded, adoptedRng)
      assertEquals(adoptedRes.map(t => (t._1, t._2)), legacyRes, s"mismatch on $dfen seed $seed")
      assertEquals(adoptedRng.nextLong(), legacyRng.nextLong(), s"RNG state diverged on $dfen seed $seed")

  test(
    "Wave 2: visitor callback states never double-apply endTurn() on normal turns or apply endTurn() on king captures"
  ):
    val f6 = parse("4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RP")
    TurnGenerator.forEachLegalTurnState(f6) { (moves, len, afterState, isKingCapture) =>
      val path     = moves.take(len).toList
      val replayed = path.foldLeft(f6)(_.makeMove(_)).endTurn()
      assert(!isKingCapture)
      assertEquals(afterState, replayed)
      assertEquals(afterState.activeColor, Color.Black)
      assert(afterState.flags.isDicePoolEmpty)
      true
    }

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
    val f4      = parse("4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1 PPP")
    val f7      = parse("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP")
    val f10     = parse("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP")
    val expired = SearchControl.untilDeadline(deadlineNanos = 0L, clockNanos = () => 1L)

    // Priority 4: forced pass returns None
    assertEquals(adoptedRootSelect(f4, syntheticEvaluator.evaluate, expired, new Random(1)), None)

    // Priority 1: immediate king capture wins even when deadline is already expired
    val winRes = adoptedRootSelect(f7, syntheticEvaluator.evaluate, expired, new Random(1))
    assertEquals(winRes.map(_._1.score), Some(SearchScoring.TerminalWinScore))
    assertEquals(winRes.map(_._3.evaluatorCalls), Some(0L))

    // Priority 3: expired before first candidate evaluation falls back to first canonical turn without touching cache
    val fallbackRes = adoptedRootSelect(f10, syntheticEvaluator.evaluate, expired, new Random(1)).get
    assertEquals(fallbackRes._1.moves, TurnGenerator.generateAllLegalTurnPaths(f10).head)
    assertEquals(fallbackRes._3.evaluatorCalls, 0L)

    // Priority 2: clock expires after 3 candidates; only completed candidates are ranked and warm hits are not read after cancellation
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

      // Legacy 56-roll unpruned reference expectation
      val legacySum = DiceRolls.byWeightDescending.foldLeft(0.0) { case (acc, (roll, weight)) =>
        val rolled = afterTurn.withDicePool(roll)
        val paths  = TurnGenerator.generateAllLegalTurnPaths(rolled)
        val v      =
          if paths.isEmpty then syntheticEvaluator.evaluate(rolled.endTurn(), perspective).toDouble
          else
            val hasWin = paths.exists(p => p.init.foldLeft(rolled)(_.makeMove(_)).isKingCapture(p.last))
            if hasWin then bounds.terminalFor(afterTurn.activeColor)
            else
              val scores =
                paths.map(p => syntheticEvaluator.evaluate(p.foldLeft(rolled)(_.makeMove(_)).endTurn(), perspective))
              (if maximizing then scores.max else scores.min).toDouble
        acc + weight.toDouble * v
      }
      val legacyExpectation = legacySum / DiceRolls.totalOrderedRolls.toDouble

      val policy = new ChanceNode.Policy:
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
