package dicechess.engine.search

import dicechess.engine.domain.*
import scala.util.Random
import scala.util.boundary, boundary.break

/** Monte-Carlo search bot (difficulty 6).
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

  override def findBestMove(state: GameState): Option[ScoredSequence] =
    findBestMove(state, DefaultConfig, rand)

  /** Reproducible entry point (e.g. the arena): the default rollout budget, driven by the supplied `random`. */
  override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
    findBestMove(state, DefaultConfig, random)

  /** Finds the best turn under an explicit rollout budget and random source. */
  def findBestMove(state: GameState, config: MonteCarloConfig, random: Random): Option[ScoredSequence] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then None
    else
      val scoredPaths  = paths.map(path => SearchScoring.scorePath(state, path, equityEval(config, random)))
      val maxCriterion = scoredPaths.map(s => (s.score, terminalWinPreference(s))).max
      val optimalPaths = scoredPaths.filter(s => (s.score, terminalWinPreference(s)) == maxCriterion)
      Some(optimalPaths(random.nextInt(optimalPaths.length)))

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
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then break(None)

    // Cheap material pre-score; scorePath assigns TerminalWinScore to king-capture turns regardless of the evalFn.
    val preScored = paths.map(p => SearchScoring.scorePath(state, p, Evaluator.evaluateMaterial))
    val captures  = preScored.filter(_.score == SearchScoring.TerminalWinScore)
    if captures.nonEmpty then break(Some(captures.minBy(_.moves.size))) // take the fastest win, no rollouts needed

    val candidates = preScored.sortBy(s => -s.score).take(MaxCandidates)
    val myColor    = state.activeColor
    var best       = candidates.head // fallback: the best material turn
    var bestWin    = Double.NegativeInfinity
    var i          = 0
    while i < candidates.length && System.nanoTime() < deadlineNanos do
      val slice      = System.nanoTime() + (deadlineNanos - System.nanoTime()) / (candidates.length - i)
      val finalState = candidates(i).moves.foldLeft(state)((s, m) => s.makeMove(m)).endTurn()
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

  /** Among equal-scoring paths, prefer a shorter king capture (faster win); irrelevant for non-terminal scores. */
  private def terminalWinPreference(scored: ScoredSequence): Int =
    if scored.score == SearchScoring.TerminalWinScore then -scored.moves.size else 0

  /** Evaluation handed to [[SearchScoring.scorePath]]: the Monte-Carlo win probability of the post-turn position from
    * the moving side's perspective, scaled to an integer. `scorePath` short-circuits king captures to
    * [[SearchScoring.TerminalWinScore]] before this is called, so rollouts run only on non-terminal continuations.
    */
  private def equityEval(config: MonteCarloConfig, random: Random)(state: GameState, color: Color): Int =
    val est     = MonteCarloEquity.estimate(state, config, random)
    val winProb = if color.isWhite then est.whiteWin else est.blackWin
    (winProb * ProbScoreScale).round.toInt

  /** Uses the Monte-Carlo estimate (rather than the material sigmoid) for doubling-cube decisions. */
  override protected def estimateWinProbability(state: GameState): Double =
    val est = MonteCarloEquity.estimate(state, DefaultConfig, rand)
    if state.activeColor.isWhite then est.whiteWin else est.blackWin

  override def shouldOfferDouble(state: GameState, currentStake: Int): Boolean =
    val _ = currentStake
    estimateWinProbability(state) > 0.65

  override def shouldAcceptDouble(state: GameState, currentStake: Int): Boolean =
    val _ = currentStake
    estimateWinProbability(state) > 0.30
