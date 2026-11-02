// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*

import scala.util.Random

/** Tuning for [[ExpectimaxSearch]].
  *
  * @param candidateLimit
  *   how many of the mover's own turns (pre-ranked by [[ExpectimaxSearch]]'s `preRank`, material by default) are
  *   expanded to full depth. Dice Chess routinely offers hundreds of legal turns per roll, so expanding all of them
  *   through a chance node is infeasible; this bounds the branching at the decision node. Must be positive.
  * @param exactOnlyMode
  *   when true, the transposition table stores and reuses exact entries only. This is primarily a validation mode for
  *   comparing cached and uncached trees without fail-soft bound reuse.
  * @param searchDepth
  *   number of full-turn plies in the expectimax tree. Depth 2 preserves the historical `MAX → CHANCE → MIN → leaf`
  *   search; depth 3 adds `CHANCE → MAX → leaf` after every opponent reply so exchanges include our recapture.
  */
final case class ExpectimaxConfig(
    candidateLimit: Int = 8,
    exactOnlyMode: Boolean = false,
    searchDepth: Int = 2
):
  require(candidateLimit > 0, s"candidateLimit must be positive, got $candidateLimit")
  require(searchDepth == 2 || searchDepth == 3, s"searchDepth must be 2 or 3, got $searchDepth")

/** Root-level rescoring: after the search's own chance-node expectation is computed for each root candidate, blend it
  * with a second, cheaper-at-the-root evaluator run *once* on the candidates' own resulting positions (before the
  * opponent's roll) — `finalScore = (1 - weight) * searchValue + weight * rescoreValue`.
  *
  * Meant for a tactically sharp but leaf-prohibitive evaluator: e.g. king/queen capture-probability features cost a
  * 216-outcome DFS each, far too expensive under a chance node's hundreds of leaves, but cheap enough for the handful
  * of root candidates ([[ExpectimaxConfig.candidateLimit]]).
  *
  * A candidate where at least one dice roll loses our king outright ([[ExpectimaxSearch.LossValue]]) is never rescored,
  * at any weight — that sentinel must always rank last regardless of what the rescorer thinks of the resulting position
  * (see the root loop's `blendedRootValue`).
  *
  * @param evalBatch
  *   the rescoring evaluator, same batching contract as the search's own `evalBatch`
  * @param weight
  *   blend weight; must be in `[0, 1]`. Zero is deliberately accepted as an operationally safe way to disable a
  *   configured rescorer: it is exactly equivalent to `None` and does not invoke `evalBatch`.
  */
final case class RootRescore(evalBatch: (Array[GameState], Color) => Array[Int], weight: Double):
  require(weight >= 0.0 && weight <= 1.0, s"weight must be in [0, 1], got $weight")

/** Learned replacement for a root candidate's chance node: instead of expanding the opponent's 56 weighted dice
  * outcomes and their replies, one batched call scores the position each candidate reaches and returns that expectation
  * directly.
  *
  * This is the search's most expensive layer by orders of magnitude — every candidate pays hundreds to thousands of
  * leaf evaluations for one number — so a model that predicts that number turns a chance node into a single row of a
  * batch. It is off by default and has to be enabled explicitly, because it trades an exact expectation for an
  * estimate: what comes back is what the model thinks the expansion would have produced.
  *
  * What it keeps: the immediate-king-capture shortcut and the forced pass above it, pre-ranking and its deterministic
  * order, the random tie-break, the deadline's meaning (the batch is indivisible and is not started once the deadline
  * has passed, so the anytime fallback to the pre-ranker's pick is unchanged), and root rescoring.
  *
  * What it gives up: Star1/Star2 pruning and the transposition table have nothing to prune or store — a collapsed value
  * is a different quantity from the exact expectation the table holds at that depth, so the table is left untouched
  * rather than mixed — and [[ExpectimaxConfig.searchDepth]] has no effect, since no tree is built below the root.
  *
  * It also gives up per-roll knowledge, and with it the one rule [[RootRescore]] treats as inviolable: a candidate that
  * loses our king on some roll must never be rescued by a rescorer. `lossGuard` restores exactly that rule without
  * expanding anything — [[KingCaptureProbability]] answers "can the opponent capture our king on their next roll" over
  * the same 56 multisets, exactly (king-capture paths ignore the maximum micro-moves rule, so its depth-first search is
  * not an estimate), at one 216-outcome search per root candidate. Leave it off for a pure collapse; turn it on when a
  * rescorer is configured at a positive weight, or the rescorer can outvote a lost line.
  *
  * @param evalBatch
  *   scores the positions the candidates reach, from the root mover's perspective, on the same Int axis as the search's
  *   own `evalBatch` — the value means "the expectation of this chance node", not "the value of this leaf"
  * @param lossGuard
  *   whether to re-establish the loss-taint rule described above. It only has an effect while a [[RootRescore]] is
  *   active at a positive weight — that rule is about what a rescorer may not rescue, and with no rescorer the
  *   collapsed value is returned unchanged — so the search skips the work entirely in that case rather than paying a
  *   216-outcome search per candidate for a value nothing reads.
  */
final case class ChanceCollapse(
    evalBatch: (Array[GameState], Color) => Array[Int],
    lossGuard: Boolean = false
)

/** One move's root telemetry from [[ExpectimaxSearch]]: how wide the decision node really was.
  *
  * `candidatesCompleted < candidatesSelected` means the deadline or work budget truncated the anytime loop — the move
  * was chosen among fewer candidates than [[ExpectimaxConfig.candidateLimit]] allowed. Persistently tiny completion
  * counts on production hardware mean the effective search degenerates toward "play the pre-ranker's first pick", which
  * no amount of candidate-limit tuning can fix — that evidence is exactly what this type exists to surface.
  *
  * An immediate king-capture win reports `0/0` selected/completed: no candidate was pre-ranked or expanded, so counting
  * it as a completed candidate would overstate the searched width. A forced pass (no legal turn for the roll) reports
  * all-zero — `legalTurns == 0` is what tells the two apart, keeping the sink's contract at exactly one record per
  * `findBestMove` call.
  *
  * @param legalTurns
  *   size of the full legal-turn list before pre-ranking
  * @param candidatesSelected
  *   how many turns survived pre-ranking (at most the candidate limit)
  * @param candidatesCompleted
  *   how many selected candidates were fully expanded through the chance node. Ranking needs comparable values, and a
  *   full expansion is one way to get one; a transposition-table hit ([[RootSearchStats.ttHits]]) and a collapsed
  *   candidate ([[RootSearchStats.candidatesCollapsed]]) are the other two, and are counted separately because they did
  *   no expansion work
  * @param candidatesAbandoned
  *   candidates whose chance node was cut mid-expansion by the deadline or the work budget (at most one: the search
  *   stops afterwards). Their partial expectation is discarded rather than ranked, so this counts work paid for and
  *   thrown away — a persistent non-zero value means the per-move budget is too small for even one candidate at this
  *   width.
  * @param cutoffs
  *   candidates whose chance node Star1 or Star2 pruning settled as a bound instead of an exact expectation
  * @param rollsSaved
  *   dice **classes** those cutoffs left unexpanded, summed over the pruned candidates. A chance node iterates the
  *   promotion-aware classes of [[ActiveDicePartitioner]], so one candidate has at most 56 classes and fewer when the
  *   opponent's pieces retain fewer dice (ten for a king and a bishop)
  * @param probeCutoffs
  *   the subset of `cutoffs` settled by the Star2 probe sum before any class was expanded
  */
