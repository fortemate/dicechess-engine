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

/** One move's root telemetry from [[ExpectimaxSearch]]: how wide the decision node really was.
  *
  * `candidatesCompleted < candidatesSelected` means the wall-clock deadline truncated the anytime loop — the move was
  * chosen among fewer candidates than [[ExpectimaxConfig.candidateLimit]] allowed. Persistently tiny completion counts
  * on production hardware mean the effective search degenerates toward "play the pre-ranker's first pick", which no
  * amount of candidate-limit tuning can fix — that evidence is exactly what this type exists to surface.
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
  *   how many selected candidates were fully expanded through the chance node — the only ones whose values are
  *   comparable, and therefore the only ones ranked
  * @param candidatesAbandoned
  *   candidates whose chance node was cut mid-expansion by the deadline (at most one: the search stops afterwards).
  *   Their partial expectation is discarded rather than ranked, so this counts work paid for and thrown away — a
  *   persistent non-zero value means the per-move budget is too small for even one candidate at this width.
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
    ttProbes: Int = 0, // candidates for which the table was consulted (tt configured)
    ttHits: Int = 0,   // candidates resolved by an EXACT entry — ranked, but never expanded
    ttCutoffs: Int = 0 // candidates rejected by a stored UPPER bound before any expansion
):
  /** Whether the deadline cut the loop short of the selected candidate set. */
  def deadlineTruncated: Boolean = candidatesCompleted + cutoffs + ttHits + ttCutoffs < candidatesSelected

  /** The deadline elapsed before a single candidate could be scored, so the turn came from the pre-ranker alone — the
    * search contributed nothing beyond candidate selection.
    */
  def fellBackToPreRank: Boolean = candidatesCompleted == 0 && ttHits == 0 && candidatesAbandoned > 0

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
  */
