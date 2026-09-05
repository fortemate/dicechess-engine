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
  *
  * ==Resignation==
  * No `shouldResign` hook exists on [[SearchAlgorithm]] by design. Resignation is an operational decision handled by
  * the bot runtime / host application layer rather than the search strategy.
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

  /** Determines whether the bot should accept (Take) or decline (Drop) a double from the opponent, evaluated from the
    * perspective of `responder`.
    *
    * This is the single policy point for the take/drop decision: strategies that want their own threshold or estimator
    * override **this** overload. The two-argument form below only delegates here, so a policy written against it is
    * bypassed whenever a caller names the responder explicitly (which every caller holding a `doubleDecision` state
    * must, see the hazard note there).
    *
    * @param state
    *   current game state (dice pool is empty); its active colour is the side to move, which in a double decision is
    *   the offerer, not the responder
    * @param currentStake
    *   the proposed double stake (e.g. 2, 4...)
    * @param responder
    *   the colour of the player responding to the double offer
    * @return
    *   true to accept the double (Take), false to resign the current stake (Drop)
    */
  def shouldAcceptDouble(state: GameState, currentStake: Int, responder: Color): Boolean =
    val _ = currentStake
    winProbability(state, responder) > 0.25

  /** Determines whether the bot should accept (Take) or decline (Drop) a double from the opponent, taking the side to
    * move as the responder. Kept for source and binary compatibility; it delegates to the three-argument overload.
    *
    * @note
    *   Perspective hazard: this overload evaluates the win probability of `state.activeColor`. In a double decision
    *   state, `state.activeColor` is the player who offered the double, NOT the responder, so a caller that feeds the
    *   delivered position here asks whether the *offerer* should take. Callers must prefer
    *   `shouldAcceptDouble(state, currentStake, responder)`; strategies must override that overload, not this one.
    *
    * @param state
    *   current game state (dice pool is empty)
    * @param currentStake
    *   the proposed double stake (e.g. 2, 4...)
    * @return
    *   true to accept the double (Take), false to resign the current stake (Drop)
    */
  def shouldAcceptDouble(state: GameState, currentStake: Int): Boolean =
    shouldAcceptDouble(state, currentStake, state.activeColor)

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

  /** Estimates the winning probability in [0.0, 1.0] of `color`, whichever side is to move.
    *
    * This is the estimator hook: a strategy with its own equity model (for example a Monte-Carlo rollout) overrides
    * **this** method, so that both the offer decision (active colour) and the take/drop decision (responder colour) see
    * the same estimate. The default maps the centipawn evaluation to a probability with a logistic sigmoid.
    */
  protected def winProbability(state: GameState, color: Color): Double =
    val eval = Evaluator.evaluate(state, color)
    1.0 / (1.0 + math.exp(-eval / 400.0))

  /** Estimates the winning probability in [0.0, 1.0] for the side to move. Convenience over [[winProbability]] for
    * offer decisions, where the deciding side is the active colour; do not override it, override [[winProbability]].
    */
  protected def estimateWinProbability(state: GameState): Double =
    winProbability(state, state.activeColor)

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
