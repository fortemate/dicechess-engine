// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import scala.util.Random

/** Random bot that never declines a king it can take at once.
  *
  * It plays like [[RandomSearch]], a uniformly random legal full-turn path, with one exception: when the very first
  * micro-move of some path captures the opponent's King (the King is under direct attack from a piece the roll lets it
  * move), it plays one of those paths, chosen uniformly at random.
  *
  * The look is deliberately one micro-move deep. A king capture that needs a preparatory micro-move (a pawn clearing a
  * file, a second knight jump) is not looked for, and the bot does not protect its own King. That keeps it between
  * [[RandomSearch]] and [[CheckmateAwareSearch]], which finds multi-move captures and also keeps its King safe.
  */
object RandomKingCaptureSearch extends SearchAlgorithm:

  private val rand = new Random()

  /** Finds a move using the shared internal `Random` instance.
    *
    * @param state
    *   current [[dicechess.engine.domain.GameState]]; `state.activeColor` indicates who is moving
    * @return
    *   the selected [[ScoredSequence]], or `None` if no legal move exists (forced pass)
    */
  override def findBestMove(state: GameState): Option[ScoredSequence] =
    findBestMove(state, rand)

  /** Finds a move with an explicit `Random` instance for reproducible sampling.
    *
    * @param state
    *   current [[dicechess.engine.domain.GameState]]
    * @param random
    *   RNG used to choose among the candidate paths
    * @return
    *   the selected [[ScoredSequence]], or `None` if no legal move exists (forced pass)
    */
  override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then None
    else
      val capturing = paths.filter(path => state.isKingCapture(path.head))
      if capturing.nonEmpty then
        Some(ScoredSequence(capturing(random.nextInt(capturing.length)), SearchScoring.TerminalWinScore))
      else Some(SearchScoring.scorePath(state, paths(random.nextInt(paths.length))))