final case class RootSearchStats(
    legalTurns: Int,
    candidatesSelected: Int,
    candidatesCompleted: Int,
    candidatesAbandoned: Int = 0,
    cutoffs: Int = 0,
    rollsSaved: Int = 0,
    probeCutoffs: Int = 0,
    // Transposition-table telemetry, kept strictly apart from the expansion counters above: a TT-resolved candidate
    // did zero chance-node work this move, so folding it into candidatesCompleted/cutoffs would corrupt the
    // effective-width metric this record exists to measure and distort every A/B width comparison built on it.
    ttProbes: Int = 0,  // candidates for which the table was consulted (tt configured)
    ttHits: Int = 0,    // candidates resolved by an EXACT entry — ranked, but never expanded
    ttCutoffs: Int = 0, // candidates rejected by a stored UPPER bound before any expansion
    // Candidates a configured [[ChanceCollapse]] resolved in one batched call. Ranked like completed ones and kept out
    // of candidatesCompleted for the same reason ttHits are: they did no chance-node work, and folding them in would
    // report a searched width that never happened.
    candidatesCollapsed: Int = 0,
    // The collapse model broke its batching contract (it answered with a different number of rows than it was given),
    // so nothing was ranked and the move came from the pre-ranker. Kept apart from candidatesAbandoned because the two
    // call for opposite responses: a deadline says the budget is too small, this says the model is wrong.
    collapseRejected: Boolean = false
):
  /** Whether the deadline cut the loop short of the selected candidate set. A rejected collapse batch leaves the same
    * counters at zero without the clock having run out, so it is excluded rather than reported as truncation.
    */
  def deadlineTruncated: Boolean =
    !collapseRejected &&
      candidatesCompleted + cutoffs + ttHits + ttCutoffs + candidatesCollapsed < candidatesSelected

  /** Nothing was scored, so the turn came from the pre-ranker alone — the search contributed nothing beyond candidate
    * selection. True for either reason it can happen: the deadline elapsed first, or the collapse model's batch was
    * refused. [[RootSearchStats.collapseRejected]] and [[RootSearchStats.candidatesAbandoned]] tell them apart.
    */
  def fellBackToPreRank: Boolean =
    candidatesCompleted == 0 && ttHits == 0 && candidatesCollapsed == 0 &&
      (candidatesAbandoned > 0 || collapseRejected)

/** Configurable two- or three-ply expectimax search for Dice Chess.
  *
  * Unlike a one-ply evaluator ([[GreedySearch]], [[OnnxEvalSearch]]), this looks one full turn ahead and so sees
  * tactical punishments — a capture that hangs a bigger piece to the recapture — that a static evaluation cannot. The
  * layer between the two plies is a **chance node**: the opponent's roll is unknown when we move, so the value of our
  * turn is the expectation over all [[DiceRolls]] outcomes of the opponent's best (for them) reply.
  *
  * The evaluation function is injected as a batch (`evalBatch(states, color)` scores every state from `color`'s
  * perspective) so the same search works with any leaf evaluator — the engine's material score, or an externally
  * trained model — and so the many leaves under one chance node can be scored in a single call. Non-terminal leaf
  * scores must not exceed [[ExpectimaxSearch.UpperScoreBound]] because Star pruning uses that ceiling; values below
  * zero are supported down to the terminal-loss sentinel.
  *
  * Two terminal cases sit outside the evaluator, because a king capture ends the game and material scores never see the
  * king:
  *   - if one of our own turns captures the opponent's king, we play it immediately (an outright win);
  *   - a leaf where the opponent captures our king is worth [[ExpectimaxSearch.LossValue]] — below any real score on
  *     any scale — so the opponent always takes it and we always rank that line last.
  *
  * At depth 3, every opponent reply is followed by a chance node over our next roll and a MAX node over our legal
  * replies before leaf evaluation. As a [[TimeBudgetedSearch]] the implementation honours a wall-clock deadline at
  * every chance-node roll boundary, expanding pre-ranked root candidates until time runs out.
  *
  * @param preRank
  *   batched evaluator used to rank the mover's own legal turns before the (expensive) chance-node expansion — only the
  *   top [[ExpectimaxConfig.candidateLimit]] are explored. Defaults to material ([[ExpectimaxSearch.materialBatch]] —
  *   the historical, hardcoded behaviour). Widening `candidateLimit` compensates for a crude pre-ranker at linear
  *   search-cost growth; a sharper pre-ranker (e.g. the same value model already driving the chance node) attacks the
  *   actual bottleneck instead — candidateLimit=16 vs material pre-ranking measured +4.8pp purely from surfacing turns
  *   the material proxy had buried outside the top 8.
  * @param statsSink
  *   receives one [[RootSearchStats]] per `findBestMove` call. Defaults to a no-op so the hot path pays nothing beyond
  *   a single call; a real sink lets a host (arena runner, production bot) observe how many candidates the deadline
  *   actually allowed — see [[RootSearchStats]] for why that matters.
  * @param chanceCollapse
  *   when present, replaces every root candidate's exact chance-node expansion with one batched model call — see
  *   [[ChanceCollapse]] for what that keeps and what it gives up. Absent by default, and absent means the exact search
  *   runs unchanged: the collapsed path is a separate branch that the default configuration never enters.
  */
