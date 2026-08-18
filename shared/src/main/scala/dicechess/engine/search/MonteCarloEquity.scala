package dicechess.engine.search

import dicechess.engine.domain.*
import scala.util.Random
import scala.util.boundary, boundary.break

/** Result of a Monte-Carlo pre-roll equity estimate.
  *
  * The three probabilities partition every rollout's outcome mass and therefore sum to `1.0`:
  *   - [[whiteWin]] / [[blackWin]] — probability that White / Black captures a king first;
  *   - [[undecided]] — residual survival mass at the ply horizon (games that were neither won nor lost within
  *     `maxPlies`).
  *
  * @param standardError
  *   the standard error of the [[whiteWin]] estimate (`sqrt(sampleVariance / rollouts)`); halve by quadrupling
  *   rollouts.
  * @param varianceReductionVsVanilla
  *   how many times smaller the per-rollout variance is than a vanilla 0/1 Monte-Carlo with the same mean would be
  *   (`mean·(1-mean) / sampleVariance`). Values `> 1` quantify the Rao-Blackwell gain; `+Infinity` means the estimate
  *   is exact (zero sample variance, e.g. a position decided on the first roll).
  */
final case class EquityEstimate(
    whiteWin: Double,
    blackWin: Double,
    undecided: Double,
    rollouts: Int,
    standardError: Double,
    varianceReductionVsVanilla: Double
)

/** Tuning knobs for [[MonteCarloEquity.estimate]].
  *
  * @param rollouts
  *   hard cap on the number of rollouts (also the exact count when [[targetError]] is `0`).
  * @param maxPlies
  *   ply horizon for a single rollout; survival mass still alive at the horizon is reported as `undecided`.
  * @param targetError
  *   when `> 0`, stop early once the White-win standard error drops to or below this value (checked after at least
  *   [[minRollouts]] rollouts). `0` disables adaptive stopping and runs exactly [[rollouts]] rollouts.
  * @param minRollouts
  *   minimum rollouts before adaptive stopping may trigger; ignored when [[targetError]] is `0`.
  */
final case class MonteCarloConfig(
    rollouts: Int = 500,
    maxPlies: Int = 1000,
    targetError: Double = 0.0,
    minRollouts: Int = 128
)

/** Rao-Blackwellized Monte-Carlo pre-roll equity estimator.
  *
  * For a position with too few games in the database to read an empirical win-rate, this estimates
  * `P(White win) / P(Black win) / P(undecided)` on demand. It is the Scala port of the C++ reference
  * ([[https://github.com/rabestro/dice-chess-engine]]).
  *
  * ## Algorithm
  *
  * Each rollout walks a random game and, at every ply, integrates the **exact** per-ply king-capture probability
  * `p = wins / 216` from [[KingCaptureProbability]] instead of sampling a 0/1 win:
  *
  * ```text
  * survive = 1
  * for each ply (side S to move):
  *   p = P(S captures the opponent king on this roll)   // exact, over all 216 rolls
  *   winsOf(S) += survive * p
  *   survive   *= (1 - p)
  *   advance to a random surviving continuation          // a sampled non-capturing turn
  * ```
  *
  * Because the win mass at each node is added analytically (Rao-Blackwellization) rather than as a Bernoulli sample,
  * the estimator has far lower variance than vanilla 0/1 Monte-Carlo — [[EquityEstimate.varianceReductionVsVanilla]]
  * reports the measured ratio.
  *
  * ## Deviation from the reference
  *
  * The reference advances the rollout to *any* sampled legal turn, including king-captures. A king capture is terminal,
  * so following it produces a king-less board that is then (incorrectly) played on, a small second-order bias. As the
  * rules source of truth this estimator instead conditions the continuation on survival: it advances only through turns
  * that do **not** capture a king (the event with probability `1 - p` that `survive` already tracks). The analytic
  * per-ply terms — and therefore the variance self-check — are unaffected.
  */
