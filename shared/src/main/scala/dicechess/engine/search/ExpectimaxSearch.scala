package dicechess.engine.search

import dicechess.engine.domain.*

import scala.util.Random

/** Tuning for [[ExpectimaxSearch]].
  *
  * @param candidateLimit
  *   how many of the mover's own turns (pre-ranked by [[ExpectimaxSearch]]'s `preRank`, material by default) are
  *   expanded to full depth. Dice Chess routinely offers hundreds of legal turns per roll, so expanding all of them
  *   through a chance node is infeasible; this bounds the branching at the decision node. Must be positive.
  */
final case class ExpectimaxConfig(candidateLimit: Int = 8):
  require(candidateLimit > 0, s"candidateLimit must be positive, got $candidateLimit")

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
  * (see `findBestMove`'s loss-taint tracking).
  *
  * @param evalBatch
  *   the rescoring evaluator, same batching contract as the search's own `evalBatch`
  * @param weight
  *   blend weight; must be in `(0, 1]` (0 would be indistinguishable from omitting rescoring entirely, so `None` is the
  *   only way to express "disabled")
  */
final case class RootRescore(evalBatch: (Array[GameState], Color) => Array[Int], weight: Double):
  require(weight > 0.0 && weight <= 1.0, s"weight must be in (0, 1], got $weight")

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
    probeCutoffs: Int = 0
):
  /** Whether the deadline cut the loop short of the selected candidate set. */
  def deadlineTruncated: Boolean = candidatesCompleted + cutoffs < candidatesSelected

  /** The deadline elapsed before a single candidate could be scored, so the turn came from the pre-ranker alone — the
    * search contributed nothing beyond candidate selection.
    */
  def fellBackToPreRank: Boolean = candidatesCompleted == 0 && candidatesAbandoned > 0