final class ExpectimaxSearch(
    evalBatch: (Array[GameState], Color) => Array[Int],
    config: ExpectimaxConfig = ExpectimaxConfig(),
    rootRescore: Option[RootRescore] = None,
    preRank: (Array[GameState], Color) => Array[Int] = ExpectimaxSearch.materialBatch,
    statsSink: RootSearchStats => Unit = ExpectimaxSearch.NoStats,
    tt: Option[TranspositionTable] = None,
    chanceCollapse: Option[ChanceCollapse] = None
) extends ControlledSearch:

  import ExpectimaxSearch.*

  override def findBestMove(state: GameState): Option[ScoredSequence] =
    findBestMove(state, new Random())

  /** Finds the best turn with an explicit `Random`, running to completion over every candidate. */
  override def findBestMove(state: GameState, rand: Random): Option[ScoredSequence] =
    findBestMove(state, SearchControl.Unbounded, rand)

  /** Finds the best turn under a [[SearchControl]] — a wall-clock deadline, a deterministic rules work budget, or both.
    * The historical `findBestMove(state, deadlineNanos, random)` is this method with
    * `SearchControl.untilDeadline(deadlineNanos)`.
    *
    * Candidates are expanded in pre-ranked order, and the control is consulted **between dice rolls inside** a
    * candidate's chance node — not merely between candidates. Even the top candidate can therefore be abandoned before
    * it yields a comparable value, which is the point: one candidate routinely costs more than the whole per-turn
    * budget, so a coarser check made the deadline advisory rather than binding (#496). A budget reaches further still:
    * the same [[BudgetedRules.Budget]] generates every opponent reply inside the tree, so exhaustion inside one roll's
    * reply generation cancels the invocation at the very next check, deterministically and without a clock.
    *
    * The result is the best *completed* ([[SearchOutcome.Exact]]) candidate, or — when cancellation left no candidate
    * time to finish — the pre-ranker's own top pick, so the anytime contract still delivers a legal turn. A partially
    * expanded candidate is never ranked: its expectation covers only the rolls the control allowed, so comparing it
    * against complete ones would let the instant the deadline landed choose the move. [[RootSearchStats]] reports both
    * cases. The complete fallback order is documented on [[ControlledSearch]]; root legal-turn generation, the
    * immediate-capture test and the pre-rank batch are part of it and run even when `control` is already cancelled at
    * entry — the first operation cancellation skips is the first piece of search work.
    *
    * All of this matters because a single roll can generate thousands of opponent replies (the Dice Chess branching
    * tail).
    */
  override def findBestMove(state: GameState, control: SearchControl, random: Random): Option[ScoredSequence] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then
      // Forced pass: still one record per call — all-zero, distinguished from a win shortcut by legalTurns == 0.
      statsSink(RootSearchStats(0, 0, 0))
      None
    else
      immediateWin(state, paths) match
        case Some(win) =>
          statsSink(RootSearchStats(paths.size, 0, 0))
          Some(win)
        case None => Some(searchRoot(state, paths, control, random))

  /** The shortest immediate king capture among `paths`, if any: it wins outright, so it is taken before pre-ranking,
    * which would otherwise be free to prune it (the king has no material or model value).
    */
  private def immediateWin(state: GameState, paths: List[List[Move]]): Option[ScoredSequence] =
    val winning = paths.filter(path => capturesEnemyKing(state, path))
    Option.when(winning.nonEmpty)(ScoredSequence(winning.minBy(_.size), SearchScoring.TerminalWinScore))

  /** Pre-ranks the mover's turns, expands the top candidates under the deadline, and ranks the ones that finished. */
  private def searchRoot(
      state: GameState,
      paths: List[List[Move]],
      control: SearchControl,
      random: Random
  ): ScoredSequence =
    val myColor    = state.activeColor
    val candidates = selectRootCandidates(state, paths, myColor)
    val expansion  = new RootExpansion
    expandRootCandidates(candidates, myColor, control, expansion)
    statsSink(expansion.toStats(paths.size, candidates.entries.length))
    val results = expansion.ranked
    // The deadline elapsed inside the very first candidate, so nothing has a comparable value yet. The anytime
    // contract still owes a legal turn: play the pre-ranker's own top pick, scored as the pre-ranker scored it.
    if results.isEmpty then ScoredSequence(candidates.entries(0)._1, candidates.topPreRankScore)
    else
      val bestQ = results.map(_._2).max
      val best  = results.collect { case (path, q) if q == bestQ => path }
      ScoredSequence(best(random.nextInt(best.length)), bestQ.toInt)

  /** Scores every legal turn in one batched pre-rank call and keeps the top [[ExpectimaxConfig.candidateLimit]]. */
  private def selectRootCandidates(state: GameState, paths: List[List[Move]], myColor: Color): RootCandidates =
    // Every path here is provably non-king-capturing (`immediateWin` already took those), so its own resulting
    // position — needed for both the pre-rank score and, for survivors, the chance node — is exactly
    // `applyTurn(state, path)`; computing it once here and reusing it below avoids replaying the same turn twice.
    // Array throughout (not List): the top-K expansion loop indexes `entries(i)`, which must stay O(1), and the
    // batched pipeline avoids intermediate linked-list-node allocations in this per-move hot path.
    val withResultState = paths.map(path => path -> applyTurn(state, path)).toArray
    val preRankScores   = preRank(withResultState.map(_._2), myColor)
    // Pre-rank in one batched call, expand only the top candidates through the (expensive) chance node. sortBy is
    // stable (like List's), so equal-scored candidates keep generation order — the material default stays identical.
    val ranked = withResultState
      .zip(preRankScores)
      .sortBy { case (_, score) => -score }
      .take(config.candidateLimit)
    RootCandidates(ranked.map(_._1), ranked(0)._2)

  /** Expands the candidate set into `expansion`, stopping as soon as the deadline has passed.
    *
    * Root rescores must be known before chance-node expansion: each candidate has a different affine transform from the
    * best blended score back into the leaf-search score domain used by Star1, Star2, and TT bounds. An already expired
    * deadline skips that indivisible batch; if the batch starts in time but itself exhausts the budget, the second
    * check prevents chance-node work from starting at all.
    */
  private def expandRootCandidates(
      candidates: RootCandidates,
      myColor: Color,
      control: SearchControl,
      expansion: RootExpansion
  ): Unit =
    chanceCollapse match
      case Some(collapse) => collapseRootCandidates(candidates, myColor, control, expansion, collapse)
      case None           => expandRootCandidatesExactly(candidates, myColor, control, expansion)

  /** The exact expansion: one chance node per candidate, pruned and cached as configured. */
  private def expandRootCandidatesExactly(
      candidates: RootCandidates,
      myColor: Color,
      control: SearchControl,
      expansion: RootExpansion
  ): Unit =
    val entries             = candidates.entries
    val activeRootRescore   = rootRescoreValues(entries, myColor, control)
    val expiredBeforeChance = control.isCancelled
    // Preserve the established anytime telemetry: if no search can start, the first selected candidate is the one the
    // deadline abandoned and the move falls back to the pre-ranker's top pick.
    if expiredBeforeChance then expansion.abandoned = 1
    var i        = 0
    var continue = !expiredBeforeChance
    while i < entries.length && continue do
      val (path, resultState) = entries(i)
      if tt.isDefined then expansion.ttProbes += 1
      val alpha = rootSearchAlpha(expansion, activeRootRescore, i)
      continue = recordRootCandidate(
        path,
        rootChanceNode(resultState, myColor, control, alpha),
        activeRootRescore,
        i,
        expansion
      )
      i += 1
      if continue && control.isCancelled then continue = false

  /** The collapsed expansion: the whole candidate set is resolved by one batched model call.
    *
    * The deadline is handled exactly as the exact path handles it, and for the same reason: the rescore batch and the
    * collapse batch are both indivisible, so neither is started once the deadline has passed, and a deadline that lands
    * before them leaves the move to the pre-ranker's top pick with `candidatesAbandoned = 1`.
    *
    * Every candidate in the batch is comparable — they are all the same estimate of the same quantity — so all of them
    * are ranked, and none of them touches the transposition table.
    */
  private def collapseRootCandidates(
      candidates: RootCandidates,
      myColor: Color,
      control: SearchControl,
      expansion: RootExpansion,
      collapse: ChanceCollapse
  ): Unit =
    val entries           = candidates.entries
    val resultStates      = entries.map(_._2)
    val activeRootRescore = rootRescoreValues(entries, myColor, control)
    if control.isCancelled then expansion.abandoned = 1
    else
      val values = collapse.evalBatch(resultStates, myColor)
      // A model that answers with a different number of rows than it was given has broken the batching contract, and
      // the rows it did return cannot be trusted to line up: an off-by-one batch would rank every candidate with its
      // neighbour's value, which is worse than not searching at all. Nothing is ranked and the move falls back to the
      // pre-ranker's pick — reported as its own flag, because "the model is wrong" and "the budget was too small" are
      // different problems for whoever reads the telemetry.
      if values.length != entries.length then expansion.collapseRejected = true
      else
        lossTaints(collapse, activeRootRescore, resultStates, myColor, control) match
          case None          => expansion.abandoned = 1
          case Some(tainted) =>
            var i = 0
            while i < entries.length do
              expansion.rank(entries(i)._1, collapsedRootValue(values(i), tainted(i), activeRootRescore, i))
              expansion.collapsed += 1
              i += 1

  /** Per-candidate loss taint for a collapsed root, or `None` when the deadline landed inside the pass.
    *
    * Two decisions live here. The guard runs **only when a rescorer is actually active**: without one,
    * [[collapsedRootValue]] returns the model's value untouched, so a 216-outcome search per candidate would change
    * neither the ranking nor the reported score — it would be pure latency, paid on the move's own clock. And the pass
    * is **all-or-nothing**: the guard exists to stop a rescorer rescuing a line that loses our king, so ranking a
    * half-guarded set would apply that rule to some candidates and not to others, which is worse than not ranking at
    * all. [[KingCaptureProbability]] takes no deadline of its own, so the clock is checked between candidates — the
    * same granularity the exact path checks between rolls.
    */
  private def lossTaints(
      collapse: ChanceCollapse,
      activeRootRescore: Option[(Double, Array[Int])],
      resultStates: Array[GameState],
      myColor: Color,
      control: SearchControl
  ): Option[Array[Boolean]] =
    if !collapse.lossGuard || activeRootRescore.isEmpty then Some(new Array[Boolean](resultStates.length))
    else
      val tainted = new Array[Boolean](resultStates.length)
      var i       = 0
      var ranOut  = false
      while i < resultStates.length && !ranOut do
        if control.isCancelled then ranOut = true
        else
          val taint = losesKingOnSomeRoll(resultStates(i), myColor, control)
          // A budgeted guard query that ran out returns no verdict and leaves the control cancelled: re-read it before
          // recording the candidate, exactly as the exact path re-reads it after a reply generation.
          if control.isCancelled then ranOut = true
          else
            tainted(i) = taint
            i += 1
      Option.unless(ranOut)(tainted)

  /** A collapsed candidate's ranking score: the model's expectation, blended with the root rescore unless this line
    * loses our king on some roll — the same rule, and the same arithmetic, as the exact path's `blendedRootValue`.
    */
  private def collapsedRootValue(
      value: Int,
      lossTainted: Boolean,
      activeRootRescore: Option[(Double, Array[Int])],
      index: Int
  ): Double =
    activeRootRescore match
      case Some((weight, values)) if !lossTainted => blendRootScore(value.toDouble, values(index).toDouble, weight)
      case _                                      => value.toDouble

  /** Whether any dice outcome lets the opponent capture our king from `afterTurn`, which is exactly what the exact
    * expansion records as a loss taint — obtained from a 216-outcome capture search instead of from 56 chance-node
    * expansions, because a collapsed candidate never expands one.
    *
    * Under a work budget the query is `BudgetedRules.kingCaptureRolls` on the invocation's shared budget, so the guard
    * is bounded like every other nested rules query; an interrupted query returns no verdict (the value here is then
    * meaningless) and the caller re-reads `control.isCancelled`. Without a budget the scratch-board
    * [[KingCaptureProbability]] search is the cheaper way to the same answer.
    */
  private def losesKingOnSomeRoll(afterTurn: GameState, myColor: Color, control: SearchControl): Boolean =
    control.budget match
      case Some(budget) =>
        BudgetedRules.kingCaptureRolls(afterTurn, myColor, budget) match
          case BudgetedRules.Outcome.Complete(count, _) => count > 0
          case BudgetedRules.Outcome.Incomplete(_)      => false
      case None => KingCaptureProbability.kingCaptureProbability(afterTurn, myColor) > 0.0

  /** The rescore weight paired with one score per candidate, or `None` when no rescorer is configured, weight zero
    * disabled it, or the deadline elapsed before the batch could start.
    *
    * Weight zero is exactly the unrescored path and deliberately does not invoke an otherwise configured evaluator.
    */
  private def rootRescoreValues(
      entries: Array[(List[Move], GameState)],
      myColor: Color,
      control: SearchControl
  ): Option[(Double, Array[Int])] =
    rootRescore match
      case Some(RootRescore(rescoreEval, weight)) if weight > 0.0 && !control.isCancelled =>
        Some(weight -> rescoreEval(entries.map(_._2), myColor))
      case _ => None

  /** The alpha this candidate's chance node prunes against: the best blended score so far, mapped back into this
    * candidate's own search-score domain whenever rescoring is active.
    */
  private def rootSearchAlpha(
      expansion: RootExpansion,
      activeRootRescore: Option[(Double, Array[Int])],
      index: Int
  ): Double =
    activeRootRescore match
      case Some((weight, values)) => transformedRootAlpha(expansion.bestFinal, values(index).toDouble, weight)
      case _                      => expansion.bestFinal

  /** The chance node of one root candidate: the opponent's unknown roll after our turn, searched through the same
    * recursive machinery at every depth — `searchDepth - 1` decision plies below it, MIN first, under the window
    * `[alpha, +∞)`.
    */
  private def rootChanceNode(
      resultState: GameState,
      myColor: Color,
      control: SearchControl,
      searchAlpha: Double
  ): ChanceNodeResult =
    recursiveChanceNode(
      resultState,
      SearchContext(
        utilityBounds(myColor),
        pliesRemaining = config.searchDepth - 1,
        maximizing = false,
        control,
        searchAlpha,
        beta = Double.PositiveInfinity
      )
    )

  /** Folds one candidate's chance-node result into the expansion, and reports whether the loop may continue.
    *
    * Only a fully expanded candidate is ranked. A truncated one carries the expectation of the rolls it happened to
    * reach, which is not comparable with a complete one — ranking it would let the arbitrary point where the clock
    * landed decide the move. That is also why an abandoned candidate stops the loop: the deadline is already past, so
    * starting another candidate cannot finish either.
    */
  private def recordRootCandidate(
      path: List[Move],
      res: ChanceNodeResult,
      activeRootRescore: Option[(Double, Array[Int])],
      index: Int,
      expansion: RootExpansion
  ): Boolean =
    res.outcome match
      case SearchOutcome.Exact(value, _) =>
        expansion.rank(path, blendedRootValue(value, res.lossTainted, activeRootRescore, index))
        // A TT-exact hit is ranked like a completed candidate (the value IS exact, alpha may advance from it), but it
        // did no expansion work — counting it as completed would inflate the effective-width metric.
        if res.fromTT then expansion.ttHits += 1 else expansion.completed += 1
        true
      case SearchOutcome.UpperBound(_, _) | SearchOutcome.LowerBound(_, _) =>
        // A root chance node is bounded by alpha alone (its beta is +∞), so only an upper bound — "provably below the
        // best completed candidate" — can arrive here; a lower bound is unreachable and, were it ever produced, is
        // still not rankable, so it is treated the same way: counted, never ranked.
        recordPrunedCandidate(res, expansion)
        true
      case SearchOutcome.Incomplete(_) =>
        expansion.abandoned += 1
        false

  /** The candidate's final ranking score: its raw search value, or that value blended with the root rescore — never
    * blended for a line that loses our king on some roll, at any weight.
    */
  private def blendedRootValue(
      value: Double,
      lossTainted: Boolean,
      activeRootRescore: Option[(Double, Array[Int])],
      index: Int
  ): Double =
    activeRootRescore match
      case Some((weight, values)) if !lossTainted => blendRootScore(value, values(index).toDouble, weight)
      case _                                      => value

  /** Attributes a pruned candidate to the mechanism that pruned it, keeping TT reuse out of the expansion counters. */
  private def recordPrunedCandidate(res: ChanceNodeResult, expansion: RootExpansion): Unit =
    if res.fromTT then expansion.ttCutoffs += 1
    else
      expansion.cutoffs += 1
      if res.probePruned then expansion.probeCutoffs += 1
      expansion.rollsSaved += res.classes - res.classesResolved

  /** The pre-ranked root candidate set: the turns to expand with the position each reaches, plus the top pre-rank score
    * that the anytime fallback plays when no candidate finishes.
    */
  final private class RootCandidates(val entries: Array[(List[Move], GameState)], val topPreRankScore: Int)

  /** Mutable accumulator for one root expansion loop: the width counters [[RootSearchStats]] reports, the ranked
    * candidates, and the best blended score so far — which is the alpha every later candidate prunes against.
    */
  final private class RootExpansion:
    var collapseRejected = false
    var collapsed        = 0
    var completed        = 0
    var abandoned        = 0
    var cutoffs          = 0
    var rollsSaved       = 0
    var probeCutoffs     = 0
    var ttProbes         = 0
    var ttHits           = 0
    var ttCutoffs        = 0
    var bestFinal        = Double.NegativeInfinity

    private val evaluated = List.newBuilder[(List[Move], Double)]

    /** Admits a comparable candidate to the ranked set and advances the root alpha. */
    def rank(path: List[Move], value: Double): Unit =
      evaluated += (path -> value)
      bestFinal = math.max(bestFinal, value)

    def ranked: List[(List[Move], Double)] = evaluated.result()

    def toStats(legalTurns: Int, candidatesSelected: Int): RootSearchStats =
      RootSearchStats(
        legalTurns,
        candidatesSelected,
        completed,
        abandoned,
        cutoffs,
        rollsSaved,
        probeCutoffs,
        ttProbes,
        ttHits,
        ttCutoffs,
        collapsed,
        collapseRejected
      )

  /** A chance node at any depth: the control check, the transposition-table probe, the shared [[ChanceNode]] expansion
    * under this search's [[NodePolicy]], and the store.
    *
    * [[SearchContext.pliesRemaining]] counts decision plies below the node. `1` means the mover's replies are scored by
    * the leaf evaluator — the historical depth-2 chance node — and `2` means every reply is followed by our own chance
    * node and MAX reply. The root candidate's node is this method at `searchDepth - 1` with the MIN role and the window
    * `[alpha, +∞)`, so a candidate can only be pruned from above, by the best completed candidate so far.
    *
    * The result's [[SearchOutcome]] is [[SearchOutcome.Exact]] only when every weighted class was resolved before
    * `ctx.control` cancelled the invocation. [[SearchOutcome.Incomplete]] carries no partial expectation at all, so a
    * caller cannot rank it against complete ones (#496); it is also never written to the table. The control is read
    * **before** the table, so an already-cancelled invocation returns `Incomplete` deterministically instead of being
    * rescued by a hit. A role salt separates MAX continuations from MIN continuations in the table: the same board at
    * the same nominal depth has the opposite value perspective in those two roles.
    */
  private def recursiveChanceNode(toMove: GameState, ctx: SearchContext): ChanceNodeResult =
    if ctx.control.isCancelled then ChanceNodeResult.incomplete(ctx.control)
    else
      val key = transpositionKey(toMove, ctx.maximizing)
      val hit = ttHit(key, ctx)
      if hit.isDefined then hit.get
      else
        val expanded =
          ChanceNode.expand(toMove, ctx.bounds, ctx.control, ctx.alpha, ctx.beta, new NodePolicy(toMove, ctx))
        storeNode(key, ctx.pliesRemaining, expanded)
        ChanceNodeResult(
          expanded.outcome,
          expanded.lossTainted,
          expanded.classes,
          expanded.classesResolved,
          expanded.probePruned
        )

  /** A stored value that already answers this node: an exact entry at the same depth, or — outside
    * [[ExpectimaxConfig.exactOnlyMode]] — a fail-soft bound the node's own window has already been driven past.
    *
    * Strict inequalities, matching the Star1/Star2 cutoffs: a root candidate whose stored bound exactly ties alpha may
    * still equal the best and must reach the random tie-break, not vanish into the table (#69). One closure, not a
    * chain of them: this runs once per reply of every decision node in the tree.
    */
  private def ttHit(key: Long, ctx: SearchContext): Option[ChanceNodeResult] =
    tt.flatMap: table =>
      table
        .probe(key)
        .flatMap: entry =>
          if entry.depth != ctx.pliesRemaining then None
          else if entry.bound == TTBound.Exact then
            Some(ChanceNodeResult.fromTable(SearchOutcome.Exact(entry.value, ctx.control.workUsed), entry.lossTainted))
          else if !config.exactOnlyMode && boundSettlesWindow(entry, ctx) then
            val outcome =
              if entry.bound == TTBound.UpperBound then SearchOutcome.UpperBound(entry.value, ctx.control.workUsed)
              else SearchOutcome.LowerBound(entry.value, ctx.control.workUsed)
            Some(ChanceNodeResult.fromTable(outcome, entry.lossTainted))
          else None

  /** Whether the stored bound points the right way for this node's window and has been driven past it. */
  private def boundSettlesWindow(entry: TTEntry, ctx: SearchContext): Boolean =
    entry.bound match
      case TTBound.UpperBound => entry.value < ctx.alpha
      case TTBound.LowerBound => entry.value > ctx.beta
      case TTBound.Exact      => false

  /** Writes a finished or bounded node to the table; an [[SearchOutcome.Incomplete]] node is never stored. */
  private def storeNode(key: Long, pliesRemaining: Int, result: ChanceNode.Result): Unit =
    tt.foreach: table =>
      result.outcome match
        case SearchOutcome.Exact(value, _) =>
          table.store(key, value, TTBound.Exact, pliesRemaining, result.lossTainted)
        case SearchOutcome.UpperBound(value, _) if !config.exactOnlyMode =>
          table.store(key, value, TTBound.UpperBound, pliesRemaining, result.lossTainted)
        case SearchOutcome.LowerBound(value, _) if !config.exactOnlyMode =>
          table.store(key, value, TTBound.LowerBound, pliesRemaining, result.lossTainted)
        case _ => ()

  /** This search's side of the shared chance node: reply generation through the canonical turn-state visitor, the
    * terminal and forced-pass cases, the batched leaf layer one ply above the evaluator, Star2 probes by the
    * material-best reply, and the fail-soft decision node deeper in the tree.
    *
    * One instance serves one node; the replies generated for the probe phase are kept per class so the roll loop never
    * generates them twice, and a probe's exact value is reused by the decision node for the reply it searched.
    */
  final private class NodePolicy(toMove: GameState, ctx: SearchContext) extends ChanceNode.Policy:
    private val bounds  = ctx.bounds
    private val myColor = ctx.myColor
    private val leafPly = ctx.pliesRemaining == 1
    private val classes = ActiveDicePartitioner.partitionsFor(toMove).length
    private val replies = Array.fill[ChanceNode.Replies](classes)(PendingReplies)

    /** The reply the probe searched for each class (or [[NoProbe]]) and the exact value it found, reused by the
      * decision node rather than searched twice.
      */
    private val probeReply = Array.fill(classes)(NoProbe)
    private val probeValue = new Array[ChanceNode.Value](classes)

    /** A class whose mover captures the king: a win for MAX, a loss for MIN — tainted, because the loss is ours. */
    private val terminal =
      ChanceNode.Value.exact(bounds.terminalFor(toMove.activeColor), lossTainted = !ctx.maximizing)

    /** One ply above the evaluator only the mover's own king capture is terminal, so a MAX node cannot score below the
      * evaluator's floor and a MIN node cannot score above its ceiling; deeper nodes can reach either sentinel.
      */
    def lowestValue: Double  = if ctx.maximizing && leafPly then bounds.minNonTerminal else bounds.terminalLoss
    def highestValue: Double = if !ctx.maximizing && leafPly then bounds.maxNonTerminal else bounds.terminalWin

    /** The class's replies, generated once. [[InterruptedReplies]] means the shared budget ran out inside the
      * generation, which leaves the control cancelled; the caller answers `Incomplete`, never a forced pass.
      */
    private def repliesAt(index: Int, rolled: GameState): ChanceNode.Replies =
      val cached = replies(index)
      if cached ne PendingReplies then cached
      else
        val generated = ChanceNode.Replies.generate(rolled, ctx.control) match
          case Some(fresh) => fresh
          case None        => InterruptedReplies
        replies(index) = generated
        generated

    override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]] =
      val values = new Array[ChanceNode.Value](classes)
      if !generateAll(rolled) then Some(Array.fill(classes)(ChanceNode.Value.incomplete(ctx.control)))
      else
        if leafPly then leafProbes(rolled, values) else innerProbes(rolled, values)
        Some(values)

    /** Generates every class's replies up front, since probing needs the material-best reply of each. */
    private def generateAll(rolled: IArray[GameState]): Boolean =
      var index    = 0
      var complete = true
      while index < classes && complete do
        if ctx.control.isCancelled || (repliesAt(index, rolled(index)) eq InterruptedReplies) then complete = false
        index += 1
      complete

    /** Probing one ply above the evaluator: every probe is a single position — the material-best reply, or the forced
      * pass — so all of them are scored in one batch. A pass is exact; a reply bounds its class in the role's
      * direction.
      */
    private def leafProbes(rolled: IArray[GameState], values: Array[ChanceNode.Value]): Unit =
      val leaves    = scala.collection.mutable.ArrayBuffer.empty[GameState]
      val leafIndex = Array.fill(classes)(NoProbe)
      var index     = 0
      while index < classes do
        val current = replies(index)
        if current.capturesKing then values(index) = terminal
        else
          leafIndex(index) = leaves.length
          leaves += (if current.isForcedPass then rolled(index).endTurn()
                     else current.states(probeIndex(current.states)))
        index += 1
      if leaves.nonEmpty then
        val leafArray = leaves.toArray
        // Scored on a copy: deduplication compacts its input in place, and the per-class lookup below still indexes
        // the original order, so two classes probing the same position would otherwise read each other's leaf.
        val scores = probeScoreMap(leafArray.clone(), myColor)
        index = 0
        while index < classes do
          val leaf = leafIndex(index)
          if leaf != NoProbe then
            val score = scores(leafKey(leafArray(leaf))).toDouble
            val work  = ctx.control.workUsed
            values(index) =
              if replies(index).isForcedPass then ChanceNode.Value.exact(score, workUsed = work)
              else if ctx.maximizing then ChanceNode.Value(SearchOutcome.LowerBound(score, work))
              else ChanceNode.Value(SearchOutcome.UpperBound(score, work))
          index += 1

    /** Probing deeper in the tree: each probe is itself a chance node, searched with an open window and therefore exact
      * — a forced pass settles its class outright, a reply's value is kept for the decision node.
      */
    private def innerProbes(rolled: IArray[GameState], values: Array[ChanceNode.Value]): Unit =
      var index       = 0
      var interrupted = false
      while index < classes do
        val current = replies(index)
        val value   =
          if interrupted then ChanceNode.Value.incomplete(ctx.control)
          else if current.capturesKing then terminal
          else if ctx.control.isCancelled then ChanceNode.Value.incomplete(ctx.control)
          else if current.isForcedPass then toValue(recursiveChanceNode(rolled(index).endTurn(), ctx.childOpen))
          else
            val reply = probeIndex(current.states)
            val found = toValue(recursiveChanceNode(current.states(reply), ctx.childOpen))
            found.outcome match
              case SearchOutcome.Exact(value, work) =>
                // One reply's exact value bounds the class in the role's direction; the decision node reuses it.
                probeReply(index) = reply
                probeValue(index) = found
                val bound =
                  if ctx.maximizing then SearchOutcome.LowerBound(value, work)
                  else SearchOutcome.UpperBound(value, work)
                ChanceNode.Value(bound, found.lossTainted)
              case _ => found
        if value.outcome.isIncomplete then interrupted = true
        values(index) = value
        index += 1

    /** The reply whose resulting position is best for the mover by the material proxy: the first such reply in
      * canonical order, so a budgeted and an unbudgeted search probe the same line.
      */
    private def probeIndex(states: Array[GameState]): Int =
      var best      = 0
      var bestScore = Evaluator.evaluateMaterial(states(0), myColor)
      var index     = 1
      while index < states.length do
        val score = Evaluator.evaluateMaterial(states(index), myColor)
        if (ctx.maximizing && score > bestScore) || (!ctx.maximizing && score < bestScore) then
          best = index
          bestScore = score
        index += 1
      best

    def decide(rolled: GameState, index: Int, alpha: Double, beta: Double): ChanceNode.Value =
      val current = repliesAt(index, rolled)
      // Reply generation is atomic and can be long; a deadline that passed inside it must not be followed by an
      // evaluator batch, so the control is re-read before any evaluation (the budget case is the interrupted list).
      if (current eq InterruptedReplies) || ctx.control.isCancelled then ChanceNode.Value.incomplete(ctx.control)
      else if current.capturesKing then terminal
      else if current.isForcedPass then
        if leafPly then ChanceNode.Value.exact(evalOne(rolled.endTurn(), myColor), workUsed = ctx.control.workUsed)
        else toValue(recursiveChanceNode(rolled.endTurn(), ctx.childWithWindow(alpha, beta)))
      else if leafPly then leafDecisionValue(current.states)
      else searchDecisionNode(current.states, alpha, beta, probeReply(index), index)

    /** The mover's best reply by the leaf evaluator, scored in one batch.
      *
      * Leaves are **deduplicated by position** before scoring. Dice Chess turns are 1–3 micro-moves, and reordering
      * independent micro-moves reaches the same board — measured at ~78% duplicate leaves per chance node, i.e. only
      * about a fifth of the generated replies are distinct positions. Since the value taken here is a minimum or a
      * maximum, which over a multiset equals the same over its distinct elements, dropping duplicates is exact: the
      * returned value is unchanged, only the evaluator does less work. This matters because the evaluator is the
      * search's dominant cost (a trained model behind a JNI call), while the deduplication is a hash per leaf. The
      * replies array is consumed in place; nothing reads it after the leaf layer has scored it.
      */
    private def leafDecisionValue(states: Array[GameState]): ChanceNode.Value =
      val scores = evalBatch(distinctLeaves(states), myColor)
      var value  = if ctx.maximizing then Int.MinValue else Int.MaxValue
      var index  = 0
      while index < scores.length do
        value = if ctx.maximizing then math.max(value, scores(index)) else math.min(value, scores(index))
        index += 1
      ChanceNode.Value.exact(value.toDouble, workUsed = ctx.control.workUsed)

    /** The full decision node: replies in material order, each searched under the node's tightening window, folded
      * fail-soft into an exact best or a bound. The reply the probe already searched to an exact value is reused.
      */
    private def searchDecisionNode(
        states: Array[GameState],
        alpha: Double,
        beta: Double,
        probedReply: Int,
        index: Int
    ): ChanceNode.Value =
      val ordered = orderedReplies(states)
      val node    = new DecisionNodeScratch(alpha, beta)
      var k       = 0
      while k < ordered.length && node.result.isEmpty do
        val reply = ordered(k)
        val child =
          if reply == probedReply then probeValue(index)
          else toValue(recursiveChanceNode(states(reply), ctx.childWithWindow(node.localAlpha, node.localBeta)))
        foldDecisionChild(node, child)
        k += 1
      node.value

    /** Reply indices ordered best-first for this node's role by the same material proxy the probing phase uses — a
      * stable order, since equal scores keep canonical order. One packed `Long` per reply and one primitive sort: this
      * runs once per class of every inner chance node.
      */
    private def orderedReplies(states: Array[GameState]): Array[Int] =
      val keys  = new Array[Long](states.length)
      var index = 0
      while index < states.length do
        val score = Evaluator.evaluateMaterial(states(index), myColor).toLong
        val rank  =
          math.max(-Int.MaxValue.toLong, math.min(Int.MaxValue.toLong, if ctx.maximizing then -score else score))
        keys(index) = (rank << 32) | index.toLong
        index += 1
      java.util.Arrays.sort(keys)
      val ordered = new Array[Int](states.length)
      index = 0
      while index < ordered.length do
        ordered(index) = (keys(index) & 0xffffffffL).toInt
        index += 1
      ordered

    private def foldDecisionChild(node: DecisionNodeScratch, child: ChanceNode.Value): Unit =
      node.lossTainted ||= child.lossTainted
      child.outcome match
        case SearchOutcome.Exact(value, _)      => foldExactChild(node, value)
        case SearchOutcome.UpperBound(value, _) =>
          // An upper bound is a usable value only for the maximizing role; for the minimizing role it settles the node.
          if ctx.maximizing then node.boundBest = math.max(node.boundBest, value)
          else node.cutoff(SearchOutcome.UpperBound(value, ctx.control.workUsed))
        case SearchOutcome.LowerBound(value, _) =>
          // A lower bound is a usable value only for the minimizing role; for the maximizing role it settles the node.
          if ctx.maximizing then node.cutoff(SearchOutcome.LowerBound(value, ctx.control.workUsed))
          else node.boundBest = math.min(node.boundBest, value)
        case SearchOutcome.Incomplete(_) => node.result = Some(child)

    /** An exact child advances this node's own best value and its window; crossing the caller's bound ends the node. */
    private def foldExactChild(node: DecisionNodeScratch, value: Double): Unit =
      node.hasExact = true
      if ctx.maximizing then
        node.exactBest = math.max(node.exactBest, value)
        node.localAlpha = math.max(node.localAlpha, node.exactBest)
        if node.exactBest > node.beta then node.cutoff(SearchOutcome.LowerBound(node.exactBest, ctx.control.workUsed))
      else
        node.exactBest = math.min(node.exactBest, value)
        node.localBeta = math.min(node.localBeta, node.exactBest)
        if node.exactBest < node.alpha then node.cutoff(SearchOutcome.UpperBound(node.exactBest, ctx.control.workUsed))

    /** Mutable fail-soft state of one decision node: the window as it tightens, the best exact and best bounded child
      * seen so far, and the cutoff or abort that ended the reply loop.
      */
    final private class DecisionNodeScratch(val alpha: Double, val beta: Double):
      var localAlpha  = alpha
      var localBeta   = beta
      var exactBest   = if ctx.maximizing then bounds.terminalLoss else bounds.terminalWin
      var boundBest   = if ctx.maximizing then bounds.terminalLoss else bounds.terminalWin
      var hasExact    = false
      var lossTainted = false

      var result: Option[ChanceNode.Value] = None

      def cutoff(outcome: SearchOutcome[Double]): Unit = result = Some(ChanceNode.Value(outcome, lossTainted))

      /** The node's value once the reply loop has stopped: the cutoff, the best exact child, or the bound the children
        * prove together.
        *
        * The best exact child is the node's exact value only when no bounded child could still beat it. A MAX node
        * whose exact best is 50 and whose other reply came back as "at most 80" (it failed low against the caller's
        * alpha, which was above 50) is worth somewhere in `[50, 80]` — an upper bound of 80, not an exact 50. Reporting
        * it exact would let the chance node above fold a too-low value into an expectation it then treats as complete.
        */
      def value: ChanceNode.Value =
        if result.isDefined then result.get
        else if hasExact && (if ctx.maximizing then boundBest <= exactBest else boundBest >= exactBest) then
          ChanceNode.Value.exact(exactBest, lossTainted, ctx.control.workUsed)
        else if ctx.maximizing then
          ChanceNode.Value(SearchOutcome.UpperBound(math.max(exactBest, boundBest), ctx.control.workUsed), lossTainted)
        else
          ChanceNode.Value(SearchOutcome.LowerBound(math.min(exactBest, boundBest), ctx.control.workUsed), lossTainted)

  private def toValue(result: ChanceNodeResult): ChanceNode.Value = ChanceNode.Value(result.outcome, result.lossTainted)

  private def transpositionKey(state: GameState, maximizing: Boolean): Long =
    if maximizing then state.zobristHash ^ MaxChanceKeySalt else state.zobristHash

  /** One batched evaluation of the distinct probe leaves, keyed so each class can look its own (possibly duplicated)
    * leaf back up. Consumes `rawProbeStates` (see [[distinctLeaves]]): callers that index it afterwards pass a copy.
    */
  private def probeScoreMap(
      rawProbeStates: Array[GameState],
      myColor: Color
  ): scala.collection.mutable.HashMap[LeafKey, Int] =
    val distinctProbeStates = distinctLeaves(rawProbeStates)
    val probeScores         = evalBatch(distinctProbeStates, myColor)
    val scoreMap            = new scala.collection.mutable.HashMap[LeafKey, Int](distinctProbeStates.length * 2, 0.75)
    var m                   = 0
    while m < distinctProbeStates.length do
      scoreMap.put(leafKey(distinctProbeStates(m)), probeScores(m))
      m += 1
    scoreMap

  /** The distinct positions among `leaves`, in first-seen order.
    *
    * **Mutates `leaves`**, compacting the distinct entries into its front. That is safe only because every caller owns
    * the array and never looks at it again, and it is worth the sharper contract on a path that runs once per dice
    * class per candidate: the obvious version — write into a second full-length array, then slice — allocates an extra
    * array the size of the whole reply list every time, and with ~78% duplicates that array is discarded almost
    * immediately. Compaction is safe in place because the write index never overtakes the read index (`count <= i`
    * throughout), so no unread element is ever overwritten.
    *
    * Returns `leaves` itself when nothing was duplicated, so the no-op case allocates nothing at all.
    */
  private def distinctLeaves(leaves: Array[GameState]): Array[GameState] =
    val seen  = new scala.collection.mutable.HashSet[LeafKey](leaves.length * 2, 0.75)
    var count = 0
    var i     = 0
    while i < leaves.length do
      val leaf = leaves(i)
      if seen.add(leafKey(leaf)) then
        leaves(count) = leaf
        count += 1
      i += 1
    if count == leaves.length then leaves else leaves.slice(0, count)

  /** A leaf's exact identity for deduplication.
    *
    * Exactness is the whole point: a key that merged two genuinely different positions could discard the one holding
    * the minimum and silently change the search's value, so this carries every field that distinguishes a position for
    * an arbitrary evaluator.
    *
    * [[dicechess.engine.domain.GameState]] *would* work as the key — it hand-overrides `equals`/`hashCode` and compares
    * the mailbox by content (`java.util.Arrays.equals`), so transposed positions do match. The reason for a separate
    * key is **cost, not correctness**: `GameState.hashCode` runs `java.util.Arrays.hashCode` over the 64-entry mailbox,
    * while this mixes eleven primitives in register arithmetic. On a path that hashes once per leaf, tens of thousands
    * of times per chance node, that difference is the whole point of the deduplication.
    *
    * `mailbox` is omitted because it is a redundant index over the same eight bitboards, not independent state. `flags`
    * is included as a whole: it packs castling rights and the half-move clock, both of which genuinely differ between
    * replies (a capture resets the clock, a quiet move does not). `fullMoveNumber` is constant across one chance node's
    * leaves today — all of them come from the same base position — but is kept in the key so this stays a complete
    * position identity rather than one that depends on the caller's invariants.
    */
  final private case class LeafKey(
      white: Long,
      black: Long,
      pawns: Long,
      knights: Long,
      bishops: Long,
      rooks: Long,
      queens: Long,
      kings: Long,
      enPassant: Long,
      flags: Int,
      fullMoveNumber: Int
  ):
    /** Hand-written because the generated one is unaffordable here: a derived case-class `hashCode` hashes through
      * `productElement`, which returns `Any` and therefore **boxes all eleven primitive fields on every call** — an
      * allocation storm on a path that runs once per leaf, tens of thousands of times per chance node. Mixing the
      * fields directly in `Long` arithmetic keeps the whole computation in registers. The generated `equals` is left
      * alone: it compares fields in their primitive types and does not box.
      */
    override def hashCode: Int =
      var h = white
      h = h * 31 + black
      h = h * 31 + pawns
      h = h * 31 + knights
      h = h * 31 + bishops
      h = h * 31 + rooks
      h = h * 31 + queens
      h = h * 31 + kings
      h = h * 31 + enPassant
      h = h * 31 + flags
      h = h * 31 + fullMoveNumber
      (h ^ (h >>> 32)).toInt

  private def leafKey(state: GameState): LeafKey =
    LeafKey(
      state.whitePieces.value,
      state.blackPieces.value,
      state.pawns.value,
      state.knights.value,
      state.bishops.value,
      state.rooks.value,
      state.queens.value,
      state.kings.value,
      state.enPassant.value,
      state.flags.value,
      state.fullMoveNumber
    )

  /** Plays every micro-move of `path` (the active color is preserved within a turn) and ends the turn, yielding the
    * position with the other side to move and an empty dice pool.
    */
  private def applyTurn(base: GameState, path: List[Move]): GameState =
    path.foldLeft(base)((s, move) => s.makeMove(move)).endTurn()

  /** Whether `path` (played by `base.activeColor`) ends by capturing the opposing king — same test [[SearchScoring]]
    * uses, applied to either ply.
    */
  private def capturesEnemyKing(base: GameState, path: List[Move]): Boolean =
    val mover      = base.activeColor
    val beforeLast = path.init.foldLeft(base)((s, move) => s.makeMove(move))
    beforeLast.mailbox.get(path.last.toSquare).exists(p => p.pieceType == PieceType.King && p.color != mover)

  private def evalOne(state: GameState, color: Color): Double =
    evalBatch(Array(state), color)(0).toDouble