object MonteCarloEquity:

  /** Survival mass below which a rollout is considered resolved and stops early. */
  private val SurvivalEpsilon = 1e-5

  /** Per-ply attempts to sample a roll that has a surviving (non king-capture) continuation before giving up and
    * treating the remaining survival mass as undecided.
    */
  private val MaxRollRetries = 64

  /** Estimates pre-roll equity with the given configuration and random source. */
  def estimate(state: GameState, config: MonteCarloConfig, random: Random): EquityEstimate =
    estimate(state, config, Long.MaxValue, random)

  /** Estimates pre-roll equity, stopping early once `deadlineNanos` (a [[java.lang.System.nanoTime]] value) is reached
    * — but always after at least one rollout, so the result is never empty. Use for wall-clock-bounded callers such as
    * a time-budgeted bot; unlike the rollout/targetError budgets, the deadline path is non-deterministic (it depends on
    * machine speed), so reserve it for play, not for reproducible tests.
    */
  def estimate(state: GameState, config: MonteCarloConfig, deadlineNanos: Long, random: Random): EquityEstimate =
    var n     = 0
    var meanW = 0.0 // running mean of per-rollout White-win mass
    var m2W   = 0.0 // running sum of squared deviations (Welford) for White-win mass
    var meanB = 0.0
    var meanU = 0.0

    // Ply 0 of every rollout starts from `state`, so its king-capture probability is identical across all rollouts.
    // KingCaptureProbability is deterministic and consumes no RNG, so hoisting it out of the loop is bit-for-bit exact
    // while removing (rollouts - 1) full 56-multiset DFS evaluations per estimate.
    val ply0Capture = KingCaptureProbability.kingCaptureProbability(state, state.activeColor.opponent)

    var stop = false
    while !stop && n < config.rollouts do
      val (w, b, u) = singleRollout(state, config.maxPlies, random, ply0Capture)
      n += 1
      val deltaW = w - meanW
      meanW += deltaW / n
      m2W += deltaW * (w - meanW)
      meanB += (b - meanB) / n
      meanU += (u - meanU) / n

      if config.targetError > 0 && n >= config.minRollouts then
        val se = math.sqrt(sampleVariance(m2W, n) / n)
        if se <= config.targetError then stop = true
      if System.nanoTime() >= deadlineNanos then stop = true

    val variance = sampleVariance(m2W, n)
    val se       = if n > 0 then math.sqrt(variance / n) else 0.0
    val vanilla  = meanW * (1.0 - meanW)
    val vrr      = if variance > 1e-12 then vanilla / variance else Double.PositiveInfinity
    EquityEstimate(meanW, meanB, meanU, n, se, vrr)

  /** Estimates pre-roll equity with a fixed rollout budget. */
  def estimate(state: GameState, rollouts: Int, random: Random): EquityEstimate =
    estimate(state, MonteCarloConfig(rollouts = rollouts), random)

  /** Estimates pre-roll equity with default settings and a fresh (unseeded) random source. */
  def estimate(state: GameState): EquityEstimate =
    estimate(state, MonteCarloConfig(), new Random())

  private inline def sampleVariance(m2: Double, n: Int): Double =
    if n > 1 then m2 / (n - 1) else 0.0

  /** Runs one Rao-Blackwellized rollout, returning `(whiteWinMass, blackWinMass, survivingMass)`. The three always sum
    * to `1.0`.
    */
  private def singleRollout(
      start: GameState,
      maxPlies: Int,
      random: Random,
      ply0Capture: Double
  ): (Double, Double, Double) =
    var state     = start
    var whiteWon  = 0.0
    var blackWon  = 0.0
    var survive   = 1.0
    var ply       = 0
    var resolving = true
    while resolving && ply < maxPlies && survive > SurvivalEpsilon do
      val mover = state.activeColor
      // Ply 0 starts from `start`; reuse the precomputed value (identical input) instead of recomputing KCP.
      val pCapture =
        if ply == 0 then ply0Capture else KingCaptureProbability.kingCaptureProbability(state, mover.opponent)
      if mover.isWhite then whiteWon += pCapture * survive else blackWon += pCapture * survive
      survive *= (1.0 - pCapture)
      if survive <= SurvivalEpsilon then resolving = false
      else
        advance(state, random) match
          case Some(next) => state = next; ply += 1
          case None       => resolving = false // no surviving continuation; remaining survive is undecided
    (whiteWon, blackWon, survive)

  /** Samples a random surviving continuation: rolls the dice, generates legal turns, drops king-capture (terminal)
    * turns, and applies a uniformly random remaining turn. Re-rolls past empty or fully-terminal rolls up to
    * [[MaxRollRetries]] times; returns `None` when none is found.
    */
  private def advance(state: GameState, random: Random): Option[GameState] = boundary:
    var attempt = 0
    while attempt < MaxRollRetries do
      val roll   = List(random.nextInt(6) + 1, random.nextInt(6) + 1, random.nextInt(6) + 1)
      val rolled = state.withDicePool(roll)
      val paths  = TurnGenerator.generateAllLegalTurnPaths(rolled)
      if paths.nonEmpty then
        val survivors = paths.filterNot(p => capturesOpponentKing(rolled, p))
        if survivors.nonEmpty then
          val chosen = survivors(random.nextInt(survivors.length))
          break(Some(chosen.foldLeft(rolled)((s, m) => s.makeMove(m)).endTurn()))
      attempt += 1
    None

  /** True when the last move of `path` captures the side-not-to-move's king (i.e. the turn ends the game). */
  private def capturesOpponentKing(state: GameState, path: List[Move]): Boolean =
    if path.isEmpty then false
    else
      val beforeLast = path.init.foldLeft(state)((s, m) => s.makeMove(m))
      val target     = beforeLast.mailbox(path.last.toSquare)
      !target.isEmpty && target.pieceType == PieceType.King && target.color != state.activeColor