final class ExpectimaxSearch(
    evalBatch: (Array[GameState], Color) => Array[Int],
    config: ExpectimaxConfig = ExpectimaxConfig(),
    rootRescore: Option[RootRescore] = None,
    preRank: (Array[GameState], Color) => Array[Int] = ExpectimaxSearch.materialBatch,
    statsSink: RootSearchStats => Unit = ExpectimaxSearch.NoStats,
    tt: Option[TranspositionTable] = None
) extends TimeBudgetedSearch:

  import ExpectimaxSearch.*

  override def findBestMove(state: GameState): Option[ScoredSequence] =
    findBestMove(state, new Random())

  /** Finds the best turn with an explicit `Random`, running to completion over every candidate. */
  override def findBestMove(state: GameState, rand: Random): Option[ScoredSequence] =
    findBestMove(state, NoDeadline, rand)

  /** Finds the best turn under a wall-clock deadline.
    *
    * Candidates are expanded in material-ranked order, and the deadline is honoured **between dice rolls inside** a
    * candidate's chance node — not merely between candidates. Even the top candidate can therefore be abandoned before
    * it yields a comparable value, which is the point: one candidate routinely costs more than the whole per-turn
    * budget, so a coarser check made the deadline advisory rather than binding (#496).
    *
    * The result is the best *completed* candidate, or — when the deadline left no candidate time to finish — the
    * pre-ranker's own top pick, so the anytime contract still delivers a legal turn. A partially expanded candidate is
    * never ranked: its expectation covers only the rolls the clock allowed, so comparing it against complete ones would
    * let the instant the deadline landed choose the move. [[RootSearchStats]] reports both cases.
    *
    * All of this matters because a single roll can generate thousands of opponent replies (the Dice Chess branching
    * tail).
    */
  override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] =
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
        case None => Some(searchRoot(state, paths, deadlineNanos, random))

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
      deadlineNanos: Long,
      random: Random
  ): ScoredSequence =
    val myColor    = state.activeColor
    val candidates = selectRootCandidates(state, paths, myColor)
    val expansion  = new RootExpansion
    expandRootCandidates(candidates, myColor, deadlineNanos, expansion)
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
      deadlineNanos: Long,
      expansion: RootExpansion
  ): Unit =
    val entries             = candidates.entries
    val activeRootRescore   = rootRescoreValues(entries, myColor, deadlineNanos)
    val expiredBeforeChance = expired(deadlineNanos)
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
        rootChanceNode(resultState, myColor, deadlineNanos, alpha),
        activeRootRescore,
        i,
        expansion
      )
      i += 1
      if continue && expired(deadlineNanos) then continue = false

  /** The rescore weight paired with one score per candidate, or `None` when no rescorer is configured, weight zero
    * disabled it, or the deadline elapsed before the batch could start.
    *
    * Weight zero is exactly the unrescored path and deliberately does not invoke an otherwise configured evaluator.
    */
  private def rootRescoreValues(
      entries: Array[(List[Move], GameState)],
      myColor: Color,
      deadlineNanos: Long
  ): Option[(Double, Array[Int])] =
    rootRescore match
      case Some(RootRescore(rescoreEval, weight)) if weight > 0.0 && !expired(deadlineNanos) =>
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

  /** The configured chance-node expansion of one root candidate. */
  private def rootChanceNode(
      resultState: GameState,
      myColor: Color,
      deadlineNanos: Long,
      searchAlpha: Double
  ): ChanceNodeResult =
    if config.searchDepth == 2 then chanceNodeValue(resultState, myColor, deadlineNanos, searchAlpha)
    else depthThreeChanceNodeValue(resultState, myColor, deadlineNanos, searchAlpha)

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
    if res.complete then
      expansion.rank(path, blendedRootValue(res, activeRootRescore, index))
      // A TT-exact hit is ranked like a completed candidate (the value IS exact, alpha may advance from it), but it
      // did no expansion work — counting it as completed would inflate the effective-width metric.
      if res.fromTT then expansion.ttHits += 1 else expansion.completed += 1
      true
    else if res.pruned then
      recordPrunedCandidate(res, expansion)
      true
    else
      expansion.abandoned += 1
      false

  /** The candidate's final ranking score: its raw search value, or that value blended with the root rescore — never
    * blended for a line that loses our king on some roll, at any weight.
    */
  private def blendedRootValue(
      res: ChanceNodeResult,
      activeRootRescore: Option[(Double, Array[Int])],
      index: Int
  ): Double =
    activeRootRescore match
      case Some((weight, values)) if !res.lossTainted => blendRootScore(res.value, values(index).toDouble, weight)
      case _                                          => res.value

  /** Attributes a pruned candidate to the mechanism that pruned it, keeping TT reuse out of the expansion counters. */
  private def recordPrunedCandidate(res: ChanceNodeResult, expansion: RootExpansion): Unit =
    if res.fromTT then expansion.ttCutoffs += 1
    else
      expansion.cutoffs += 1
      if res.probePruned then expansion.probeCutoffs += 1
      expansion.rollsSaved += (DiceRolls.byWeightDescending.length - res.rollsProcessed)

  /** The pre-ranked root candidate set: the turns to expand with the position each reaches, plus the top pre-rank score
    * that the anytime fallback plays when no candidate finishes.
    */
  final private class RootCandidates(val entries: Array[(List[Move], GameState)], val topPreRankScore: Int)

  /** Mutable accumulator for one root expansion loop: the width counters [[RootSearchStats]] reports, the ranked
    * candidates, and the best blended score so far — which is the alpha every later candidate prunes against.
    */
  final private class RootExpansion:
    var completed    = 0
    var abandoned    = 0
    var cutoffs      = 0
    var rollsSaved   = 0
    var probeCutoffs = 0
    var ttProbes     = 0
    var ttHits       = 0
    var ttCutoffs    = 0
    var bestFinal    = Double.NegativeInfinity

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
        ttCutoffs
      )

  /** The expectation, over the 56 weighted dice outcomes, of the opponent's best reply value (from `myColor`'s view),
    * alongside whether any single roll forced [[ExpectimaxSearch.LossValue]] (the opponent capturing our king outright)
    * — tracked precisely per roll (an exact match against the sentinel, not a threshold on the weighted average) so
    * [[RootRescore]] can never rescue a line that is lost on even one roll, however small its weight.
    *
    * [[ChanceNodeResult.complete]] says whether every weighted roll was processed before `deadlineNanos`. `false` means
    * the expansion was cut short, so the accumulated value is a partial expectation over an arbitrary prefix of the
    * roll order — the caller must discard it rather than rank it against complete ones (#496).
    *
    * @param oppToMove
    *   position after our turn: the opponent is to move and the dice pool is empty.
    * @param deadlineNanos
    *   [[ExpectimaxSearch.NoDeadline]] for the untimed path, which skips the clock entirely
    */
  private def chanceNodeValue(
      oppToMove: GameState,
      myColor: Color,
      deadlineNanos: Long,
      alpha: Double
  ): ChanceNodeResult =
    val key = oppToMove.zobristHash
    ttChanceHit(key, alpha).getOrElse(computeChanceNodeValue(oppToMove, myColor, deadlineNanos, alpha, key))

  /** A stored value that already answers this chance node: an exact entry at the same depth, or — outside
    * [[ExpectimaxConfig.exactOnlyMode]] — an upper bound provably below `alpha`.
    *
    * Strict inequality, matching the Star1/Star2 cutoffs: a candidate whose stored bound exactly ties alpha may still
    * equal the best and must reach the random tie-break, not vanish into the table (#69).
    */
  private def ttChanceHit(key: Long, alpha: Double): Option[ChanceNodeResult] =
    tt.flatMap(_.probe(key))
      .filter(_.depth == config.searchDepth - 1)
      .flatMap: entry =>
        if entry.bound == TTBound.Exact then Some(ttChanceResult(entry, complete = true, pruned = false))
        else if !config.exactOnlyMode && entry.bound == TTBound.UpperBound && entry.value < alpha then
          Some(ttChanceResult(entry, complete = false, pruned = true))
        else None

  private def ttChanceResult(entry: TTEntry, complete: Boolean, pruned: Boolean): ChanceNodeResult =
    ChanceNodeResult(
      value = entry.value,
      lossTainted = entry.lossTainted,
      complete = complete,
      pruned = pruned,
      rollsProcessed = 0,
      fromTT = true
    )

  /** Expands the chance node for real: Star2 probing (only with a finite alpha), then the Star1 roll loop, then one
    * transposition-table store for whatever the two phases produced.
    */
  private def computeChanceNodeValue(
      oppToMove: GameState,
      myColor: Color,
      deadlineNanos: Long,
      alpha: Double,
      key: Long
  ): ChanceNodeResult =
    val scratch = new ChanceNodeScratch
    if alpha > Double.NegativeInfinity then star2Ceiling(scratch, oppToMove, myColor, deadlineNanos, alpha)
    val res =
      if scratch.cutByStar2 then
        ChanceNodeResult(
          value = scratch.remainingCeiling(0),
          lossTainted = scratch.lossTainted,
          complete = false,
          pruned = true,
          rollsProcessed = 0,
          probePruned = true
        )
      else if scratch.cutByDeadline then
        ChanceNodeResult(
          value = 0.0,
          lossTainted = scratch.lossTainted,
          complete = false,
          pruned = false,
          rollsProcessed = 0
        )
      else
        expandChanceRolls(scratch, oppToMove, myColor, deadlineNanos, alpha)
        star1Result(scratch, alpha)
    storeChanceNode(key, res)
    res

  /** Star2 probing phase: bound every roll by its top-1 opponent reply, accumulate those bounds into per-roll suffix
    * ceilings, and cut the whole node when even the best remaining expectation falls below `alpha`.
    */
  private def star2Ceiling(
      scratch: ChanceNodeScratch,
      oppToMove: GameState,
      myColor: Color,
      deadlineNanos: Long,
      alpha: Double
  ): Unit =
    val probes = collectStar2Probes(scratch, oppToMove, myColor, deadlineNanos)
    if !scratch.cutByDeadline then
      fillProbeCeilings(scratch, probes, myColor)
      accumulateCeilingSuffix(scratch)
      if scratch.remainingCeiling(0) < alpha then scratch.cutByStar2 = true

  /** Generates every roll's replies (cached for the roll loop) and picks the one state that bounds each roll. */
  private def collectStar2Probes(
      scratch: ChanceNodeScratch,
      oppToMove: GameState,
      myColor: Color,
      deadlineNanos: Long
  ): Star2Probes =
    val rolls       = DiceRolls.byWeightDescending
    val checkClock  = timed(deadlineNanos)
    val statesBuf   = List.newBuilder[GameState]
    val rollIndices = new Array[Int](rolls.length)
    var stateCount  = 0
    var i           = 0
    while i < rolls.length && !scratch.cutByDeadline do
      if clockExpired(checkClock, deadlineNanos) then scratch.cutByDeadline = true
      else
        val rolled  = oppToMove.withDicePool(rolls(i)._1)
        val replies = TurnGenerator.generateAllLegalTurnPaths(rolled)
        scratch.replies(i) = replies
        star2ProbeState(rolled, replies, oppToMove, myColor) match
          case Some(state) =>
            statesBuf += state
            rollIndices(i) = stateCount
            stateCount += 1
          case None =>
            scratch.lossTainted = true
            rollIndices(i) = Star2Probes.LostRoll
        i += 1
    new Star2Probes(statesBuf.result().toArray, rollIndices)

  /** The single state whose evaluation bounds one roll, or `None` when some reply to that roll captures our king — the
    * ceiling is then the loss sentinel and there is nothing left to score.
    */
  private def star2ProbeState(
      rolled: GameState,
      replies: List[List[Move]],
      oppToMove: GameState,
      myColor: Color
  ): Option[GameState] =
    if replies.isEmpty then Some(oppToMove.endTurn())
    else if replies.exists(reply => capturesEnemyKing(rolled, reply)) then None
    else Some(applyTurn(rolled, replies.minBy(r => Evaluator.evaluateMaterial(applyTurn(rolled, r), myColor))))

  /** Writes each roll's ceiling: one batched evaluation of the distinct probe leaves, then a lookup per roll. With no
    * probe leaves at all, every roll lost our king, so every ceiling is the loss sentinel.
    */
  private def fillProbeCeilings(scratch: ChanceNodeScratch, probes: Star2Probes, myColor: Color): Unit =
    val ceilings = scratch.remainingCeiling
    if probes.states.isEmpty then java.util.Arrays.fill(ceilings, 0, probes.rollIndices.length, LossValue)
    else
      val scores = probeScoreMap(probes.states, myColor)
      var k      = 0
      while k < probes.rollIndices.length do
        val idx = probes.rollIndices(k)
        ceilings(k) = if idx < 0 then LossValue else scores(leafKey(probes.states(idx))).toDouble
        k += 1

  /** Turns the per-roll ceilings into suffix expectations in place: `remainingCeiling(k)` becomes the most the rolls
    * from `k` on can still contribute, which is exactly what Star1 needs at every roll boundary.
    */
  private def accumulateCeilingSuffix(scratch: ChanceNodeScratch): Unit =
    val rolls    = DiceRolls.byWeightDescending
    val ceilings = scratch.remainingCeiling
    var k        = rolls.length - 1
    while k >= 0 do
      ceilings(k) = ceilings(k + 1) + (rolls(k)._2.toDouble / DiceRolls.totalOrderedRolls) * ceilings(k)
      k -= 1

  /** One batched evaluation of the distinct probe leaves, keyed so each roll can look its own (possibly duplicated)
    * leaf back up. Shared by the depth-2 and depth-3 probing phases, which score their probes identically.
    */
  private def probeScoreMap(
      rawProbeStates: Array[GameState],
      myColor: Color
  ): scala.collection.mutable.HashMap[LeafKey, Int] =
    val distinctProbeStates = distinctLeaves(rawProbeStates.clone())
    val probeScores         = evalBatch(distinctProbeStates, myColor)
    val scoreMap            = new scala.collection.mutable.HashMap[LeafKey, Int](distinctProbeStates.length * 2, 0.75)
    var m                   = 0
    while m < distinctProbeStates.length do
      scoreMap.put(leafKey(distinctProbeStates(m)), probeScores(m))
      m += 1
    scoreMap

  /** Star1 roll loop: fold each roll's weighted value into the expectation, stopping when the remaining probability
    * mass can no longer reach `alpha`, or when the deadline lands between two rolls.
    */
  private def expandChanceRolls(
      scratch: ChanceNodeScratch,
      oppToMove: GameState,
      myColor: Color,
      deadlineNanos: Long,
      alpha: Double
  ): Unit =
    val rolls      = DiceRolls.byWeightDescending
    val checkClock = timed(deadlineNanos)
    while scratch.index < rolls.length && !scratch.cutByDeadline && !scratch.cutByStar1 do
      if alpha > Double.NegativeInfinity && star1UpperBound(scratch, alpha) < alpha then scratch.cutByStar1 = true
      else if clockExpired(checkClock, deadlineNanos) then scratch.cutByDeadline = true
      else accumulateRoll(scratch, oppToMove, myColor)

  /** The most the expectation can still reach from the current roll on: the Star2 suffix ceiling when a probing phase
    * ran, otherwise the unprocessed probability mass at the evaluator's ceiling ($S + R \cdot U$).
    */
  private def star1UpperBound(scratch: ChanceNodeScratch, alpha: Double): Double =
    if alpha > Double.NegativeInfinity then scratch.acc + scratch.remainingCeiling(scratch.index)
    else
      val remainingProb =
        (DiceRolls.totalOrderedRolls - scratch.processedWeight).toDouble / DiceRolls.totalOrderedRolls
      scratch.acc + remainingProb * UpperScoreBound

  /** Expands one roll's opponent replies and folds the weighted result into the running expectation. */
  private def accumulateRoll(scratch: ChanceNodeScratch, oppToMove: GameState, myColor: Color): Unit =
    val (roll, weight) = DiceRolls.byWeightDescending(scratch.index)
    val rolled         = oppToMove.withDicePool(roll)
    val replies        = repliesFor(scratch.replies, scratch.index, rolled)
    val rollValue      =
      if replies.isEmpty then evalOne(oppToMove.endTurn(), myColor)
      else opponentMinValue(rolled, replies, myColor)
    if rollValue == LossValue then scratch.lossTainted = true
    scratch.acc += (weight.toDouble / DiceRolls.totalOrderedRolls) * rollValue
    scratch.processedWeight += weight
    scratch.index += 1

  /** The node's result once the roll loop has stopped: a Star1 bound, a partial expectation the caller must discard, or
    * the complete expectation.
    */
  private def star1Result(scratch: ChanceNodeScratch, alpha: Double): ChanceNodeResult =
    if scratch.cutByStar1 then
      ChanceNodeResult(
        value = star1UpperBound(scratch, alpha),
        lossTainted = scratch.lossTainted,
        complete = false,
        pruned = true,
        rollsProcessed = scratch.index
      )
    else
      ChanceNodeResult(
        value = scratch.acc,
        lossTainted = scratch.lossTainted,
        complete = !scratch.cutByDeadline,
        pruned = false,
        rollsProcessed = scratch.index
      )

  private def storeChanceNode(key: Long, res: ChanceNodeResult): Unit =
    tt.foreach: table =>
      if res.complete then table.store(key, res.value, TTBound.Exact, config.searchDepth - 1, res.lossTainted)
      else if res.pruned && !config.exactOnlyMode then
        table.store(key, res.value, TTBound.UpperBound, config.searchDepth - 1, res.lossTainted)

  /** The reply list for one roll: the one a probing phase already generated, or a fresh generation when none ran. */
  private def repliesFor(cached: Array[List[List[Move]]], index: Int, rolled: GameState): List[List[Move]] =
    val stored = cached(index)
    if stored ne null then stored // scalafix:ok(DisableSyntax.null)
    else TurnGenerator.generateAllLegalTurnPaths(rolled)

  /** Mutable state of one depth-2 chance-node expansion: the reply lists the probing phase generated, the Star2
    * per-roll ceilings, the weighted accumulator of the Star1 roll loop, and the three ways that loop can stop.
    */
  final private class ChanceNodeScratch:
    private val totalRolls = DiceRolls.byWeightDescending.length

    val replies: Array[List[List[Move]]] = new Array[List[List[Move]]](totalRolls)
    val remainingCeiling: Array[Double]  = new Array[Double](totalRolls + 1)

    var lossTainted   = false
    var cutByDeadline = false
    var cutByStar1    = false
    var cutByStar2    = false

    var acc             = 0.0
    var processedWeight = 0

    /** Index of the next roll the Star1 loop will expand, and therefore also the count of rolls processed. */
    var index = 0

  /** Depth-3 continuation after one root candidate: opponent chance/MIN, then our chance/MAX, then the leaf model. */
  private def depthThreeChanceNodeValue(
      oppToMove: GameState,
      myColor: Color,
      deadlineNanos: Long,
      alpha: Double
  ): ChanceNodeResult =
    val result = recursiveChanceNode(
      oppToMove,
      SearchContext(
        myColor,
        pliesRemaining = config.searchDepth - 1,
        maximizing = false,
        deadlineNanos,
        alpha,
        beta = Double.PositiveInfinity
      )
    )
    ChanceNodeResult(
      value = result.value,
      lossTainted = result.lossTainted,
      complete = !result.aborted && result.bound == TTBound.Exact,
      pruned = !result.aborted && result.bound != TTBound.Exact,
      rollsProcessed = result.rollsProcessed,
      probePruned = result.probePruned,
      fromTT = result.fromTT
    )

  /** Recursive chance node used by depth 3.
    *
    * [[SearchContext.pliesRemaining]] counts decision plies below this chance node. The outer invocation has two
    * (opponent MIN, our MAX); recursive invocations have one. A role salt separates MAX continuations from MIN
    * continuations in the TT: the same board at the same nominal depth has the opposite value perspective in those two
    * roles.
    */
  private def recursiveChanceNode(toMove: GameState, ctx: SearchContext): RecursiveNodeResult =
    val key = transpositionKey(toMove, ctx.maximizing)
    val hit = ttRecursiveHit(key, ctx)
    if hit.isDefined then hit.get
    else
      val result = computeRecursiveChanceNode(toMove, ctx)
      if !result.aborted then storeRecursiveNode(key, ctx.pliesRemaining, result)
      result

  /** A stored value that already answers this node: an exact entry at the same depth, or a fail-soft bound that the
    * node's own window has already been driven past.
    *
    * One closure, not a chain of them: this runs once per reply of every decision node in the tree.
    */
  private def ttRecursiveHit(key: Long, ctx: SearchContext): Option[RecursiveNodeResult] =
    tt.flatMap: table =>
      table
        .probe(key)
        .flatMap: entry =>
          if entry.depth != ctx.pliesRemaining then None
          else if entry.bound == TTBound.Exact || reusableBound(entry, ctx) then
            Some(RecursiveNodeResult(entry.value, entry.bound, entry.lossTainted, fromTT = true))
          else None

  /** Whether a stored non-exact bound already settles this node's window. [[ExpectimaxConfig.exactOnlyMode]] refuses
    * every fail-soft bound, so it short-circuits before the entry is even examined.
    */
  private def reusableBound(entry: TTEntry, ctx: SearchContext): Boolean =
    !config.exactOnlyMode && boundSettlesWindow(entry, ctx)

  /** Whether the stored bound points the right way for this node's window and has been driven past it. */
  private def boundSettlesWindow(entry: TTEntry, ctx: SearchContext): Boolean =
    entry.bound match
      case TTBound.UpperBound => entry.value < ctx.alpha
      case TTBound.LowerBound => entry.value > ctx.beta
      case TTBound.Exact      => false

  private def storeRecursiveNode(key: Long, pliesRemaining: Int, result: RecursiveNodeResult): Unit =
    tt.foreach: table =>
      if result.bound == TTBound.Exact || !config.exactOnlyMode then
        table.store(key, result.value, result.bound, pliesRemaining, result.lossTainted)

  /** Expands the node for real: Star2 probing (only when this node's own side of the window is finite), the probe-sum
    * cutoff, then the roll loop.
    */
  private def computeRecursiveChanceNode(toMove: GameState, ctx: SearchContext): RecursiveNodeResult =
    val scratch = new RecursiveNodeScratch(ctx)
    if scratch.useStar2 then probeStar2Rolls(scratch, toMove, ctx)
    if scratch.aborted then RecursiveNodeResult.aborted
    else
      scratch.accumulateStar2Bounds()
      val star2Cut = star2Cutoff(scratch, ctx)
      if star2Cut.isDefined then star2Cut.get
      else
        expandRecursiveRolls(scratch, toMove, ctx)
        if scratch.result.isDefined then scratch.result.get
        else
          RecursiveNodeResult(scratch.acc, TTBound.Exact, scratch.lossTainted, rollsProcessed = scratch.rollsProcessed)

  /** Star2 probing phase: bound every roll by searching only its material-best reply. */
  private def probeStar2Rolls(scratch: RecursiveNodeScratch, toMove: GameState, ctx: SearchContext): Unit =
    if ctx.pliesRemaining == 1 then probeLeafRolls(scratch, toMove, ctx)
    else probeInnerRolls(scratch, toMove, ctx)

  /** Probing one ply above the leaves: every probe is a single position, so all of them are scored in one batch. */
  private def probeLeafRolls(scratch: RecursiveNodeScratch, toMove: GameState, ctx: SearchContext): Unit =
    val rolls        = DiceRolls.byWeightDescending
    val checkClock   = timed(ctx.deadlineNanos)
    val probeStates  = List.newBuilder[GameState]
    val stateIndices = Array.fill(rolls.length)(Star2Probes.LostRoll)
    var stateCount   = 0
    var i            = 0
    while i < rolls.length && !scratch.aborted do
      if clockExpired(checkClock, ctx.deadlineNanos) then scratch.aborted = true
      else
        val rolled  = toMove.withDicePool(rolls(i)._1)
        val replies = TurnGenerator.generateAllLegalTurnPaths(rolled)
        scratch.replies(i) = replies
        terminalDecisionValue(rolled, replies, ctx.maximizing) match
          case Some(value) => scratch.probeNodes(i) = Some(RecursiveNodeResult.exact(value, !ctx.maximizing))
          case None        =>
            probeStates += probeLeafState(scratch, i, toMove, rolled, replies, ctx)
            stateIndices(i) = stateCount
            stateCount += 1
        i += 1
    if !scratch.aborted then scoreLeafProbes(scratch, probeStates.result().toArray, stateIndices, ctx.myColor)

  /** The single state that bounds one roll, recording the probing path so the decision node can reuse the probe's exact
    * value instead of searching that reply a second time.
    */
  private def probeLeafState(
      scratch: RecursiveNodeScratch,
      index: Int,
      toMove: GameState,
      rolled: GameState,
      replies: List[List[Move]],
      ctx: SearchContext
  ): GameState =
    if replies.isEmpty then toMove.endTurn()
    else
      val path = selectProbePath(rolled, replies, ctx.myColor, ctx.maximizing)
      scratch.probePaths(index) = Some(path)
      applyTurn(rolled, path)

  private def scoreLeafProbes(
      scratch: RecursiveNodeScratch,
      rawProbeStates: Array[GameState],
      stateIndices: Array[Int],
      myColor: Color
  ): Unit =
    if rawProbeStates.nonEmpty then
      val scores = probeScoreMap(rawProbeStates, myColor)
      var k      = 0
      while k < stateIndices.length do
        val idx = stateIndices(k)
        if idx >= 0 then
          scratch.probeNodes(k) = Some(RecursiveNodeResult.exact(scores(leafKey(rawProbeStates(idx))).toDouble))
        k += 1

  /** Probing deeper in the tree: each probe is itself a chance node, searched with an open window. */
  private def probeInnerRolls(scratch: RecursiveNodeScratch, toMove: GameState, ctx: SearchContext): Unit =
    val rolls      = DiceRolls.byWeightDescending
    val checkClock = timed(ctx.deadlineNanos)
    var i          = 0
    while i < rolls.length && !scratch.aborted do
      if clockExpired(checkClock, ctx.deadlineNanos) then scratch.aborted = true
      else
        val rolled  = toMove.withDicePool(rolls(i)._1)
        val replies = TurnGenerator.generateAllLegalTurnPaths(rolled)
        scratch.replies(i) = replies
        terminalDecisionValue(rolled, replies, ctx.maximizing) match
          case Some(value) => scratch.probeNodes(i) = Some(RecursiveNodeResult.exact(value, !ctx.maximizing))
          case None        => probeInnerRoll(scratch, i, toMove, rolled, replies, ctx)
        i += 1

  /** Probes one non-terminal roll with an open window. A bound pointing the wrong way for this node's role cannot bound
    * the Star2 sum, so it degrades to the role's own worst case instead.
    */
  private def probeInnerRoll(
      scratch: RecursiveNodeScratch,
      index: Int,
      toMove: GameState,
      rolled: GameState,
      replies: List[List[Move]],
      ctx: SearchContext
  ): Unit =
    val (afterTurn, path) =
      if replies.isEmpty then toMove.endTurn() -> None
      else
        val selected = selectProbePath(rolled, replies, ctx.myColor, ctx.maximizing)
        applyTurn(rolled, selected) -> Some(selected)
    scratch.probePaths(index) = path
    val probed = recursiveChanceNode(afterTurn, ctx.childOpen)
    if probed.aborted then scratch.aborted = true
    else if probeBoundIsSound(probed.bound, ctx.maximizing) then scratch.probeNodes(index) = Some(probed)
    else scratch.probeNodes(index) = Some(unusableProbeBound(ctx.maximizing))

  /** The role's own worst case, standing in for a probe whose bound cannot bound the Star2 sum. */
  private def unusableProbeBound(maximizing: Boolean): RecursiveNodeResult =
    if maximizing then RecursiveNodeResult(LossValue, TTBound.LowerBound, lossTainted = false)
    else RecursiveNodeResult(WinValue, TTBound.UpperBound, lossTainted = false)

  /** The whole node settled by its Star2 probe sum, before a single roll is expanded. */
  private def star2Cutoff(scratch: RecursiveNodeScratch, ctx: SearchContext): Option[RecursiveNodeResult] =
    if !scratch.useStar2 then None
    else
      val star2Value = scratch.star2Sum
      if !ctx.maximizing && star2Value < ctx.alpha then Some(probeCut(scratch, star2Value, TTBound.UpperBound))
      else if ctx.maximizing && star2Value > ctx.beta then Some(probeCut(scratch, star2Value, TTBound.LowerBound))
      else None

  private def probeCut(scratch: RecursiveNodeScratch, value: Double, bound: TTBound): RecursiveNodeResult =
    RecursiveNodeResult(value, bound, scratch.probeLossTainted, rollsProcessed = 0, probePruned = true)

  /** The node's roll loop: fold each roll's exact value into the expectation, stopping at the first window cutoff, the
    * first aborted child, or the deadline.
    */
  private def expandRecursiveRolls(scratch: RecursiveNodeScratch, toMove: GameState, ctx: SearchContext): Unit =
    val rolls      = DiceRolls.byWeightDescending
    val checkClock = timed(ctx.deadlineNanos)
    while scratch.rollsProcessed < rolls.length && scratch.result.isEmpty do
      val index = scratch.rollsProcessed
      windowCut(scratch, ctx, index) match
        case Some(cut)                                           => scratch.result = Some(cut)
        case None if clockExpired(checkClock, ctx.deadlineNanos) => scratch.result = Some(RecursiveNodeResult.aborted)
        case None                                                => expandOneRecursiveRoll(scratch, toMove, ctx, index)

  /** The Star1 window cutoff for the roll at `index`, taken before any of that roll's work is paid for. */
  private def windowCut(scratch: RecursiveNodeScratch, ctx: SearchContext, index: Int): Option[RecursiveNodeResult] =
    val upperBound = scratch.acc + scratch.remainingUpper(index)
    val lowerBound = scratch.acc + scratch.remainingLower(index)
    if upperBound < ctx.alpha then Some(scratch.boundResult(upperBound, TTBound.UpperBound))
    else if lowerBound > ctx.beta then Some(scratch.boundResult(lowerBound, TTBound.LowerBound))
    else None

  /** Searches one roll's decision node inside the window that roll's own probability implies.
    *
    * The roll's index and probability go into `scratch` rather than into a per-roll value object: this is the hottest
    * loop in the depth-3 tree (56 rolls under every one of hundreds of replies), and one object per roll measured as a
    * real JMH regression against one object per node.
    */
  private def expandOneRecursiveRoll(
      scratch: RecursiveNodeScratch,
      toMove: GameState,
      ctx: SearchContext,
      index: Int
  ): Unit =
    val (roll, weight) = DiceRolls.byWeightDescending(index)
    val rolled         = toMove.withDicePool(roll)
    val replies        = repliesFor(scratch.replies, index, rolled)
    scratch.rollProbability = weight.toDouble / DiceRolls.totalOrderedRolls
    // A decision node at the leaf ply never reads its window — terminal shortcut, forced pass and leaf batch all
    // ignore it, and `searchDecisionNode` is unreachable there — so the transform and its context are skipped on the
    // tree's hottest edge (56 rolls under every reply of every node above).
    val childCtx =
      if ctx.pliesRemaining == 1 then ctx
      else ctx.withWindow(childAlpha(scratch, ctx), childBeta(scratch, ctx))
    val child = recursiveDecisionNode(
      rolled,
      replies,
      childCtx,
      scratch.probePathAt(index),
      scratch.probeNodeAt(index)
    )
    if child.aborted then scratch.result = Some(child)
    else
      scratch.lossTainted ||= child.lossTainted
      foldRecursiveChild(scratch, ctx, rolled, replies, child)

  /** The alpha the child must beat for the current roll to still matter to the parent's window, mapped through that
    * roll's own probability. [[clampScore]] keeps the transformed window inside the range Star bounds are stated in.
    */
  private def childAlpha(scratch: RecursiveNodeScratch, ctx: SearchContext): Double =
    if ctx.alpha == Double.NegativeInfinity then ctx.alpha
    else clampScore((ctx.alpha - scratch.acc - scratch.remainingAfterCurrentUpper) / scratch.rollProbability)

  /** The beta counterpart of [[childAlpha]]. */
  private def childBeta(scratch: RecursiveNodeScratch, ctx: SearchContext): Double =
    if ctx.beta == Double.PositiveInfinity then ctx.beta
    else clampScore((ctx.beta - scratch.acc - scratch.remainingAfterCurrentLower) / scratch.rollProbability)

  /** Folds the current roll's child result into the node: a bound that already settles the parent's window ends the
    * loop; anything else must reach an exact value before it can join the expectation.
    */
  private def foldRecursiveChild(
      scratch: RecursiveNodeScratch,
      ctx: SearchContext,
      rolled: GameState,
      replies: List[List[Move]],
      child: RecursiveNodeResult
  ): Unit =
    val combined      = scratch.acc + scratch.rollProbability * child.value
    val combinedUpper = combined + scratch.remainingAfterCurrentUpper
    val combinedLower = combined + scratch.remainingAfterCurrentLower
    if child.bound == TTBound.UpperBound && combinedUpper < ctx.alpha then
      scratch.result = Some(scratch.boundResult(combinedUpper, TTBound.UpperBound))
    else if child.bound == TTBound.LowerBound && combinedLower > ctx.beta then
      scratch.result = Some(scratch.boundResult(combinedLower, TTBound.LowerBound))
    else accumulateExactChild(scratch, ctx, rolled, replies, child)

  /** Re-searches a still-bounded child with an open window, then folds its exact value into the expectation.
    *
    * At the depths [[ExpectimaxConfig]] allows, the re-search is unreachable from a node that ran no probing phase, so
    * [[RecursiveNodeScratch.probePathAt]]'s guard is dead code here — see its Scaladoc for why, and why it is written
    * anyway.
    */
  private def accumulateExactChild(
      scratch: RecursiveNodeScratch,
      ctx: SearchContext,
      rolled: GameState,
      replies: List[List[Move]],
      child: RecursiveNodeResult
  ): Unit =
    val index      = scratch.rollsProcessed
    val exactChild =
      if child.bound == TTBound.Exact then child
      else
        recursiveDecisionNode(
          rolled,
          replies,
          ctx.openWindow,
          scratch.probePathAt(index),
          scratch.probeNodeAt(index)
        )
    if exactChild.aborted then scratch.result = Some(exactChild)
    else
      scratch.acc += scratch.rollProbability * exactChild.value
      scratch.lossTainted ||= exactChild.lossTainted
      scratch.rollsProcessed += 1

  /** One roll's decision node: the terminal shortcut, the forced pass, the leaf layer, or a full reply search. */
  private def recursiveDecisionNode(
      rolled: GameState,
      replies: List[List[Move]],
      ctx: SearchContext,
      probePath: Option[List[Move]],
      probeNode: Option[RecursiveNodeResult]
  ): RecursiveNodeResult =
    terminalDecisionValue(rolled, replies, ctx.maximizing) match
      case Some(value)                     => RecursiveNodeResult.exact(value, lossTainted = !ctx.maximizing)
      case None if replies.isEmpty         => passedDecisionValue(rolled, ctx)
      case None if ctx.pliesRemaining == 1 => leafDecisionValue(rolled, replies, ctx.myColor, ctx.maximizing)
      case None                            => searchDecisionNode(rolled, replies, ctx, probePath, probeNode)

  /** No legal turn for this roll, so the side to move passes and the value comes from the layer below. */
  private def passedDecisionValue(rolled: GameState, ctx: SearchContext): RecursiveNodeResult =
    if ctx.pliesRemaining == 1 then RecursiveNodeResult.exact(evalOne(rolled.endTurn(), ctx.myColor))
    else recursiveChanceNode(rolled.endTurn(), ctx.child)

  /** The full decision node: replies in material order, each searched under the node's tightening window, folded
    * fail-soft into an exact best or a bound.
    */
  private def searchDecisionNode(
      rolled: GameState,
      replies: List[List[Move]],
      ctx: SearchContext,
      probePath: Option[List[Move]],
      probeNode: Option[RecursiveNodeResult]
  ): RecursiveNodeResult =
    val ordered = orderedReplies(rolled, replies, ctx)
    val node    = new DecisionNodeScratch(ctx)
    while ordered.hasNext && node.result.isEmpty do
      val (path, afterTurn, _) = ordered.next()
      val probe                = reusableProbe(probePath, probeNode, path)
      val child                =
        if probe.isDefined then probe.get
        else recursiveChanceNode(afterTurn, ctx.childWithWindow(node.localAlpha, node.localBeta))
      foldDecisionChild(node, ctx, child)
    decisionNodeResult(node, ctx)

  /** The replies ordered best-first for this node's role by the same material proxy the probing phase uses. Returned as
    * an iterator so the loop stays linear — indexing a `List` once per reply would make the node quadratic.
    */
  private def orderedReplies(
      rolled: GameState,
      replies: List[List[Move]],
      ctx: SearchContext
  ): Iterator[(List[Move], GameState, Int)] =
    replies
      .map: path =>
        val state = applyTurn(rolled, path)
        val score = Evaluator.evaluateMaterial(state, ctx.myColor)
        (path, state, if ctx.maximizing then -score else score)
      .sortBy(_._3)
      .iterator

  /** The Star2 probe already searched this exact reply to an exact value: reuse it rather than search it twice. */
  private def reusableProbe(
      probePath: Option[List[Move]],
      probeNode: Option[RecursiveNodeResult],
      path: List[Move]
  ): Option[RecursiveNodeResult] =
    if probePath.contains(path) && probeNode.exists(node => !node.aborted && node.bound == TTBound.Exact) then probeNode
    else None

  private def foldDecisionChild(node: DecisionNodeScratch, ctx: SearchContext, child: RecursiveNodeResult): Unit =
    if child.aborted then node.result = Some(child)
    else
      node.lossTainted ||= child.lossTainted
      child.bound match
        case TTBound.Exact      => foldExactChild(node, ctx, child)
        case TTBound.UpperBound => foldUpperBoundChild(node, ctx, child)
        case TTBound.LowerBound => foldLowerBoundChild(node, ctx, child)

  /** An exact child advances this node's own best value and its window; crossing the caller's bound ends the node. */
  private def foldExactChild(node: DecisionNodeScratch, ctx: SearchContext, child: RecursiveNodeResult): Unit =
    node.hasExact = true
    if ctx.maximizing then
      node.exactBest = math.max(node.exactBest, child.value)
      node.localAlpha = math.max(node.localAlpha, node.exactBest)
      if node.exactBest > ctx.beta then node.cutoff(node.exactBest, TTBound.LowerBound)
    else
      node.exactBest = math.min(node.exactBest, child.value)
      node.localBeta = math.min(node.localBeta, node.exactBest)
      if node.exactBest < ctx.alpha then node.cutoff(node.exactBest, TTBound.UpperBound)

  /** An upper bound is a usable value only for the maximizing role; for the minimizing role it settles the node. */
  private def foldUpperBoundChild(node: DecisionNodeScratch, ctx: SearchContext, child: RecursiveNodeResult): Unit =
    if ctx.maximizing then node.boundBest = math.max(node.boundBest, child.value)
    else node.cutoff(child.value, TTBound.UpperBound)

  /** A lower bound is a usable value only for the minimizing role; for the maximizing role it settles the node. */
  private def foldLowerBoundChild(node: DecisionNodeScratch, ctx: SearchContext, child: RecursiveNodeResult): Unit =
    if ctx.maximizing then node.cutoff(child.value, TTBound.LowerBound)
    else node.boundBest = math.min(node.boundBest, child.value)

  private def decisionNodeResult(node: DecisionNodeScratch, ctx: SearchContext): RecursiveNodeResult =
    if node.result.isDefined then node.result.get
    else if node.hasExact then RecursiveNodeResult.exact(node.exactBest, node.lossTainted)
    else if ctx.maximizing then RecursiveNodeResult(node.boundBest, TTBound.UpperBound, node.lossTainted)
    else RecursiveNodeResult(node.boundBest, TTBound.LowerBound, node.lossTainted)

  /** Mutable state of one depth-3 recursive chance node: the Star2 probe metadata (allocated only when this node's own
    * side of the window is finite), the weighted accumulator of the roll loop, and the bound or abort that ended it.
    */
  final private class RecursiveNodeScratch(ctx: SearchContext):
    private val totalRolls = DiceRolls.byWeightDescending.length
    private val maximizing = ctx.maximizing

    /** Whether a Star2 probing phase runs here at all, and therefore whether the probe arrays are real. */
    val useStar2: Boolean = ctx.boundedForRole

    val replies: Array[List[List[Move]]] = new Array[List[List[Move]]](totalRolls)

    val probePaths: Array[Option[List[Move]]] =
      if useStar2 then Array.fill[Option[List[Move]]](totalRolls)(None) else EmptyProbePaths

    val probeNodes: Array[Option[RecursiveNodeResult]] =
      if useStar2 then Array.fill[Option[RecursiveNodeResult]](totalRolls)(None) else EmptyProbeNodes

    /** One roll's probing path, or `None` when this node ran no probing phase.
      *
      * The guard is what makes the accessor worth having: without a probing phase [[probePaths]] is the shared empty
      * array, so an unguarded read at any index throws. Both readers go through here so they cannot drift apart — the
      * per-roll search and the open-window re-search used to disagree about the guard, and only one of them was right.
      *
      * At `searchDepth` 2 or 3 — all [[ExpectimaxConfig]] admits — the re-search cannot actually reach a node with no
      * probing phase, so that reader's guard is dead code today. A one-ply chance node never re-searches at all: its
      * decision nodes are terminal, forced passes or leaf batches, every one of which returns an exact value. A two-ply
      * chance node is always the MIN root of [[depthThreeChanceNodeValue]], whose beta is `+∞`, so [[useStar2]] can
      * only be false when its alpha is `-∞` too — a fully open window, and an open window yields exact values all the
      * way down (its first reply is searched with the same open window, which sets `hasExact`, and no cutoff can fire
      * against an infinite bound). The guard is here so that raising the depth cap or rearranging the window plumbing
      * cannot turn that argument into an `ArrayIndexOutOfBoundsException`.
      */
    def probePathAt(index: Int): Option[List[Move]] = if useStar2 then probePaths(index) else None

    /** [[probePathAt]] for the probed child's result. */
    def probeNodeAt(index: Int): Option[RecursiveNodeResult] = if useStar2 then probeNodes(index) else None

    private val star2Bound: Array[Double] =
      if useStar2 then new Array[Double](totalRolls + 1) else EmptyStar2Bounds

    var aborted     = false
    var lossTainted = false
    var acc         = 0.0

    /** Doubles as the index of the next roll: the loop advances it only once a roll's exact value has been folded into
      * [[acc]], so the count and the index can never diverge.
      */
    var rollsProcessed = 0

    /** Probability of the roll currently being expanded, set once per iteration of the roll loop. */
    var rollProbability = 0.0

    var result: Option[RecursiveNodeResult] = None

    /** Accumulates the probe values into suffix expectations — the bound the roll loop uses at every boundary. A no-op
      * without a probing phase, which is also when [[star2Bound]] is the shared empty array.
      */
    def accumulateStar2Bounds(): Unit =
      if useStar2 then
        val rolls = DiceRolls.byWeightDescending
        var i     = totalRolls - 1
        while i >= 0 do
          val probability = rolls(i)._2.toDouble / DiceRolls.totalOrderedRolls
          star2Bound(i) = star2Bound(i + 1) + probability * probeNodes(i).get.value
          i -= 1

    /** The probe-bounded expectation of the whole node. Only meaningful when [[useStar2]]. */
    def star2Sum: Double = star2Bound(0)

    /** The most the unprocessed rolls from `from` on could still contribute. */
    def remainingUpper(from: Int): Double =
      if useStar2 && !maximizing then star2Bound(from) else RemainingProbabilityByRollIndex(from) * WinValue

    /** The least the unprocessed rolls from `from` on could still contribute. */
    def remainingLower(from: Int): Double =
      if useStar2 && maximizing then star2Bound(from) else RemainingProbabilityByRollIndex(from) * LossValue

    /** [[remainingUpper]] for everything strictly after the roll currently being expanded. */
    def remainingAfterCurrentUpper: Double = remainingUpper(rollsProcessed + 1)

    /** [[remainingLower]] for everything strictly after the roll currently being expanded. */
    def remainingAfterCurrentLower: Double = remainingLower(rollsProcessed + 1)

    def boundResult(value: Double, bound: TTBound): RecursiveNodeResult =
      RecursiveNodeResult(value, bound, lossTainted, rollsProcessed = rollsProcessed)

    def probeLossTainted: Boolean = probeNodes.iterator.flatten.exists(_.lossTainted)

  /** Mutable fail-soft state of one decision node: the window as it tightens, the best exact and best bounded child
    * seen so far, and the cutoff that ended the reply loop.
    */
  final private class DecisionNodeScratch(ctx: SearchContext):
    var localAlpha  = ctx.alpha
    var localBeta   = ctx.beta
    var exactBest   = if ctx.maximizing then LossValue else WinValue
    var boundBest   = if ctx.maximizing then LossValue else WinValue
    var hasExact    = false
    var lossTainted = false

    var result: Option[RecursiveNodeResult] = None

    def cutoff(value: Double, bound: TTBound): Unit =
      result = Some(RecursiveNodeResult(value, bound, lossTainted))

  private def leafDecisionValue(
      rolled: GameState,
      replies: List[List[Move]],
      myColor: Color,
      maximizing: Boolean
  ): RecursiveNodeResult =
    val leaves = distinctLeaves(replies.iterator.map(reply => applyTurn(rolled, reply)).toArray)
    val scores = evalBatch(leaves, myColor)
    var value  = if maximizing then Int.MinValue else Int.MaxValue
    var i      = 0
    while i < scores.length do
      if maximizing then value = math.max(value, scores(i)) else value = math.min(value, scores(i))
      i += 1
    RecursiveNodeResult.exact(value.toDouble)

  private def terminalDecisionValue(
      rolled: GameState,
      replies: List[List[Move]],
      maximizing: Boolean
  ): Option[Double] =
    if replies.exists(reply => capturesEnemyKing(rolled, reply)) then Some(if maximizing then WinValue else LossValue)
    else None

  private def selectProbePath(
      rolled: GameState,
      replies: List[List[Move]],
      myColor: Color,
      maximizing: Boolean
  ): List[Move] =
    if maximizing then replies.maxBy(path => Evaluator.evaluateMaterial(applyTurn(rolled, path), myColor))
    else replies.minBy(path => Evaluator.evaluateMaterial(applyTurn(rolled, path), myColor))

  private def probeBoundIsSound(bound: TTBound, maximizing: Boolean): Boolean =
    bound == TTBound.Exact || (if maximizing then bound == TTBound.LowerBound else bound == TTBound.UpperBound)

  private def clampScore(value: Double): Double = math.max(LossValue, math.min(WinValue, value))

  private def transpositionKey(state: GameState, maximizing: Boolean): Long =
    if maximizing then state.zobristHash ^ MaxChanceKeySalt else state.zobristHash

  /** The opponent picks the reply that is worst for us. A reply capturing our king is worst of all ([[LossValue]]);
    * otherwise the resulting leaves are scored in one batch and the minimum is taken.
    *
    * Leaves are **deduplicated by position** before scoring. Dice Chess turns are 1–3 micro-moves, and reordering
    * independent micro-moves reaches the same board — measured at ~78% duplicate leaves per chance node, i.e. only
    * about a fifth of the generated replies are distinct positions. Since the value taken here is a minimum, and the
    * minimum over a multiset equals the minimum over its distinct elements, dropping duplicates is exact: the returned
    * value is unchanged, only the evaluator does less work. This matters because the evaluator is the search's dominant
    * cost (a trained model behind a JNI call), while the deduplication is a hash per leaf.
    */
  private def opponentMinValue(rolled: GameState, replies: List[List[Move]], myColor: Color): Double =
    if replies.exists(reply => capturesEnemyKing(rolled, reply)) then LossValue
    else
      val leaves = distinctLeaves(replies.iterator.map(reply => applyTurn(rolled, reply)).toArray)
      val scores = evalBatch(leaves, myColor)
      var min    = Int.MaxValue
      var i      = 0
      while i < scores.length do
        if scores(i) < min then min = scores(i)
        i += 1
      min.toDouble

  /** The distinct positions among `leaves`, in first-seen order.
    *
    * **Mutates `leaves`**, compacting the distinct entries into its front. That is safe only because the caller builds
    * the array immediately before the call and never looks at it again, and it is worth the sharper contract on a path
    * that runs once per dice roll per candidate: the obvious version — write into a second full-length array, then
    * slice — allocates an extra array the size of the whole reply list every time, and with ~78% duplicates that array
    * is discarded almost immediately. Compaction is safe in place because the write index never overtakes the read
    * index (`count <= i` throughout), so no unread element is ever overwritten.
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

final private case class ChanceNodeResult(
    value: Double,
    lossTainted: Boolean,
    complete: Boolean,
    pruned: Boolean,
    rollsProcessed: Int,
    probePruned: Boolean = false,
    // True when the result came from the transposition table rather than an expansion. The caller counts such
    // candidates in ttHits/ttCutoffs, never in candidatesCompleted/cutoffs: a TT-resolved candidate did zero
    // chance-node work, and folding it into the expansion counters would corrupt the effective-width telemetry
    // that RootSearchStats exists to measure (#494).
    fromTT: Boolean = false
)

final private case class RecursiveNodeResult(
    value: Double,
    bound: TTBound,
    lossTainted: Boolean,
    aborted: Boolean = false,
    rollsProcessed: Int = 0,
    probePruned: Boolean = false,
    fromTT: Boolean = false
)

private object RecursiveNodeResult:
  def exact(value: Double, lossTainted: Boolean = false): RecursiveNodeResult =
    RecursiveNodeResult(value, TTBound.Exact, lossTainted)

  val aborted: RecursiveNodeResult =
    RecursiveNodeResult(0.0, TTBound.Exact, lossTainted = false, aborted = true)

/** Everything a depth-3 node needs about *where* it sits — role, remaining plies, clock and window — carried as one
  * value so descending the tree is a single explicit transformation rather than ten positional arguments.
  *
  * The combinators are the only ways the tree moves: [[child]] and [[childWithWindow]] go one ply down with the roles
  * swapped, [[childOpen]] does the same for a Star2 probe, and [[withWindow]]/[[openWindow]] re-enter the same node
  * under a different window. A node that reuses its own context down the recursion allocates nothing.
  *
  * @param pliesRemaining
  *   decision plies still below the current chance node
  * @param maximizing
  *   whether the side to move at the node below is us (MAX) or the opponent (MIN)
  */