/** One chance node's result as the search machinery passes it up: the [[SearchOutcome]] a parent folds, ranks, prunes
  * or abandons on, the loss taint [[RootRescore]] respects, and the telemetry [[RootSearchStats]] reports. An
  * [[SearchOutcome.Incomplete]] outcome carries no expectation at all, so a cancelled node cannot be ranked by
  * construction (#496).
  *
  * @param classes
  *   how many partition classes the node had (`0` for a table hit, which expanded nothing)
  * @param classesResolved
  *   how many of them contributed a value before the node finished or was cut
  * @param probePruned
  *   whether the Star2 probe sum settled the node before any class was expanded
  * @param fromTT
  *   true when the result came from the transposition table rather than an expansion. The root counts such candidates
  *   in ttHits/ttCutoffs, never in candidatesCompleted/cutoffs: a TT-resolved candidate did zero chance-node work, and
  *   folding it into the expansion counters would corrupt the effective-width telemetry that RootSearchStats exists to
  *   measure (#494).
  */
final private case class ChanceNodeResult(
    outcome: SearchOutcome[Double],
    lossTainted: Boolean,
    classes: Int = 0,
    classesResolved: Int = 0,
    probePruned: Boolean = false,
    fromTT: Boolean = false
)

private object ChanceNodeResult:
  /** A node the control cancelled: no value, only the work consumed so far. */
  def incomplete(control: SearchControl): ChanceNodeResult =
    ChanceNodeResult(SearchOutcome.Incomplete(control.workUsed), lossTainted = false)

  def fromTable(outcome: SearchOutcome[Double], lossTainted: Boolean): ChanceNodeResult =
    ChanceNodeResult(outcome, lossTainted, fromTT = true)

