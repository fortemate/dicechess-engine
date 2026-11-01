// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import scala.util.Random
import scala.util.boundary, boundary.break

/** Monte-Carlo search bot (difficulty 7).
  *
  * Ranks every legal full-turn path by the Rao-Blackwellized Monte-Carlo win probability of the *resulting* position
  * ([[MonteCarloEquity]]) and plays the highest-scoring one; an immediate king capture is always preferred. Where the
  * primitive bots score only a single ply of heuristics, this estimates the full-game win probability via rollouts, so
  * it accounts for deeper consequences.
  *
  * Per-move cost scales with the number of legal turns × the rollout budget, so [[DefaultConfig]] uses a modest budget.
  * Tests and benchmarks pass an explicit [[MonteCarloConfig]] and [[scala.util.Random]] through
  * [[findBestMove(state:dicechess\.engine\.domain\.GameState,config:dicechess\.engine\.search\.MonteCarloConfig,random:scala\.util\.Random)*]]
  * for reproducible, faster, or stronger play.
  */
object MonteCarloSearch extends SearchAlgorithm with DrawOfferLogic with TimeBudgetedSearch:

  /** Maps a win probability in `[0, 1]` onto the integer [[ScoredSequence.score]]. Kept far below
    * [[SearchScoring.TerminalWinScore]] (`Int.MaxValue`) so a king capture always outranks any probabilistic score.
    */
  private val ProbScoreScale = 1_000_000

  /** Default per-move budget — a balance between playing strength and decision latency. */
  val DefaultConfig: MonteCarloConfig = MonteCarloConfig(rollouts = 120, maxPlies = 40)

  /** Config for the wall-clock time-budgeted path: the rollout cap is effectively unbounded so the **deadline**, not
    * the rollout count, is the binding limit and the whole time budget is spent on the strongest play available.
    */
  private val DeadlineConfig: MonteCarloConfig = DefaultConfig.copy(rollouts = Int.MaxValue)

  /** Upper bound on the number of turns the time-budgeted search Monte-Carlo-evaluates per move. The candidate set is
    * first ranked by a cheap material score, so under time pressure rollouts are spent on the most promising turns
    * rather than diluted across a large branching factor.
    */
  private val MaxCandidates = 16

  private val rand = new Random()

  /** Internal container for candidate turns evaluated in search. */
  final private case class CandidateTurn(
      moves: List[Move],
      score: Int,
      resultingState: GameState
  )

  override def findBestMove(state: GameState): Option[ScoredSequence] =
    findBestMove(state, DefaultConfig, rand)

  /** Reproducible entry point (e.g. the arena): the default rollout budget, driven by the supplied `random`. */
  override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
    findBestMove(state, DefaultConfig, random)

  /** Finds the best turn under an explicit rollout budget and random source. */
  def findBestMove(state: GameState, config: MonteCarloConfig, random: Random): Option[ScoredSequence] =
    val kingCaptures = collection.mutable.ArrayBuffer.empty[(List[Move], Int)]
    var bestScore    = Int.MinValue
    val optimalMoves = collection.mutable.ArrayBuffer.empty[List[Move]]
    var anyPath      = false

    TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, isKingCapture) =>
      anyPath = true
      if isKingCapture then
        kingCaptures += ((moves.take(len).toList, len))
        true
      else if kingCaptures.nonEmpty then
        // All king captures are emitted before normal paths; stop early as a king capture strictly dominates.
        false
      else
        val score = equityEval(config, random)(resState, state.activeColor)
        if score > bestScore then
          bestScore = score
          optimalMoves.clear()
          optimalMoves += moves.take(len).toList
        else if score == bestScore then optimalMoves += moves.take(len).toList
        true
    }

    if !anyPath then None
    else if kingCaptures.nonEmpty then
      val minLen   = kingCaptures.map(_._2).min
      val bestWins = kingCaptures.filter(_._2 == minLen)
      val chosen   = bestWins(random.nextInt(bestWins.length))._1
      Some(ScoredSequence(chosen, SearchScoring.TerminalWinScore))
    else
      val chosen = optimalMoves(random.nextInt(optimalMoves.length))
      Some(ScoredSequence(chosen, bestScore))

  /** Finds the best turn under a **wall-clock** deadline (`deadlineNanos`, a [[java.lang.System.nanoTime]] value), so a
    * bot on a game clock never overruns. The caller allocates the game budget into a per-move deadline.
    *
    * Strategy: take any immediate king capture for free; otherwise rank turns by a cheap material score, keep the top
    * [[MaxCandidates]], and Monte-Carlo-evaluate them in that order, giving each an equal slice of the remaining time
    * and stopping at the deadline. The best material turn is the fallback, so a legal move is always returned even if
    * the deadline elapses before any rollout completes. This path is non-deterministic by design.
    */
  def findBestMove(
      state: GameState,
      config: MonteCarloConfig,
      deadlineNanos: Long,
      random: Random
  ): Option[ScoredSequence] = boundary:
    val kingCaptures = collection.mutable.ArrayBuffer.empty[CandidateTurn]
    val normalTurns  = collection.mutable.ArrayBuffer.empty[CandidateTurn]
    var anyPath      = false

    TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, isKingCapture) =>
      anyPath = true
      if isKingCapture then
        kingCaptures += CandidateTurn(moves.take(len).toList, SearchScoring.TerminalWinScore, resState)
        true
      else if kingCaptures.nonEmpty then
        // All king captures are emitted first; stop early if any king capture was found.
        false
      else
        val matScore = Evaluator.evaluateMaterial(resState, state.activeColor)
        normalTurns += CandidateTurn(moves.take(len).toList, matScore, resState)
        true
    }

    if !anyPath then break(None)
    if kingCaptures.nonEmpty then
      // Take the fastest win, no rollouts needed
      break(Some(ScoredSequence(kingCaptures.minBy(_.moves.size).moves, SearchScoring.TerminalWinScore)))

    val candidates = normalTurns.sortBy(c => -c.score).take(MaxCandidates)
    val myColor    = state.activeColor
    var best       = candidates.head // fallback: the best material turn
    var bestWin    = Double.NegativeInfinity
    var i          = 0
    while i < candidates.length && System.nanoTime() < deadlineNanos do
      val slice      = System.nanoTime() + (deadlineNanos - System.nanoTime()) / (candidates.length - i)
      val finalState = candidates(i).resultingState
      val est        = MonteCarloEquity.estimate(finalState, config, slice, random)
      val win        = if myColor.isWhite then est.whiteWin else est.blackWin
      if win > bestWin then
        bestWin = win
        best = candidates(i)
      i += 1

    val score = if bestWin >= 0.0 then (bestWin * ProbScoreScale).round.toInt else best.score
    Some(ScoredSequence(best.moves, score))

  /** Time-budgeted entry point (see [[TimeBudgetedSearch]]). Evaluates within the wall-clock `deadlineNanos` using a
    * rollout cap high enough that the deadline — not the rollout count — bounds the search, so the whole budget is
    * spent on the strongest play available in the time given.
    */
  override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] =
    findBestMove(state, DeadlineConfig, deadlineNanos, random)

  /** Computes the Monte-Carlo win probability of the post-turn position from the moving side's perspective, scaled to
    * an integer. King captures short-circuit to [[SearchScoring.TerminalWinScore]] before this is called, so rollouts
    * run only on non-terminal continuations.
    */
  private def equityEval(config: MonteCarloConfig, random: Random)(state: GameState, color: Color): Int =
    val est     = MonteCarloEquity.estimate(state, config, random)
    val winProb = if color.isWhite then est.whiteWin else est.blackWin
    (winProb * ProbScoreScale).round.toInt

  /** Uses the Monte-Carlo estimate (rather than the material sigmoid) for doubling-cube decisions, for whichever colour
    * is asked about: the offerer on an offer, the responder on a take/drop.
    */
  override protected def winProbability(state: GameState, color: Color): Double =
    winProbability(state, color, DefaultConfig, rand)

  /** [[winProbability]] with an explicit rollout budget and entropy source, so the perspective mapping can be tested on
    * a tiny budget: the estimator integrates the exact capture probability over all 216 rolls at every ply, which makes
    * the default budget expensive on busy positions.
    */
  private[search] def winProbability(state: GameState, color: Color, config: MonteCarloConfig, random: Random): Double =
    val est = MonteCarloEquity.estimate(state, config, random)
    if color.isWhite then est.whiteWin else est.blackWin

  override def shouldOfferDouble(state: GameState, currentStake: Int): Boolean =
    val _ = currentStake
    estimateWinProbability(state) > 0.65

  override def shouldAcceptDouble(state: GameState, currentStake: Int, responder: Color): Boolean =
    val _ = currentStake
    winProbability(state, responder) > 0.30