/** Two-ply expectimax search for Dice Chess: my turn, then the opponent's dice roll, then the opponent's best reply.
  *
  * Unlike a one-ply evaluator ([[GreedySearch]], [[OnnxEvalSearch]]), this looks one full turn ahead and so sees
  * tactical punishments — a capture that hangs a bigger piece to the recapture — that a static evaluation cannot. The
  * layer between the two plies is a **chance node**: the opponent's roll is unknown when we move, so the value of our
  * turn is the expectation over all [[DiceRolls]] outcomes of the opponent's best (for them) reply.
  *
  * The evaluation function is injected as a batch (`evalBatch(states, color)` scores every state from `color`'s
  * perspective) so the same search works with any leaf evaluator — the engine's material score, or an externally
  * trained model — and so the many leaves under one chance node can be scored in a single call. Leaf scores are only
  * ever compared, minimised, and averaged, so the search is agnostic to the evaluator's absolute scale.
  *
  * Two terminal cases sit outside the evaluator, because a king capture ends the game and material scores never see the
  * king:
  *   - if one of our own turns captures the opponent's king, we play it immediately (an outright win);
  *   - a leaf where the opponent captures our king is worth [[ExpectimaxSearch.LossValue]] — below any real score on
  *     any scale — so the opponent always takes it and we always rank that line last.
  *
  * Depth is fixed at two plies. As a [[TimeBudgetedSearch]] it also honours a wall-clock deadline, expanding pre-ranked
  * candidates until time runs out.
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
    statsSink: RootSearchStats => Unit = ExpectimaxSearch.NoStats
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
    val myColor = state.activeColor
    val paths   = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then
      // Forced pass: still one record per call — all-zero, distinguished from a win shortcut by legalTurns == 0.
      statsSink(RootSearchStats(0, 0, 0))
      None
    else
      // An immediate king capture wins now; never let pre-ranking prune it (the king has no material/model value).
      val winning = paths.filter(path => capturesEnemyKing(state, path))
      if winning.nonEmpty then
        statsSink(RootSearchStats(paths.size, 0, 0))
        Some(ScoredSequence(winning.minBy(_.size), SearchScoring.TerminalWinScore))
      else
        // Every remaining path is provably non-king-capturing (the filter above already removed those), so its own
        // resulting position — needed for both the pre-rank score and, for survivors, the chance node — is exactly
        // `applyTurn(state, path)`; computing it once here and reusing it below avoids replaying the same turn twice.
        // Array throughout (not List): the top-K expansion loop below indexes `candidates(i)`, which must stay O(1),
        // and the batched pipeline avoids intermediate linked-list-node allocations in this per-move hot path.
        val withResultState = paths.map(path => path -> applyTurn(state, path)).toArray
        val preRankScores   = preRank(withResultState.map(_._2), myColor)
        // Pre-rank in one batched call, expand only the top candidates through the (expensive) chance node. sortBy is
        // stable (like List's), so equal-scored candidates keep generation order — the material default stays identical.
        val ranked = withResultState
          .zip(preRankScores)
          .sortBy { case (_, score) => -score }
          .take(config.candidateLimit)
        val candidates = ranked.map(_._1)
        // Kept for the anytime fallback below: if the deadline elapses before any candidate finishes, this is both
        // the turn we play and the only score we can honestly attach to it.
        val topPreRankScore = ranked(0)._2
        // Each candidate's own resulting position (before the opponent's roll) is kept alongside its chance-node
        // value: the chance node needs it, and — when a root rescorer is configured — so does the rescore batch,
        // scored once over exactly these states rather than recomputed.
        val evaluated  = List.newBuilder[(List[Move], GameState, Double, Boolean)]
        var i          = 0
        var completed    = 0
        var abandoned    = 0
        var cutoffs      = 0
        var rollsSaved   = 0
        var probeCutoffs = 0
        var alpha        = Double.NegativeInfinity
        var continue     = true
        while i < candidates.length && continue do
          val (path, resultState) = candidates(i)
          val res                 = chanceNodeValue(resultState, myColor, deadlineNanos, alpha)
          // Only a fully expanded candidate is ranked. A truncated one carries the expectation of the rolls it
          // happened to reach, which is not comparable with a complete one — ranking it would let the arbitrary
          // point where the clock landed decide the move.
          if res.complete then
            evaluated += ((path, resultState, res.value, res.lossTainted))
            completed += 1
            if rootRescore.isEmpty then alpha = math.max(alpha, res.value)
          else if res.pruned then
            cutoffs += 1
            if res.probePruned then probeCutoffs += 1
            rollsSaved += (DiceRolls.byWeightDescending.length - res.rollsProcessed)
          else
            abandoned += 1
            continue = false // the deadline is already past; starting another candidate cannot finish either
          i += 1
          if continue && timed(deadlineNanos) && System.nanoTime() >= deadlineNanos then continue = false
        statsSink(
          RootSearchStats(paths.size, candidates.length, completed, abandoned, cutoffs, rollsSaved, probeCutoffs)
        )
        val results = evaluated.result()
        // The deadline elapsed inside the very first candidate, so nothing has a comparable value yet. The anytime
        // contract still owes a legal turn: play the pre-ranker's own top pick, scored as the pre-ranker scored it.
        if results.isEmpty then Some(ScoredSequence(candidates(0)._1, topPreRankScore))
        else
          val scores = rootRescore match
            case None                                   => results.map { case (path, _, value, _) => path -> value }
            case Some(RootRescore(rescoreEval, weight)) =>
              val states   = results.map(_._2).toArray
              val rescored = rescoreEval(states, myColor)
              results.zip(rescored).map { case ((path, _, value, lossTainted), rescoreValue) =>
                // A line where every roll loses our king outright must never be masked by a favorable rescore —
                // LossValue sits below any real evaluator scale precisely so it always ranks last (see RootRescore).
                val blended = if lossTainted then value else (1 - weight) * value + weight * rescoreValue
                path -> blended
              }
          val bestQ = scores.map(_._2).max
          val best  = scores.collect { case (path, q) if q == bestQ => path }
          Some(ScoredSequence(best(random.nextInt(best.length)), bestQ.toInt))

  /** The expectation, over the 56 weighted dice outcomes, of the opponent's best reply value (from `myColor`'s view),
    * alongside whether any single roll forced [[LossValue]] (the opponent capturing our king outright) — tracked
    * precisely per roll (an exact match against the sentinel, not a threshold on the weighted average) so
    * [[RootRescore]] can never rescue a line that is lost on even one roll, however small its weight.
    *
    * The third element says whether every weighted roll was processed before `deadlineNanos`. `false` means the
    * expansion was cut short, so the accumulated value is a partial expectation over an arbitrary prefix of the roll
    * order — the caller must discard it rather than rank it against complete ones (#496).
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
    val checkClock    = timed(deadlineNanos)
    val rolls         = DiceRolls.byWeightDescending
    val totalRolls    = rolls.length
    var cutByDeadline = false
    var cutByStar2    = false
    var cutByStar1    = false
    var lossTainted   = false

    val allReplies       = new Array[List[List[Move]]](totalRolls)
    val remainingCeiling = new Array[Double](totalRolls + 1)

    // Star2 Probing Phase: when an alpha bound exists, probe each roll with top-1 opponent reply by material
    if alpha > Double.NegativeInfinity then
      val probeStatesBuf   = List.newBuilder[GameState]
      val probeRollIndices = new Array[Int](totalRolls)
      var probeStateCount  = 0
      var i                = 0

      while i < totalRolls && !cutByDeadline do
        if checkClock && System.nanoTime() >= deadlineNanos then cutByDeadline = true
        else
          val (roll, _) = rolls(i)
          val rolled    = oppToMove.withDicePool(roll)
          val replies   = TurnGenerator.generateAllLegalTurnPaths(rolled)
          allReplies(i) = replies

          if replies.isEmpty then
            probeStatesBuf += oppToMove.endTurn()
            probeRollIndices(i) = probeStateCount
            probeStateCount += 1
          else if replies.exists(reply => capturesEnemyKing(rolled, reply)) then
            lossTainted = true
            probeRollIndices(i) = -1
          else
            val top1Reply = replies.minBy(r => Evaluator.evaluateMaterial(applyTurn(rolled, r), myColor))
            probeStatesBuf += applyTurn(rolled, top1Reply)
            probeRollIndices(i) = probeStateCount
            probeStateCount += 1
          i += 1

      if !cutByDeadline then
        val rawProbeStates = probeStatesBuf.result().toArray
        if rawProbeStates.nonEmpty then
          val distinctProbeStates = distinctLeaves(rawProbeStates.clone())
          val probeScores         = evalBatch(distinctProbeStates, myColor)

          val scoreMap = new scala.collection.mutable.HashMap[LeafKey, Int](distinctProbeStates.length * 2, 0.75)
          var m        = 0
          while m < distinctProbeStates.length do
            scoreMap.put(leafKey(distinctProbeStates(m)), probeScores(m))
            m += 1

          var k = 0
          while k < totalRolls do
            val idx = probeRollIndices(k)
            val probeValue =
              if idx == -1 then LossValue
              else scoreMap(leafKey(rawProbeStates(idx))).toDouble
            remainingCeiling(k) = probeValue
            k += 1
        else
          var k = 0
          while k < totalRolls do
            remainingCeiling(k) = LossValue
            k += 1

        var k = totalRolls - 1
        while k >= 0 do
          val weight = rolls(k)._2
          remainingCeiling(k) =
            remainingCeiling(k + 1) + (weight.toDouble / DiceRolls.totalOrderedRolls) * remainingCeiling(k)
          k -= 1

        if remainingCeiling(0) <= alpha then cutByStar2 = true

    if cutByStar2 then
      ChanceNodeResult(
        value = remainingCeiling(0),
        lossTainted = lossTainted,
        complete = false,
        pruned = true,
        rollsProcessed = 0,
        probePruned = true
      )
    else if cutByDeadline then
      ChanceNodeResult(
        value = 0.0,
        lossTainted = lossTainted,
        complete = false,
        pruned = false,
        rollsProcessed = 0
      )
    else
      // Fall through to Star1 roll iteration, using probe values as per-roll ceilings when available
      var acc             = 0.0
      var processedWeight = 0
      var i               = 0

      while i < totalRolls && !cutByDeadline && !cutByStar1 do
        val remainingProb = (DiceRolls.totalOrderedRolls - processedWeight).toDouble / DiceRolls.totalOrderedRolls
        val upperBound =
          if alpha > Double.NegativeInfinity then acc + remainingCeiling(i)
          else acc + remainingProb * UpperScoreBound

        if alpha > Double.NegativeInfinity && upperBound <= alpha then cutByStar1 = true
        else if checkClock && System.nanoTime() >= deadlineNanos then cutByDeadline = true
        else
          val (roll, weight) = rolls(i)
          val replies =
            if allReplies(i) != null then allReplies(i)
            else
              val rolled = oppToMove.withDicePool(roll)
              TurnGenerator.generateAllLegalTurnPaths(rolled)

          val rolled = oppToMove.withDicePool(roll)
          val rollValue =
            if replies.isEmpty then evalOne(oppToMove.endTurn(), myColor)
            else opponentMinValue(rolled, replies, myColor)

          if rollValue == LossValue then lossTainted = true
          acc += (weight.toDouble / DiceRolls.totalOrderedRolls) * rollValue
          processedWeight += weight
          i += 1

      if cutByStar1 then
        val remainingProb = (DiceRolls.totalOrderedRolls - processedWeight).toDouble / DiceRolls.totalOrderedRolls
        val upperBound =
          if alpha > Double.NegativeInfinity then acc + remainingCeiling(i)
          else acc + remainingProb * UpperScoreBound

        ChanceNodeResult(
          value = upperBound,
          lossTainted = lossTainted,
          complete = false,
          pruned = true,
          rollsProcessed = i
        )
      else if cutByDeadline then
        ChanceNodeResult(
          value = acc,
          lossTainted = lossTainted,
          complete = false,
          pruned = false,
          rollsProcessed = i
        )
      else
        ChanceNodeResult(
          value = acc,
          lossTainted = lossTainted,
          complete = true,
          pruned = false,
          rollsProcessed = i
        )

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
    probePruned: Boolean = false
)

object ExpectimaxSearch:

  /** Upper score bound for non-king evaluation values (e.g. ONNX score scale [0, 10000]). Used by Star1 chance-node
    * pruning to bound the maximum possible expectation of unprocessed dice rolls ($S + R \cdot U$).
    */
  private[search] val UpperScoreBound: Double = 10000.0

  /** Value of a leaf in which the opponent captures our king. Chosen far below any real evaluation on any scale
    * (material centipawns or a scaled win-probability) so the opponent always prefers it and such a line always ranks
    * last — without tying the search to a particular evaluator's range.
    */
  private val LossValue: Double = -1e9

  /** Sentinel deadline for the un-timed entry points: `System.nanoTime()` never reaches it in practice. */
  private val NoDeadline: Long = Long.MaxValue

  /** Whether a real deadline was supplied. Guarding every clock read with this keeps the untimed path free of
    * `System.nanoTime()` syscalls entirely — the per-roll check added for #496 must not tax the arena, whose
    * reproducibility and benchmarks both live on that path.
    */
  private inline def timed(deadlineNanos: Long): Boolean = deadlineNanos != NoDeadline

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