/** Everything a chance node needs about *where* it sits — utility scale, remaining plies, role, control and window —
  * carried as one value so descending the tree is a single explicit transformation rather than ten positional
  * arguments. The two combinators are the only ways the tree moves: [[childWithWindow]] goes one ply down with the
  * roles swapped under a decision node's window, [[childOpen]] does the same with no window for a Star2 probe.
  *
  * @param bounds
  *   the utility scale, whose `perspective` is the root mover every value is stated for
  * @param pliesRemaining
  *   decision plies still below the current chance node
  * @param maximizing
  *   whether the side to move at the node below is us (MAX) or the opponent (MIN)
  */
final private case class SearchContext(
    bounds: UtilityBounds,
    pliesRemaining: Int,
    maximizing: Boolean,
    control: SearchControl,
    alpha: Double,
    beta: Double
):
  def myColor: Color = bounds.perspective

  /** One ply down with the roles swapped and the window a decision node has tightened. */
  def childWithWindow(newAlpha: Double, newBeta: Double): SearchContext =
    copy(pliesRemaining = pliesRemaining - 1, maximizing = !maximizing, alpha = newAlpha, beta = newBeta)

  /** One ply down with the roles swapped and no window — what a Star2 probe searches with. */
  def childOpen: SearchContext = childWithWindow(Double.NegativeInfinity, Double.PositiveInfinity)

