// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.Color

/** The utility scale a stochastic search reasons on: one declared perspective, the clamped range of non-terminal
  * evaluations, and the two terminal sentinels that must dominate it.
  *
  * ## One perspective
  *
  * Every window `[alpha, beta]`, every chance-node expectation and every [[SearchOutcome]] value is expressed from
  * `perspective`'s point of view: larger is better for `perspective`, whichever side is to move. A decision node is
  * **MAX** when its mover is `perspective` and **MIN** when its mover is `perspective.opponent` — see
  * [[UtilityBounds.isMaximizing]] — and the perspective never flips inside one search, so no value is negated on its
  * way up the tree.
  *
  * ## Terminal dominance
  *
  * A king capture ends the game, and material or model evaluators never see the king, so terminal values live outside
  * the evaluator's range as sentinels. The constructor enforces the strict ordering
  *
  * ```scala sc:nocompile
  * terminalLoss < minNonTerminal <= maxNonTerminal < terminalWin
  * ```
  *
  * so a lost line always ranks below every real evaluation, a won line always above, and Star1/Star2 pruning can bound
  * the unexpanded probability mass of a chance node by the scale's edges (`maxNonTerminal` for the mass that cannot
  * lose a king, `terminalWin`/`terminalLoss` where it can). At a MIN chance node the pessimistic per-roll bound is
  * `terminalLoss`, not `minNonTerminal`, until opponent king capture on the unexpanded rolls has been ruled out.
  *
  * The root is the one place this scale is not used: an immediate root king capture short-circuits with
  * [[SearchScoring.TerminalWinScore]] (`Int.MaxValue`) before any pre-ranking or chance expansion, exactly as every
  * other [[SearchAlgorithm]] reports a winning turn.
  *
  * ## Evaluator obligation
  *
  * A leaf evaluator plugged into a search declared on these bounds must return non-terminal values inside
  * `[minNonTerminal, maxNonTerminal]`; [[UtilityBounds.clampNonTerminal]] is the search's defence against a value that
  * strays, and a value it has to clamp is an evaluator bug, not a search feature.
  *
  * ## Example
  *
  * ```scala
  * import dicechess.engine.domain.Color
  * import dicechess.engine.search.{ExpectimaxSearch, UtilityBounds}
  *
  * val bounds = ExpectimaxSearch.utilityBounds(perspective = Color.White)
  * assert(bounds.isMaximizing(Color.White) && !bounds.isMaximizing(Color.Black))
  * assert(bounds.terminalLoss < bounds.minNonTerminal)
  * assert(bounds.maxNonTerminal < bounds.terminalWin)
  * assert(bounds.terminalFor(winner = Color.Black) == bounds.terminalLoss)
  * assert(bounds.clampNonTerminal(bounds.maxNonTerminal + 1.0) == bounds.maxNonTerminal)
  *
  * // A scale whose sentinels do not dominate its evaluations is rejected at construction.
  * val rejected = scala.util.Try(
  *   UtilityBounds(Color.White, terminalLoss = 0.0, minNonTerminal = 0.0, maxNonTerminal = 1.0, terminalWin = 2.0)
  * )
  * assert(rejected.isFailure)
  * ```
  *
  * @param perspective
  *   the colour every value is scored for
  * @param terminalLoss
  *   the value of a line in which `perspective` loses its king; strictly below every non-terminal value
  * @param minNonTerminal
  *   the lowest value a leaf evaluator may return
  * @param maxNonTerminal
  *   the highest value a leaf evaluator may return
  * @param terminalWin
  *   the value of a line in which `perspective` captures the opposing king; strictly above every non-terminal value
  */
final case class UtilityBounds(
    perspective: Color,
    terminalLoss: Double,
    minNonTerminal: Double,
    maxNonTerminal: Double,
    terminalWin: Double
) derives CanEqual:
  require(
    terminalLoss < minNonTerminal,
    s"terminalLoss ($terminalLoss) must be strictly below minNonTerminal ($minNonTerminal)"
  )
  require(
    minNonTerminal <= maxNonTerminal,
    s"minNonTerminal ($minNonTerminal) must not exceed maxNonTerminal ($maxNonTerminal)"
  )
  require(
    maxNonTerminal < terminalWin,
    s"maxNonTerminal ($maxNonTerminal) must be strictly below terminalWin ($terminalWin)"
  )

  /** Whether a decision node whose side to move is `mover` maximises (`mover == perspective`) or minimises. */
  def isMaximizing(mover: Color): Boolean = mover == perspective

  /** The terminal sentinel for a king capture by `winner`: [[terminalWin]] for `perspective`, else [[terminalLoss]]. */
  def terminalFor(winner: Color): Double = if winner == perspective then terminalWin else terminalLoss

  /** Whether `value` is one of the two terminal sentinels (or beyond them). */
  def isTerminal(value: Double): Boolean = value <= terminalLoss || value >= terminalWin

  /** `value` forced into `[minNonTerminal, maxNonTerminal]`; a no-op for a well-behaved evaluator. */
  def clampNonTerminal(value: Double): Double = math.max(minNonTerminal, math.min(maxNonTerminal, value))

  /** `value` forced into `[terminalLoss, terminalWin]`, the full range a search window can be stated in. */
  def clamp(value: Double): Double = math.max(terminalLoss, math.min(terminalWin, value))

  /** The same scale scored for the other colour. */
  def flipped: UtilityBounds = copy(perspective = perspective.opponent)
