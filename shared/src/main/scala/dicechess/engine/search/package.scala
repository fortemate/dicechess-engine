package dicechess.engine

/** Search and evaluation layer for the Dice Chess Engine.
  *
  * This package implements the bot strategies, turn-path enumeration, and static evaluation function that power the
  * engine's decision-making. It sits above the move-generation layer and operates on complete
  * [[dicechess.engine.domain.GameState]]s.
  *
  * ## Architecture
  *
  * | Component                                     | Responsibility                                                            |
  * |:----------------------------------------------|:--------------------------------------------------------------------------|
  * | [[TurnGenerator]]                             | Enumerates all legal full-turn paths (1–3 micro-moves) via DFS            |
  * | [[dicechess.engine.movegen.LegalMovesFilter]] | Filters first moves under the Maximum Micro-moves Rule (in `movegen`)     |
  * | [[SearchAlgorithm]]                           | Trait contract for bot strategies; returns a [[ScoredSequence]]           |
  * | [[GreedySearch]]                              | Picks the turn path with the highest material score; prefers short wins   |
  * | [[RandomSearch]]                              | Selects a uniformly random legal path; useful as a baseline / test bot    |
  * | [[Evaluator]]                                 | Pure material-balance evaluator used by all current bot strategies        |
  * | [[SearchScoring]]                             | Shared scoring helper: scores a path and handles king-capture termination |
  *
  * ## Turn Model
  *
  * A *turn* in Dice Chess consists of:
  *   1. A dice roll that yields 1–3 die values.
  *   2. A sequence of 1–3 *micro-moves* — each micro-move must be made by the piece type matching the consumed die.
  *   3. The active color is **not** toggled between micro-moves; only after the full turn completes.
  *
  * The **Maximum Micro-moves Rule** requires a player to choose an opening move that is part of the longest achievable
  * micro-move sequence. Any sequence that ends with a King capture is always legal regardless of length.
  *
  * ## Scoring
  *
  * [[SearchScoring.scorePath]] assigns [[SearchScoring.TerminalWinScore]] (`Int.MaxValue`) to any path that captures
  * the opponent's King, and falls back to [[Evaluator.evaluateMaterial]] for non-terminal paths.
  */
package object search