final private case class SearchContext(
    myColor: Color,
    pliesRemaining: Int,
    maximizing: Boolean,
    deadlineNanos: Long,
    alpha: Double,
    beta: Double
):
  /** The same node under a different window — the per-roll windows of a chance node, and its re-searches. */
  def withWindow(newAlpha: Double, newBeta: Double): SearchContext = copy(alpha = newAlpha, beta = newBeta)

  /** The same node with no window, so its value comes back exact rather than as a fail-soft bound. */
  def openWindow: SearchContext = withWindow(Double.NegativeInfinity, Double.PositiveInfinity)

  /** One ply down with the roles swapped, carrying this node's window unchanged. */
  def child: SearchContext = copy(pliesRemaining = pliesRemaining - 1, maximizing = !maximizing)

  /** One ply down with the roles swapped and the window a decision node has tightened. */
  def childWithWindow(newAlpha: Double, newBeta: Double): SearchContext =
    copy(pliesRemaining = pliesRemaining - 1, maximizing = !maximizing, alpha = newAlpha, beta = newBeta)

  /** One ply down with the roles swapped and no window — what a Star2 probe searches with. */
  def childOpen: SearchContext =
    copy(
      pliesRemaining = pliesRemaining - 1,
      maximizing = !maximizing,
      alpha = Double.NegativeInfinity,
      beta = Double.PositiveInfinity
    )

  /** Whether this node's own side of the window is finite, which is what makes a Star2 probing phase worthwhile: a MAX
    * node can only be cut from above, a MIN node only from below.
    */
  def boundedForRole: Boolean =
    if maximizing then beta < Double.PositiveInfinity else alpha > Double.NegativeInfinity

