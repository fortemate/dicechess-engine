package dicechess.engine.search

import dicechess.engine.domain.*
import scala.util.Random

/** Greedy bot strategy for Dice Chess.
  *
  * Evaluates all legal full-turn paths using [[TurnGenerator]] and selects the one with the highest material score from
  * [[Evaluator.evaluateMaterial]]. Among paths with equal scores, it prefers *shorter* winning paths (to end the game
  * sooner) and breaks any remaining ties uniformly at random.
  *
  * This is the primary default bot used by the `dicechess.engine.api.JsApi` (a separate sbt project, not linkable from
  * here) and serves as the baseline for future expectimax-based search improvements.
  */
object GreedySearch extends SearchAlgorithm:

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
      val scoredPaths  = paths.map(path => SearchScoring.scorePath(state, path))
      val maxCriterion = scoredPaths.map(scored => (scored.score, terminalWinPreference(scored))).max
      val optimalPaths = scoredPaths.filter(scored => (scored.score, terminalWinPreference(scored)) == maxCriterion)
      Some(optimalPaths(rand.nextInt(optimalPaths.length)))

  /** Secondary sort key that prefers *shorter* King-capture paths over longer ones.
    *
    * Returns the negated path length for winning sequences (so a shorter path sorts higher), and `0` for any
    * non-terminal path (so non-terminal paths are treated as equal by this criterion).
    *
    * @param scored
    *   the scored sequence to rank
    * @return
    *   `0` for non-terminal paths, or `-path.size` for King-capture paths (smaller size → higher value)
    */
  private def terminalWinPreference(scored: ScoredSequence): Int =
    if scored.score == SearchScoring.TerminalWinScore then -scored.moves.size else 0
