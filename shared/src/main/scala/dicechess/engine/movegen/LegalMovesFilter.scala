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

  /** Returns `true` when `move` captures the opponent's King (win condition). */
  private def isKingCapture(state: GameState, move: Move): Boolean =
    state.mailbox
      .get(move.toSquare)
      .exists(p => p.pieceType == PieceType.King && p.color != state.activeColor)

  /** Recursively computes the maximum achievable micro-move sequence length from `state` with `remainingDice`.
    *
    * The search is bounded by the depth of `remainingDice` (at most 3), so it always terminates. The `makeMove` method
    * preserves the active color for micro-moves, meaning no color flipping occurs during the recursion. Callers can
    * safely apply sequential micro-moves.
    *
    * A King-Capture move terminates its branch at depth 1 (the game ends). However, the search continues exploring all
    * other branches — King captures do **not** short-circuit the entire computation. This ensures that `maxLen`
    * reflects the true global maximum, including paths of length 2 or 3 that exist alongside a 1-move King capture.
    *
    * @param state
    *   the board position to evaluate
    * @param remainingDice
    *   the dice still available to spend in this turn (multiset)
    * @return
    *   the maximum number of micro-moves reachable from `state` using `remainingDice`
    */
  private def maxSequenceLength(state: GameState): Int =
    if state.flags.isDicePoolEmpty then 0
    else
      var best = 0

      for move <- MoveGenerator.generateMoves(state) do
        val moverType = state.mailbox(move.fromSquare).pieceType
        if isKingCapture(state, move) then
          // King capture ends the game: contributes depth 1 to this branch.
          // Do NOT recurse further, but continue exploring other branches.
          if best < 1 then best = 1
        else if move.isCastling then
          // Castling requires BOTH King (6) and Rook (4) dice to be present
          if state.flags.containsDie(PieceType.King.diceValue) && state.flags.containsDie(PieceType.Rook.diceValue)
          then
            val survived = state.flags.removeDie(PieceType.King.diceValue).removeDie(PieceType.Rook.diceValue)
            val next     = state.makeMove(move).withDiceSlotsOf(survived)
            val depth    = 2 + maxSequenceLength(next)
            if depth > best then best = depth
        else
          val survived = state.flags.removeDie(moverType.diceValue)
          val next     = state.makeMove(move).withDiceSlotsOf(survived)
          val depth    = 1 + maxSequenceLength(next)
          if depth > best then best = depth

      best

  // ── Public API ────────────────────────────────────────────────────────────────

  /** Filters and returns the legal first moves for a given position and rolled dice.
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
    *   the current game state (active color indicates whose turn it is)
    * @param dice
    *   the list of dice rolls for this turn (multiset, values in `[1, 6]`)
    * @return
    *   the list of legal first micro-moves under the Maximum Micro-moves Rule
    */
  def filterMaximalMoves(state: GameState): List[Move] =
    if state.flags.isDicePoolEmpty then Nil
    else
      // Pass 1: determine the globally optimal sequence length from this position.
      // This considers ALL branches including King-capture paths.
      val maxLen = maxSequenceLength(state)

      // If no sequence is achievable (all dice unplayable), the player passes
      if maxLen == 0 then Nil
      else
        // Pass 2: collect legal first moves under both criteria:
        //   (a) king-capture paths — always legal
        //   (b) non-king-capture paths that achieve maxLen
        val result = List.newBuilder[Move]

        for move <- MoveGenerator.generateMoves(state) do
          val moverType = state.mailbox(move.fromSquare).pieceType
          if isKingCapture(state, move) then
            // Type 1: King-capture first move — always legal
            result += move
          else if move.isCastling then
            if state.flags.containsDie(PieceType.King.diceValue) && state.flags.containsDie(PieceType.Rook.diceValue)
            then
              val survived  = state.flags.removeDie(PieceType.King.diceValue).removeDie(PieceType.Rook.diceValue)
              val next      = state.makeMove(move).withDiceSlotsOf(survived)
              val reachable = 2 + maxSequenceLength(next)
              if reachable == maxLen then result += move
          else
            val survived  = state.flags.removeDie(moverType.diceValue)
            val next      = state.makeMove(move).withDiceSlotsOf(survived)
            val reachable = 1 + maxSequenceLength(next)
            if reachable == maxLen then result += move

        result.result()
