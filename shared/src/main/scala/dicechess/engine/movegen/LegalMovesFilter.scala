package dicechess.engine.movegen

import dicechess.engine.domain.*

/** Legal moves filter for Dice Chess.
  *
  * Implements the **Maximum Micro-moves Rule**: a player must choose a first move that is part of the longest possible
  * sequence of micro-moves achievable with the rolled dice.
  *
  * ## Two types of legal first moves
  *   1. **King-Capture** — any sequence of micro-moves that *ends* with capturing the opponent's King is always legal,
  *      regardless of whether it is 1, 2, or 3 moves long.
  *   2. **Non-King-Capture** — any sequence of micro-moves that does *not* end with a King capture must achieve the
  *      globally optimal length `L*(state, dice)`.
  *
  * ## Rules encoded here
  *   - **Normal move** — consumes exactly one die of the matching piece type.
  *   - **Castling** — requires *and* consumes *both* the King die (`6`) and the Rook die (`4`) simultaneously.
  *   - **King-Capture** — terminates the game; a King capture contributes its depth to `maxLen` but is not recursed
  *     into (the game is over). All branches continue to be searched so that `maxLen` is computed correctly.
  *   - **Active-color invariance** — the active color is kept fixed for every intermediate state produced during a
  *     turn; it is *not* toggled between micro-moves.
  */
object LegalMovesFilter:

  // ── Private helpers ──────────────────────────────────────────────────────────

  /** Achievable sequence length for `move` played as a first move from `state`. */
  private inline def rootDepth(state: GameState, move: Move): Int =
    if state.isKingCapture(move) then 1
    else continuationLength(state, move)

  /** Computes the sequence length reachable by playing `move` from `state` when `move` is not a King-Capture.
    *
    * Assumes `move` is a pseudo-legal move generated from `state`, so the mover's die is present in the dice pool.
    * Returns `-1` if `move` is castling but the required dice (King and Rook) are not both available in the dice pool.
    */
  private def continuationLength(state: GameState, move: Move): Int =
    val survived = state.diceAfter(move)
    if !survived.isValid then -1
    else
      val diceConsumed = if move.isCastling then 2 else 1
      val next         = state.makeMove(move).withDiceSlotsOf(survived)
      diceConsumed + maxSequenceLength(next)

  /** Recursively computes the maximum achievable micro-move sequence length from `state`.
    *
    * The search is bounded by the depth of available dice in `state.flags` (at most 3), so it always terminates. The
    * `makeMove` method preserves the active color for micro-moves, meaning no color flipping occurs during the
    * recursion. Callers can safely apply sequential micro-moves.
    *
    * A King-Capture move terminates its branch at depth 1 (the game ends). However, the search continues exploring all
    * other branches — King captures do **not** short-circuit the entire computation. This ensures that `maxLen`
    * reflects the true global maximum, including paths of length 2 or 3 that exist alongside a 1-move King capture.
    *
    * @param state
    *   the board position and remaining dice pool to evaluate
    * @return
    *   the maximum number of micro-moves reachable from `state` using its dice pool
    */
  private def maxSequenceLength(state: GameState): Int =
    if state.flags.isDicePoolEmpty then 0
    else
      var best = 0

      for move <- MoveGenerator.generateMoves(state) do
        val depth = rootDepth(state, move)
        if depth > best then best = depth

      best

  // ── Public API ────────────────────────────────────────────────────────────────

  /** Filters and returns the legal first moves for a given game state (position and rolled dice).
    *
    * A first move is legal if and only if one of the following holds:
    *   1. **King-Capture path** — there exists a continuation from this move (including the move itself) that captures
    *      the opponent's King, making it a win-condition sequence. Legal at any length (1, 2, or 3 micro-moves).
    *   2. **Maximum-length condition** — the move is part of a non-King-capture path whose total length equals
    *      `L*(state, dice)`, the globally optimal sequence length.
    *
    * When no moves are achievable at all (all rolled dice correspond to piece types absent from the board), an empty
    * list is returned and the player must pass their turn.
    *
    * @param state
    *   the current game state including active color and dice pool
    * @return
    *   the list of legal first micro-moves under the Maximum Micro-moves Rule
    */
  def filterMaximalMoves(state: GameState): List[Move] =
    if state.flags.isDicePoolEmpty then Nil
    else
      val moves = MoveGenerator.generateMoves(state)
      if moves.isEmpty then Nil
      else
        val depths = new Array[Int](moves.length)
        var maxLen = 0
        var i      = 0
        var cur    = moves

        // Pass 1: compute achievable sequence length for each candidate first move and record it.
        // This considers ALL branches including King-capture paths without redundant re-traversal.
        while cur.nonEmpty do
          val move  = cur.head
          val depth = rootDepth(state, move)
          depths(i) = depth
          if depth > maxLen then maxLen = depth
          i += 1
          cur = cur.tail

        // If no sequence is achievable (all dice unplayable), the player passes
        if maxLen == 0 then Nil
        else
          // Pass 2: collect legal first moves under both criteria using recorded depths:
          //   (a) king-capture paths — always legal
          //   (b) non-king-capture paths that achieve maxLen
          val result = List.newBuilder[Move]
          i = 0
          cur = moves
          while cur.nonEmpty do
            val move = cur.head
            if state.isKingCapture(move) || depths(i) == maxLen then result += move
            i += 1
            cur = cur.tail

          result.result()
