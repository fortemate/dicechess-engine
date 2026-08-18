package dicechess.engine.search

import dicechess.engine.domain.{Color, GameState, Bitboard}

/** Evaluator for Dice Chess positions combining material balance and king safety.
  *
  * Prices pieces from [[MaterialValues]] — the centipawn scale this shares with [[PieceSafety]]'s en-prise telemetry —
  * and applies a significant penalty if the side's king is exposed to immediate capture. This helps greedy strategies
  * avoid "material-greedy" blunders that lead to instant loss.
  */
object Evaluator:

  /** Penalty applied when a player's king is attacked by an enemy piece, leaving it exposed to immediate capture. Set
    * to 2000 centipawns (worth more than a Queen) to heavily discourage material gains that expose the king.
    */
  val KingExposurePenalty: Int = 2000

  /** Centipawns per friendly attacker of a square in the enemy king's ring, used by [[evaluateAggressive]].
    *
    * Named rather than inlined because #513 changed what it multiplies: the count used to come from an early-exiting
    * probe that stopped at the first attacking piece type, so in practice it scaled a number that rarely exceeded one
    * or two. It now scales the true attacker count, which raises the term's weight relative to the pawn-storm and
    * proximity heuristics it was balanced against.
    *
    * Kept at 25 on evidence, not inertia. A sweep of 6/8/10/12/15/20/25 against the pre-#513 behaviour (2000 games
    * each) put every corrected weight at or above level, 50.6–51.9%, with a mild monotone preference for smaller
    * values; a direct A/B of w10 and w12 against w25 (3000 games) then measured only +0.9pp, inside the ±1.8pp the
    * sample can resolve. Re-scaling on that would be tuning to noise, so the correction ships alone. This is still the
    * knob to turn if the balance is revisited — with a run large enough to separate the candidates.
    */
  val RingWeight: Int = 25

  /** Evaluates the overall position for the given `color`, including material and king safety heuristics.
    *
    * @param state
    *   the current [[dicechess.engine.domain.GameState]]
    * @param color
    *   the perspective from which to evaluate
    * @return
    *   signed centipawn score: positive means `color` is ahead, negative means behind
    */
  def evaluate(state: GameState, color: Color): Int =
    evaluateMaterial(state, color) + evaluateKingSafety(state, color)

  /** Evaluates king safety for the given `color`.
    *
    * Returns a negative penalty if the `color`'s king is attacked by an opponent's piece, or 0 if safe.
    *
    * @param state
    *   the current [[dicechess.engine.domain.GameState]]
    * @param color
    *   the side whose safety is evaluated
    * @return
    *   penalty value (negative or 0)
    */
  def evaluateKingSafety(state: GameState, color: Color): Int =
    import dicechess.engine.movegen.MoveGenerator
    import dicechess.engine.domain.Square
    val myPieces = if color.isWhite then state.whitePieces else state.blackPieces
    val myKings  = state.kings & myPieces
    val oppColor = color.opponent

    var isAttacked = false
    var p          = myKings.value
    while p != 0L && !isAttacked do {
      val sqIdx = java.lang.Long.numberOfTrailingZeros(p)
      val sq    = Square.fromIndex(sqIdx)
      if !MoveGenerator.isSquareAttacked(state, sq, oppColor).isEmpty then isAttacked = true
      p &= (p - 1L)
    }
    if isAttacked then -KingExposurePenalty else 0

  /** Evaluates the material balance for the given `color`.
    *
    * A positive return value indicates that `color` has more material than the opponent; a negative value indicates a
    * material deficit.
    *
    * @param state
    *   the current [[dicechess.engine.domain.GameState]]
    * @param color
    *   the perspective from which to evaluate (typically `state.activeColor`)
    * @return
    *   signed centipawn score: positive means `color` is ahead, negative means behind
    */
  def evaluateMaterial(state: GameState, color: Color): Int =
    val myPieces  = if color.isWhite then state.whitePieces else state.blackPieces
    val oppPieces = if color.isWhite then state.blackPieces else state.whitePieces

    scoreBitboard(state, myPieces) - scoreBitboard(state, oppPieces)

  /** Computes the total centipawn value of all pieces in `bb`.
    *
    * Intersects `bb` with each piece-type bitboard to count pieces by type, then multiplies each count by the
    * corresponding fixed piece value.
    *
    * @param state
    *   the current [[dicechess.engine.domain.GameState]] providing piece-type bitboards
    * @param bb
    *   the subset of squares to score (typically one side's pieces)
    * @return
    *   the total centipawn value of pieces on squares in `bb`
    */
  private def scoreBitboard(state: GameState, bb: Bitboard): Int =
    var s = 0
    s += (bb & state.pawns).count * MaterialValues.Pawn
    s += (bb & state.knights).count * MaterialValues.Knight
    s += (bb & state.bishops).count * MaterialValues.Bishop
    s += (bb & state.rooks).count * MaterialValues.Rook
    s += (bb & state.queens).count * MaterialValues.Queen
    s += (bb & state.kings).count * MaterialValues.King
    s

  /** Evaluates the position with aggressive (king hunt) heuristics for the given `color`.
    *
    * Combines standard evaluation (material + king safety) with:
    *   - **Pawn Storm Heuristics:** bonus for advanced friendly pawns.
    *   - **King Proximity Heuristics:** bonus for friendly pieces close to the enemy King.
    *   - **King Ring Pressure Heuristics:** bonus for friendly pieces attacking squares surrounding the enemy King.
    *
    * @param state
    *   current [[dicechess.engine.domain.GameState]]
    * @param color
    *   player color whose perspective to evaluate
    * @return
    *   centipawn score favoring active attacks on the enemy King
    */
  def evaluateAggressive(state: GameState, color: Color): Int =
    import dicechess.engine.domain.Square
    import dicechess.engine.movegen.MoveGenerator

    val standardScore = evaluate(state, color)
    val oppPieces     = if color.isWhite then state.blackPieces else state.whitePieces
    val oppKings      = state.kings & oppPieces

    if oppKings.isEmpty then standardScore // No enemy king to attack (forced win or invalid FEN)
    else
      val ekIdx  = java.lang.Long.numberOfTrailingZeros(oppKings.value)
      val ekSq   = Square.fromIndex(ekIdx)
      val ekRank = ekSq.rank
      val ekFile = ekSq.file.toInt

      val myPieces      = if color.isWhite then state.whitePieces else state.blackPieces
      val friendlyPawns = state.pawns & myPieces

      // 1. Pawn Storm Heuristic
      var pawnStormBonus = 0
      var pw             = friendlyPawns.value
      while pw != 0L do {
        val sqIdx       = java.lang.Long.numberOfTrailingZeros(pw)
        val sq          = Square.fromIndex(sqIdx)
        val advancement = if color.isWhite then sq.rank - 2 else 7 - sq.rank
        pawnStormBonus += advancement * 15
        pw &= (pw - 1L)
      }

      // 2. King Proximity Heuristic (Chebyshev distance of Knights, Bishops, Rooks, Queens)
      val attackers      = (state.knights | state.bishops | state.rooks | state.queens) & myPieces
      var proximityBonus = 0
      var p              = attackers.value
      while p != 0L do {
        val sqIdx  = java.lang.Long.numberOfTrailingZeros(p)
        val sq     = Square.fromIndex(sqIdx)
        val dist   = Math.max(Math.abs(sq.rank - ekRank), Math.abs(sq.file.toInt - ekFile))
        val weight = if state.queens.contains(sq) then 40
        else if state.rooks.contains(sq) then 25
        else 15
        proximityBonus += (8 - dist) * weight
        p &= (p - 1L)
      }

      // 3. King Ring Pressure Heuristic
      //
      // `allAttackers`, not `isSquareAttacked` (#513): the latter returns the attackers of the FIRST
      // matching piece type and stops — an early exit that is its documented contract and what keeps
      // it cheap for legality checks, but which makes `.count` something other than the number of
      // attackers. Probing order being pawns → knights → diagonal → orthogonal → king, a square
      // attacked by one pawn and three pieces scored 1 × RingWeight while one attacked by two knights
      // scored 2 × RingWeight, ranking a broad multi-piece attack BELOW a narrow same-type one — the
      // opposite of what "ring pressure" is meant to reward.
      var kingRingPressure = 0
      for {
        r <- (ekRank - 1) to (ekRank + 1)
        f <- (ekFile - 1) to (ekFile + 1)
        if r >= 1 && r <= 8 && f >= 'a'.toInt && f <= 'h'.toInt
        if !(r == ekRank && f == ekFile)
      } {
        val targetSq          = Square(f.toChar, r)
        val friendlyAttackers = MoveGenerator.allAttackers(state, targetSq, color)
        kingRingPressure += friendlyAttackers.count * RingWeight
      }

      standardScore + pawnStormBonus + proximityBonus + kingRingPressure
