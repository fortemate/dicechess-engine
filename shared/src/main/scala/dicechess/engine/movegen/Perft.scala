package dicechess.engine.movegen

import dicechess.engine.domain.*

/** Performance and correctness testing utility (Perft) for move generation and game-state transitions.
  *
  * Recursively counts the number of leaf nodes at a given depth. If `state.dicePool` is empty,
  * [[MoveGenerator.generateAllMoves]] is used (standard chess pseudo-legal moves); otherwise,
  * [[MoveGenerator.generateMoves]] generates moves constrained by the current dice roll.
  */
object Perft:

  /** Counts the number of leaf nodes at a given depth.
    *
    * @param state
    *   The starting game state.
    * @param depth
    *   The remaining depth to search (leaf node counted at `depth <= 0`).
    * @return
    *   The total number of leaf nodes.
    */
  def countNodes(state: GameState, depth: Int): Long =
    if depth <= 0 then 1L
    else
      val dicePool = state.dicePool
      val moves    =
        if dicePool.isEmpty then MoveGenerator.generateAllMoves(state)
        else MoveGenerator.generateMoves(state)
      if depth == 1 then moves.length.toLong
      else
        var nodes = 0L
        for mv <- moves do
          val nextState = state.makeMove(mv).endTurn().withDicePool(dicePool)
          nodes += countNodes(nextState, depth - 1)
        nodes

  /** Performs a divide Perft: lists each move and the number of leaf nodes it produces.
    *
    * @param state
    *   The starting game state.
    * @param depth
    *   The search depth (must be at least 1).
    * @return
    *   A map from move notation to leaf node count.
    */
  def divide(state: GameState, depth: Int): Map[String, Long] =
    require(depth >= 1, s"divide depth must be >= 1, got $depth")
    val dicePool = state.dicePool
    val moves    =
      if dicePool.isEmpty then MoveGenerator.generateAllMoves(state)
      else MoveGenerator.generateMoves(state)
    moves.map { mv =>
      val notation  = mv.toUci
      val nextState = state.makeMove(mv).endTurn().withDicePool(dicePool)
      val count     = if depth > 1 then countNodes(nextState, depth - 1) else 1L
      notation -> count
    }.toMap
