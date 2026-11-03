// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine

/** Search and evaluation layer for the Dice Chess Engine.
  *
  * This package implements the bot strategies, turn-path enumeration, and static evaluation function that power the
  * engine's decision-making. It sits above the move-generation layer and operates on complete
  * [[dicechess.engine.domain.GameState]]s.
  *
  * ## Architecture
  *
  * | Component                                     | Responsibility                                                                                           |
  * |:----------------------------------------------|:---------------------------------------------------------------------------------------------------------|
  * | [[TurnGenerator]]                             | Enumerates all legal full-turn paths and shared-prefix resulting states                                  |
  * | [[BudgetedRules]]                             | Work-bounded rules queries (`turnPaths`, `visitTurnStates`, `foldTurnStates`, `kingCapturePath`)         |
  * | [[ActiveDicePartitioner]]                     | Promotion-aware 3d6 roll equivalence classes summing to 216                                              |
  * | [[AttackerTypes]] / [[PieceValues]]           | Exact 6-bit attacking piece-type masks, roll availability, and minimum piece price                       |
  * | [[PassedPawns]]                               | Canonical passed-pawn bitboard, count, and max-rank advancement detector                                 |
  * | [[SearchOutcome]] / [[SearchControl]]         | Bounded search outcomes (`Exact`, `LowerBound`, `UpperBound`, `Incomplete`) and cooperative cancellation |
  * | [[ChanceNode]]                                | Shared weighted chance-node accumulator with sound Star1/Star2 pruning                                   |
  * | [[dicechess.engine.movegen.LegalMovesFilter]] | Filters first moves under the Maximum Micro-moves Rule (in `movegen`)                                    |
  * | [[SearchAlgorithm]] / [[ControlledSearch]]    | Trait contracts for synchronous, time-budgeted, and controlled bots                                      |
  * | [[ExpectimaxSearch]]                          | Two- and three-ply stochastic expectimax search built on [[ChanceNode]]                                  |
  * | [[GreedySearch]]                              | Picks the turn path with the highest material score; prefers short wins                                  |
  * | [[RandomSearch]]                              | Selects a uniformly random legal path; useful as a baseline / test bot                                   |
  * | [[Evaluator]]                                 | Pure material-balance evaluator used by all current bot strategies                                       |
  * | [[EvaluationCache]]                           | Invocation-local exact memoization of deterministic Scala evaluations                                    |
  * | [[TranspositionTable]]                        | Fixed-size Zobrist subtree bound cache for stochastic expectimax search                                  |
  * | [[SearchScoring]]                             | Shared scoring helper: scores a path and handles king-capture termination                                |
  *
  * ## Turn Model
  *
  * A *turn* in Dice Chess consists of:
  *   1. A dice roll that yields 1–3 die values.
  *   2. A sequence of 1–3 *micro-moves* — each micro-move must be made by the piece type matching the consumed die.
  *   3. The active color is **not** toggled between micro-moves; only after the full turn completes.
  *
  * The **Maximum Micro-moves Rule** requires a player to choose an opening move that is part of the longest achievable
  * micro-move sequence. Any sequence that ends with a King capture is always legal regardless of length.
  *
  * ## Scoring
  *
  * [[SearchScoring.scorePath]] assigns [[SearchScoring.TerminalWinScore]] (`Int.MaxValue`) to any path that captures
  * the opponent's King, and falls back to [[Evaluator.evaluateMaterial]] for non-terminal paths.
  *
  * ## Ownership & Cancellation Boundaries (#398, #399)
  *
  *   - [[BudgetedRules.Budget]], [[SearchControl]], [[EvaluationCache]], and visitor `Array[Move]` buffers are
  *     **single-invocation, single-thread** objects.
  *   - [[ActiveDicePartitioner]] tables ([[CompactRoll]], [[DiceRolls]]) are immutable and shared across invocations.
  *   - [[SearchControl.isCancelled]] is cooperative and checked at yield points (including before [[EvaluationCache]]
  *     and [[TranspositionTable]] lookups), never preempting inside a single move-generation or evaluator call.
  *   - [[UtilityBounds]] enforces strict terminal-over-heuristic dominance across [[ChanceNode]], [[ExpectimaxSearch]],
  *     [[MonteCarloSearch]], and [[MonteCarloEquity]].
  *
  * ## Staged Consumer Composition Example (#398)
  *
  * ```scala
  * import dicechess.engine.domain.*
  * import dicechess.engine.search.*
  * import scala.util.Random
  *
  * // Consumer-owned prices and rank bonuses (Wave 1):
  * val prices      = PieceValues(pawn = 100, knight = 310, bishop = 330, rook = 500, queen = 900, king = 10_000)
  * val passedBonus = IArray(0, 5, 12, 25, 45, 80, 140)
  *
  * def consumerEval(state: GameState, perspective: Color): Int =
  *   var pawns = PassedPawns.bitboard(state, perspective).value
  *   var bonus = 0
  *   while pawns != 0L do
  *     val sq   = java.lang.Long.numberOfTrailingZeros(pawns)
  *     val rank = sq / 8 + 1
  *     val adv  = if perspective.isWhite then rank - 1 else 8 - rank
  *     bonus += passedBonus(adv)
  *     pawns &= pawns - 1L
  *   val hanging = PieceSafety.hangingSquares(state, perspective)
  *   val risk    =
  *     if hanging.isEmpty then 0
  *     else
  *       val victimSq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(hanging.value))
  *       val mask     = AttackerTypes.mask(state, victimSq, perspective.opponent)
  *       (AttackerTypes.availableRolls(mask) * prices.minimumOrElse(mask, 0)) / DiceRolls.totalOrderedRolls
  *   Evaluator.evaluateMaterial(state, perspective) + bonus - risk
  *
  * // Wave 2 + Wave 3: root traversal with EvaluationCache, SearchControl, and ChanceNode expansion:
  * val state   = FenParser.parse("4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP").toOption.get
  * val mover   = state.activeColor
  * val control = SearchControl.withBudget(new BudgetedRules.Budget(10_000L))
  * val cache   = new EvaluationCache(consumerEval)
  * val bounds  = ExpectimaxSearch.utilityBounds(mover)
  *
  * val folded = BudgetedRules.foldTurnStates(
  *   state,
  *   Vector.empty[(List[Move], GameState, Boolean)],
  *   control.budget.get
  * ) { (acc, path, afterState, isKingCapture) =>
  *   (acc :+ (path, afterState, isKingCapture), true)
  * }
  *
  * val firstCandidateOpt = folded match
  *   case BudgetedRules.Outcome.Complete(res, _) =>
  *     if res.value.isEmpty then None else Some(res.value.head)
  *   case BudgetedRules.Outcome.Incomplete(_) => None
  * val (_, firstAfterTurn, isWin) = firstCandidateOpt.get
  * assert(!isWin && firstAfterTurn.activeColor == mover.opponent && firstAfterTurn.flags.isDicePoolEmpty)
  *
  * val rootStaticOutcome =
  *   if control.isCancelled then SearchOutcome.Incomplete(control.workUsed)
  *   else SearchOutcome.Exact(cache(firstAfterTurn, mover), control.workUsed)
  * val policy = new ChanceNode.Policy:
  *   def lowestValue: Double  = bounds.terminalLoss
  *   def highestValue: Double = bounds.maxNonTerminal
  *   def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
  *     ChanceNode.Replies.generate(rolled, control) match
  *       case None                                  => ChanceNode.Value.incomplete(control)
  *       case Some(replies) if replies.capturesKing => ChanceNode.Value.exact(bounds.terminalLoss, lossTainted = true)
  *       case Some(_) if control.isCancelled        => ChanceNode.Value.incomplete(control)
  *       case Some(replies) if replies.isForcedPass =>
  *         ChanceNode.Value.exact(cache(rolled.endTurn(), mover).toDouble, workUsed = control.workUsed)
  *       case Some(replies) =>
  *         val minReply = replies.states.map(cache(_, mover)).min.toDouble
  *         ChanceNode.Value.exact(minReply, workUsed = control.workUsed)
  *
  * val chanceResult = ChanceNode.expand(
  *   firstAfterTurn,
  *   bounds,
  *   control,
  *   Double.NegativeInfinity,
  *   Double.PositiveInfinity,
  *   policy
  * )
  * assert(chanceResult.outcome.isExact && rootStaticOutcome.valueOption.exists(_ >= 0) && Random(0).nextInt(1) == 0)
  * ```
  */
package object search
