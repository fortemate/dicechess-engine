package dicechess.engine.search

import dicechess.engine.domain.*
import scala.util.Random

/** Checkmate-Aware search algorithm for Dice Chess.
  *
  * This bot represents a Difficulty 2 intellect. It understands the primary objective of the game:
  *   - Capturing the opponent's King (instant win).
  *   - Protecting its own King (avoiding immediate capture).
  *
  * It is completely blind to material balance or other positional values, making it play randomly when there are no
  * immediate checkmates to deliver or checks to escape.
  */
object CheckmateAwareSearch extends SearchAlgorithm:

  private val rand = new Random()

  /** Finds the best move using the shared internal `Random` instance.
    *
    * @param state
    *   current [[dicechess.engine.domain.GameState]]
    * @return
    *   the selected [[ScoredSequence]], or `None` if no legal move exists
    */
  override def findBestMove(state: GameState): Option[ScoredSequence] =
    findBestMove(state, rand)

  /** Finds the best move using the specified `Random` instance for deterministic tie-breaking.
    *
    * @param state
    *   current [[dicechess.engine.domain.GameState]]
    * @param random
    *   RNG used for choosing among equally-preferred paths
    * @return
    *   the selected [[ScoredSequence]], or `None` if no legal move exists
    */
  override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then None
    else
      // 1. Prioritize immediate King capture (winning sequences)
      val scoredPaths  = paths.map(path => SearchScoring.scorePath(state, path, (_, _) => 0))
      val winningPaths = scoredPaths.filter(_.score == SearchScoring.TerminalWinScore)

      if winningPaths.nonEmpty then Some(winningPaths(random.nextInt(winningPaths.length)))
      else
        // 2. Prioritize paths where our own King is safe at the end of the turn
        val myColor   = state.activeColor
        val safePaths = scoredPaths.filter { scored =>
          val finalState = scored.moves.foldLeft(state)((s, m) => s.makeMove(m)).endTurn()
          Evaluator.evaluateKingSafety(finalState, myColor) == 0
        }

        if safePaths.nonEmpty then Some(safePaths(random.nextInt(safePaths.length)))
        else
          // 3. Fallback to any legal path if all of them leave the King under attack
          Some(scoredPaths(random.nextInt(scoredPaths.length)))