/** The probe leaves of one Star2 phase: one state per roll that has one, in first-seen order, plus the index into
  * `states` for every roll — [[Star2Probes.LostRoll]] for a roll that loses our king outright and therefore needs no
  * evaluation at all.
  */
final private class Star2Probes(val states: Array[GameState], val rollIndices: Array[Int])

private object Star2Probes:
  /** Sentinel `rollIndices` entry: this roll has no probe leaf because some reply to it captures our king. */
  val LostRoll: Int = -1

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

  /** Suffix probability mass for [[DiceRolls.byWeightDescending]], indexed by the first unprocessed roll. */
  private val RemainingProbabilityByRollIndex: Array[Double] =
    val rolls  = DiceRolls.byWeightDescending
    val suffix = new Array[Double](rolls.length + 1)
    var i      = rolls.length - 1
    while i >= 0 do
      suffix(i) = suffix(i + 1) + rolls(i)._2.toDouble / DiceRolls.totalOrderedRolls
      i -= 1
    suffix

  /** Shared empty probe storage keeps chance nodes with an infinite window allocation-free for Star2 metadata. */
  private val EmptyProbePaths: Array[Option[List[Move]]]          = Array.empty
  private val EmptyProbeNodes: Array[Option[RecursiveNodeResult]] = Array.empty
  private val EmptyStar2Bounds: Array[Double]                     = Array.empty

  /** Sentinel deadline for the un-timed entry points: `System.nanoTime()` never reaches it in practice. */
  private val NoDeadline: Long = Long.MaxValue

  /** Whether a real deadline was supplied. Guarding every clock read with this keeps the untimed path free of
    * `System.nanoTime()` syscalls entirely — the per-roll check added for #496 must not tax the arena, whose
    * reproducibility and benchmarks both live on that path.
    */
  private inline def timed(deadlineNanos: Long): Boolean = deadlineNanos != NoDeadline

  /** Whether the deadline has already passed. `checkClock` is [[timed]] hoisted out of a roll loop, so the untimed path
    * never reaches `System.nanoTime()` at all.
    */
  private inline def clockExpired(checkClock: Boolean, deadlineNanos: Long): Boolean =
    checkClock && System.nanoTime() >= deadlineNanos

  /** [[clockExpired]] for a one-off check with no loop to hoist [[timed]] out of. */
  private inline def expired(deadlineNanos: Long): Boolean = clockExpired(timed(deadlineNanos), deadlineNanos)

  /** Default stats sink: discard. `private[search]` so JVM-only wiring (e.g. [[OnnxExpectimaxSearch]]) can name the
    * same default instead of re-inventing its own no-op.
    */
  private[search] val NoStats: RootSearchStats => Unit = _ => ()

  /** Default root pre-ranker: material, applied per state — the search's historical, hardcoded behaviour, now just
    * expressed as a batch so it fits the same injectable shape as any other pre-ranker. `private[search]` (not fully
    * private) so JVM-only wiring in this package (e.g. [[OnnxExpectimaxSearch]]) can fall back to it explicitly.
    */
  private[search] def materialBatch(states: Array[GameState], color: Color): Array[Int] =
    states.map(Evaluator.evaluateMaterial(_, color))
