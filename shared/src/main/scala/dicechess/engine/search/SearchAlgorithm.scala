package dicechess.engine.search

import dicechess.engine.domain.*

import scala.annotation.unused
import scala.util.Random

/** The scored result of a full-turn path evaluation.
  *
  * @param moves
  *   the sequence of 1–3 micro-moves that make up the turn; never empty for a valid result
  * @param score
  *   material score from the perspective of the side that played the turn. Use [[SearchScoring.TerminalWinScore]] to
  *   signal a King capture (win condition).
  */
case class ScoredSequence(moves: List[Move], score: Int)

/** Contract for bot strategies in the Dice Chess Engine.
  *
  * Implementations receive the current [[dicechess.engine.domain.GameState]] and the multiset of available dice rolls,
  * then return the chosen full-turn path wrapped in a [[ScoredSequence]], or `None` when the position has no legal
  * moves (the active player must pass).
  *
  * Implementations are expected to be thread-safe singletons (e.g., Scala `object`).
  */
trait SearchAlgorithm:
  /** Finds and returns the best full-turn path according to this strategy.
    *
    * @param state
    *   the current [[dicechess.engine.domain.GameState]]; `state.activeColor` identifies the side to move
    * @return
    *   `Some([[ScoredSequence]])` when at least one legal path exists; `None` when the player must pass
    */
  def findBestMove(state: GameState): Option[ScoredSequence]

  /** Finds the best full-turn path, drawing any randomness (e.g. tie-breaking among equally-valued turns) from the
    * supplied `random`. Lets a caller make a whole game reproducible from one seed — most importantly the bot arena,
    * which otherwise gets a fresh unseeded `Random` per move and so is non-deterministic.
    *
    * The default ignores `random` and delegates to the no-arg [[findBestMove]]; randomised strategies override it to
    * route their tie-breaking through the given source.
    */
  def findBestMove(state: GameState, @unused random: Random): Option[ScoredSequence] =
    findBestMove(state)

  /** Determines whether the bot should offer a double before its dice roll.
    *
    * @param state
    *   current game state (dice pool is empty)
    * @param currentStake
    *   the current stake of the game
    * @return
    *   true to offer a double, false otherwise
    */
  def shouldOfferDouble(state: GameState, currentStake: Int): Boolean = false

  /** Determines whether the bot should accept (Take) or decline (Drop) a double from the opponent.
    *
    * @param state
    *   current game state (dice pool is empty)
    * @param currentStake
    *   the proposed double stake (e.g. 2, 4...)
    * @return
    *   true to accept the double (Take), false to resign the current stake (Drop)
    */
  def shouldAcceptDouble(state: GameState, currentStake: Int): Boolean =
    val _ = currentStake
    estimateWinProbability(state) > 0.25

  /** Determines whether the bot should offer a draw in the current position.
    *
    * @param state
    *   current game state
    * @return
    *   true to offer a draw
    */
  def shouldOfferDraw(state: GameState): Boolean = false

  /** Determines whether the bot should accept a draw offered by the opponent.
    *
    * @param state
    *   current game state
    * @return
    *   true to accept the draw
    */
  def shouldAcceptDraw(state: GameState): Boolean = false

  /** Estimates the winning probability in [0.0, 1.0] for the active side.
    *
    * Uses a standard logistic sigmoid function to map the centipawn score to a probability.
    */
  protected def estimateWinProbability(state: GameState): Double =
    val myColor = state.activeColor
    val eval    = Evaluator.evaluate(state, myColor)
    1.0 / (1.0 + math.exp(-eval / 400.0))

/** Shared scoring utilities used by all [[SearchAlgorithm]] implementations.
  *
  * Centralises the terminal-win sentinel and the path-scoring logic so that every strategy applies them consistently.
  */
object SearchScoring:
  /** Sentinel score assigned to any path that ends with a King capture.
    *
    * Using `Int.MaxValue` guarantees that any winning path outscores all material-only evaluations. Strategies that
    * prefer *shorter* wins over *longer* ones must compare path lengths separately (see [[GreedySearch]]).
    */
  val TerminalWinScore: Int = Int.MaxValue

  /** Evaluates a full-turn path and returns a [[ScoredSequence]].
    *
    * The path is replayed move by move, preserving the active color between micro-moves (Dice Chess rule). If the final
    * move captures the opponent's King, the score is set to [[TerminalWinScore]]; otherwise the final position is
    * scored using the provided `evalFn` (which defaults to [[Evaluator.evaluateMaterial]]) from the perspective of the
    * side that played the turn.
    *
    * @param state
    *   the position *before* the turn is played; `state.activeColor` is the side to move
    * @param path
    *   the sequence of moves to evaluate; may be empty (yields material score of the current position)
    * @param evalFn
    *   function used to evaluate the final position (e.g. [[Evaluator.evaluateMaterial]] or [[Evaluator.evaluate]])
    * @return
    *   a [[ScoredSequence]] bundling `path` and its computed score
    */
  def scorePath(
      state: GameState,
      path: List[Move],
      evalFn: (GameState, Color) => Int = Evaluator.evaluateMaterial
  ): ScoredSequence =
    val score =
      if path.isEmpty then evalFn(state, state.activeColor)
      else
        val activeColor       = state.activeColor
        val intermediateState = path.init.foldLeft(state)((s, m) => s.makeMove(m))
        val lastMove          = path.last
        val isKingCapture     = intermediateState.isKingCapture(lastMove)

        if isKingCapture then TerminalWinScore
        else
          val finalState = intermediateState.makeMove(lastMove).endTurn()
          evalFn(finalState, activeColor)
    ScoredSequence(path, score)
