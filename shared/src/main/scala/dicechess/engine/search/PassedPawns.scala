package dicechess.engine.search

import dicechess.engine.domain.*

/** Shared-core primitive for detecting and evaluating passed pawns for an explicit perspective color.
  *
  * Uses the standard chess definition: a pawn is passed if there are no opposing pawns on the same file or on any
  * adjacent file on any square ahead of the pawn along its direction of advance.
  *
  * The evaluation perspective is the explicit `color` argument, independent of `state.activeColor` and the dice pool.
  */
object PassedPawns:

  /** Precomputed front spans for White pawns: squares ahead (ranks > pawn rank) on adjacent and same files. */
  private val WhiteFrontSpans: Array[Long] =
    val arr = new Array[Long](64)
    var idx = 0
    while idx < 64 do
      val f       = (idx % 8) + 'a'
      val r       = (idx / 8) + 1
      var mask    = 0L
      var rank    = r + 1
      val minFile = Math.max('a', f - 1)
      val maxFile = Math.min('h', f + 1)
      while rank <= 8 do
        var file = minFile
        while file <= maxFile do
          val targetIdx = ((rank - 1) * 8) + (file - 'a')
          mask |= (1L << targetIdx)
          file += 1
        rank += 1
      arr(idx) = mask
      idx += 1
    arr

  /** Precomputed front spans for Black pawns: squares ahead (ranks < pawn rank) on adjacent and same files. */
  private val BlackFrontSpans: Array[Long] =
    val arr = new Array[Long](64)
    var idx = 0
    while idx < 64 do
      val f       = (idx % 8) + 'a'
      val r       = (idx / 8) + 1
      var mask    = 0L
      var rank    = 1
      val minFile = Math.max('a', f - 1)
      val maxFile = Math.min('h', f + 1)
      while rank < r do
        var file = minFile
        while file <= maxFile do
          val targetIdx = ((rank - 1) * 8) + (file - 'a')
          mask |= (1L << targetIdx)
          file += 1
        rank += 1
      arr(idx) = mask
      idx += 1
    arr

  /** Returns a [[Bitboard]] of all passed pawns belonging to `color`. */
  def bitboard(state: GameState, color: Color): Bitboard =
    val myPieces  = if color.isWhite then state.whitePieces else state.blackPieces
    val oppPieces = if color.isWhite then state.blackPieces else state.whitePieces
    val myPawns   = state.pawns & myPieces
    val oppPawns  = (state.pawns & oppPieces).value
    val spans     = if color.isWhite then WhiteFrontSpans else BlackFrontSpans
    var passed    = 0L
    var p         = myPawns.value
    while p != 0L do
      val sqIdx = java.lang.Long.numberOfTrailingZeros(p)
      if (oppPawns & spans(sqIdx)) == 0L then passed |= (1L << sqIdx)
      p &= (p - 1L)
    Bitboard(passed)

  /** Returns the number of passed pawns for `color`. */
  def count(state: GameState, color: Color): Int =
    bitboard(state, color).count

  /** Returns the rank of the most advanced passed pawn for `color`, counted from that side's own back rank as 0 (so 1–6
    * for a pawn, and 0 when the side has no passed pawn).
    */
  def maxRank(state: GameState, color: Color): Int =
    val bb = bitboard(state, color).value
    if bb == 0L then 0
    else
      var max = 0
      var p   = bb
      while p != 0L do
        val sqIdx = java.lang.Long.numberOfTrailingZeros(p)
        val rank  = (sqIdx / 8) + 1
        val adv   = if color.isWhite then rank - 1 else 8 - rank
        if adv > max then max = adv
        p &= (p - 1L)
      max

  /** Returns a tuple `(count, maxRank)` of passed pawn count and most advanced rank for `color`. */
  def countAndMaxRank(state: GameState, color: Color): (Int, Int) =
    val bb = bitboard(state, color).value
    if bb == 0L then (0, 0)
    else
      var max   = 0
      var count = 0
      var p     = bb
      while p != 0L do
        count += 1
        val sqIdx = java.lang.Long.numberOfTrailingZeros(p)
        val rank  = (sqIdx / 8) + 1
        val adv   = if color.isWhite then rank - 1 else 8 - rank
        if adv > max then max = adv
        p &= (p - 1L)
      (count, max)