object ExpectimaxSearch:

  /** Blends a completed root candidate in the same domain used for final ranking. Kept as one function so ranking and
    * the inverse Star bound cannot silently drift to different arithmetic.
    */
  private[search] def blendRootScore(searchValue: Double, rescoreValue: Double, weight: Double): Double =
    (1.0 - weight) * searchValue + weight * rescoreValue

  /** Converts the best completed root score back into the current candidate's search-score domain.
    *
    * For `final = (1 - w) * search + w * rescore`, a non-loss-tainted candidate can only beat `bestFinal` when its
    * search value reaches `(bestFinal - w * rescore) / (1 - w)`. The returned value is made conservatively smaller by
    * one representable final-score step and one transformed-score step: Star cutoffs use strict `<`, so a candidate
    * which would round to an exact final tie must still survive for the seeded random tie-break.
    *
    * `min(bestFinal, transformed)` also protects [[RootRescore]]'s loss-taint rule. Until the chance node completes we
    * do not know whether rescoring will be suppressed; if it is, the candidate's final score is its raw search value,
    * so pruning above the unblended best score would be unsound. At weight one no finite inverse exists and pruning is
    * explicitly disabled. NaN input likewise fails open to full search rather than leaking into bound comparisons.
    */
  private[search] def transformedRootAlpha(bestFinal: Double, rescoreValue: Double, weight: Double): Double =
    if bestFinal.isNaN || bestFinal == Double.NegativeInfinity || weight.isNaN || weight >= 1.0 then
      Double.NegativeInfinity
    else if weight <= 0.0 then bestFinal
    else
      val strictFinal = java.lang.Math.nextDown(bestFinal)
      val transformed = (strictFinal - weight * rescoreValue) / (1.0 - weight)
      if transformed.isNaN then Double.NegativeInfinity
      else math.min(bestFinal, java.lang.Math.nextDown(transformed))

  /** Upper score bound for non-king evaluation values (e.g. ONNX score scale [0, 10000]). Used by Star1 chance-node
    * pruning to bound the maximum possible expectation of unprocessed dice rolls ($S + R \cdot U$).
    */
  private[search] val UpperScoreBound: Double = 10000.0

  /** Terminal win inside a deeper tree. One point above the leaf-model ceiling is sufficient to dominate every
    * non-terminal reply while keeping Star bounds tight; using `Int.MaxValue` here would make chance pruning inert.
    */
  private val WinValue: Double = UpperScoreBound + 1.0

  /** Value of a leaf in which the opponent captures our king. Chosen far below any real evaluation on any scale
    * (material centipawns or a scaled win-probability) so the opponent always prefers it and such a line always ranks
    * last — without tying the search to a particular evaluator's range.
    */
  private val LossValue: Double = -1e9

  /** Separates MAX-role chance nodes from MIN-role nodes in the TT. `activeColor` alone is insufficient at depth 3: an
    * inner node is evaluated for the active player, while the same position at depth 2 is evaluated for its opponent.
    */
  private val MaxChanceKeySalt: Long = 0x9e3779b97f4a7c15L

  /** Per-class reply slots of a [[NodePolicy]] start here and move to a generated list, or to [[InterruptedReplies]]
    * when the shared budget ran out inside the generation.
    */
  private val PendingReplies: ChanceNode.Replies     = new ChanceNode.Replies(Array.empty, capturesKing = false)
  private val InterruptedReplies: ChanceNode.Replies = new ChanceNode.Replies(Array.empty, capturesKing = false)

  /** "No reply was probed for this class" in a [[NodePolicy]]'s per-class probe index. */
  private val NoProbe: Int = -1

  /** The utility scale the recursive tree reasons on, declared for `perspective` (the root mover): the terminal
    * sentinels [[LossValue]] and [[WinValue]] strictly dominate the non-terminal range `[-UpperScoreBound,
    * UpperScoreBound]` that leaf evaluators must stay inside. Chance-node expectations, Star bounds and transposition
    * entries are all stated on this scale; only the root's immediate king capture uses
    * [[SearchScoring.TerminalWinScore]] instead, before any tree is built.
    */
  def utilityBounds(perspective: Color): UtilityBounds =
    UtilityBounds(perspective, LossValue, -UpperScoreBound, UpperScoreBound, WinValue)

  /** Default stats sink: discard. `private[search]` so JVM-only wiring (e.g. [[OnnxExpectimaxSearch]]) can name the
    * same default instead of re-inventing its own no-op.
    */
  private[search] val NoStats: RootSearchStats => Unit = _ => ()

  /** Default root pre-ranker: material, applied per state — the search's historical, hardcoded behaviour, now just
    * expressed as a batch so it fits the same injectable shape as any other pre-ranker.
    *
    * Public because `preRank` is: a host that supplies its own pre-ranker needs to be able to name the default it is
    * replacing — to fall back to it when its model is unavailable, or to compare against it — and an external caller
    * had no way to do that while this was package-private.
    */
  def materialBatch(states: Array[GameState], color: Color): Array[Int] =
    states.map(Evaluator.evaluateMaterial(_, color))
