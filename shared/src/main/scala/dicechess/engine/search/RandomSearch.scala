package dicechess.engine.search

import dicechess.engine.domain.*
import scala.util.Random

/** Random bot strategy for Dice Chess.
  *
  * Selects a uniformly random legal full-turn path from all candidates produced by [[TurnGenerator]]. This
  * implementation is primarily intended for:
  *   - **Testing** — provides an unpredictable opponent without evaluation bias.
  *   - **Fairness baselines** — useful for measuring improvement over pure chance.
  *   - **Move diversity** — can be used in training pipelines to generate varied game states.
  *
  * Uses a module-level [[scala.util.Random]] instance that is *not* seeded, so results are non-deterministic across
  * runs. For reproducible play, prefer [[GreedySearch.findBestMove(state, dice, rand)]] with an explicit seed.
  */
object RandomSearch extends SearchAlgorithm:

  private val rand = new Random()

  /** Finds a random legal move.
    *
    * @param state
    *   current [[dicechess.engine.domain.GameState]]; `state.activeColor` indicates who is moving
    * @return
    *   a randomly chosen [[ScoredSequence]], or `None` if no legal move exists (forced pass)
    */
  override def findBestMove(state: GameState): Option[ScoredSequence] =
    findBestMove(state, rand)

  /** Finds a random legal move with deterministic control.
    *
    * This overload of [[RandomSearch.findBestMove(state:GameState)]] accepts an explicit [[scala.util.Random]] instance
    * to enable reproducible and deterministic sampling. It is primarily used for consistent JMH benchmarking and unit
    * testing where branch volatility needs to be eliminated.
    *
    * @param state
    *   current [[dicechess.engine.domain.GameState]]; `state.activeColor` indicates who is moving
    * @param random
    *   explicit [[scala.util.Random]] instance used for tie-breaking selection
    * @return
    *   a randomly chosen [[ScoredSequence]], or `None` if no legal move exists (forced pass)
    */
  override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then None
    else
      val randomPath = paths(random.nextInt(paths.length))
      Some(SearchScoring.scorePath(state, randomPath))

  override def shouldOfferDouble(state: GameState, currentStake: Int): Boolean =
    val _ = (state, currentStake)
    rand.nextDouble() < 0.15

  override def shouldAcceptDouble(state: GameState, currentStake: Int, responder: Color): Boolean =
    val _ = (state, currentStake, responder)
    rand.nextDouble() < 0.50

  override def shouldOfferDraw(state: GameState): Boolean =
    val _ = state
    rand.nextDouble() < 0.05

  override def shouldAcceptDraw(state: GameState): Boolean =
    val _ = state
    rand.nextDouble() < 0.20
