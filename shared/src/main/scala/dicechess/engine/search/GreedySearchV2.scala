// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import scala.util.Random

/** Greedy bot strategy for Dice Chess with King Safety Heuristics.
  *
  * Evaluates all legal full-turn paths using [[TurnGenerator]] and selects the one with the highest score from
  * [[Evaluator.evaluate]]. It penalizes paths that leave its own king exposed to immediate capture.
  */
object GreedySearchV2 extends SearchAlgorithm:

  /** Finds the best move using the greedy strategy with a fresh `Random` instance per call.
    *
    * @param state
    *   current [[dicechess.engine.domain.GameState]]; `state.activeColor` indicates who is moving
    * @return
    *   the highest-scoring [[ScoredSequence]], or `None` if no legal move exists
    */
  override def findBestMove(state: GameState): Option[ScoredSequence] =
    findBestMove(state, new Random())

  /** Finds the best move with an explicit `Random` instance, enabling deterministic testing.
    *
    * @param state
    *   current [[dicechess.engine.domain.GameState]]
    * @param rand
    *   random number generator used for tie-breaking among equally-scored paths
    * @return
    *   the highest-scoring [[ScoredSequence]], or `None` if no legal move exists
    */
  override def findBestMove(state: GameState, rand: Random): Option[ScoredSequence] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then None
    else
      val winningPaths = paths.takeWhile(path => SearchScoring.isTerminalWin(state, path))
      if winningPaths.nonEmpty then
        val minLen       = winningPaths.map(_.length).min
        val shortestWins = winningPaths.filter(_.length == minLen)
        Some(ScoredSequence(shortestWins(rand.nextInt(shortestWins.length)), SearchScoring.TerminalWinScore))
      else
        val scoredPaths  = paths.map(path => SearchScoring.scorePath(state, path, Evaluator.evaluate))
        val maxScore     = scoredPaths.map(_.score).max
        val optimalPaths = scoredPaths.filter(_.score == maxScore)
        Some(optimalPaths(rand.nextInt(optimalPaths.length)))

  override def shouldOfferDouble(state: GameState, currentStake: Int): Boolean =
    val _ = currentStake
    estimateWinProbability(state) > 0.70

  override def shouldAcceptDouble(state: GameState, currentStake: Int, responder: Color): Boolean =
    val _ = currentStake
    winProbability(state, responder) > 0.35

  override def shouldOfferDraw(state: GameState): Boolean =
    state.fullMoveNumber > 30 && math.abs(Evaluator.evaluate(state, state.activeColor)) < 50

  override def shouldAcceptDraw(state: GameState): Boolean =
    Evaluator.evaluate(state, state.activeColor) < -100
