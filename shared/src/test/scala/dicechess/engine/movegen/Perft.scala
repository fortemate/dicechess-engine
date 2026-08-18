package dicechess.engine.movegen

import dicechess.engine.domain.*

object Perft:

  /** Counts the number of leaf nodes at a given depth for a fixed dice roll sequence.
    *
    * In this simplified classical-chess version for testing move generation, recursion models turn-ending after each
    * individual micro-move. We explicitly call `.endTurn()` to simulate a full turn transition, and explicitly carry
    * over the dice pool to the next player.
    */
  def countNodes(state: GameState, depth: Int): Long =
    if depth == 0 then 1
    else
      val moves = MoveGenerator.generateMoves(state)
      if depth == 1 then moves.length.toLong
      else
        var nodes = 0L
        for mv <- moves do
          val nextState = state.makeMove(mv).endTurn().withDicePool(state.dicePool) // Maintain dice roll
          nodes += countNodes(nextState, depth - 1)
        nodes

  /** Performs a divide Perft: lists each move and the number of nodes it produces. Useful for debugging discrepancies.
    */
  def divide(state: GameState, depth: Int): Map[String, Long] =
    val moves = MoveGenerator.generateMoves(state)
    moves.map { mv =>
      val notation  = s"${mv.fromSquare}${mv.toSquare}" // Simple notation for now
      val nextState = state.makeMove(mv).endTurn().withDicePool(state.dicePool)
      val count     = if depth > 1 then countNodes(nextState, depth - 1) else 1L
      notation -> count
    }.toMap
