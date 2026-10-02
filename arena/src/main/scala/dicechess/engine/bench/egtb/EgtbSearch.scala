// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.movegen.LeaperAttacks

/** Shared search utilities for 3-piece stochastic endgame tablebase solvers. */
object EgtbSearch:

  /** Precomputed king attack masks (64-bit bitboards). */
  val KingAttacks: Array[Long] =
    Array.tabulate(64)(sq => LeaperAttacks.kingAttacks(sq).value)

  /** Encodes square indices (kw, kb, aux) into a dense table index in [0, 262143]. */
  @inline def stateIndex(kw: Int, kb: Int, aux: Int): Int =
    (kw << 12) | (kb << 6) | aux

  /** Evaluates White King moves up to `maxMoves` plies. If Black King is captured, returns 1.0f immediately (terminal
    * White win).
    */
  def bestKingMoves(
      kw: Int,
      kb: Int,
      aux: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    def search(curKw: Int, movesLeft: Int): Float =
      var best    = 0.0f
      var attacks = KingAttacks(curKw) & ~(1L << aux)
      while attacks != 0L do
        val nextKw = java.lang.Long.numberOfTrailingZeros(attacks)
        attacks &= attacks - 1
        if nextKw == kb then return 1.0f
        val vPos = gamma * vBlack(stateIndex(nextKw, kb, aux))
        if vPos > best then best = vPos
        if movesLeft > 1 then
          val vNext = search(nextKw, movesLeft - 1)
          if vNext == 1.0f then return 1.0f
          if vNext > best then best = vNext
      best

    search(kw, maxMoves)

  /** Evaluates Black King moves up to `maxMoves` plies (Black minimizes White win probability). If White King is
    * captured, returns 0.0f immediately (terminal Black win). If auxiliary piece is captured, the game transitions to K
    * vs K draw (0.5f).
    */
  def bestBlackKingMoves(
      kw: Int,
      kb: Int,
      aux: Int,
      maxMoves: Int,
      vWhite: Array[Float],
      gamma: Float
  ): Float =
    def search(curKb: Int, movesLeft: Int): Float =
      var best    = 1.0f
      var attacks = KingAttacks(curKb)
      while attacks != 0L do
        val nextKb = java.lang.Long.numberOfTrailingZeros(attacks)
        attacks &= attacks - 1
        if nextKb == kw then return 0.0f
        else if nextKb == aux then
          if 0.5f < best then best = 0.5f
        else
          val vPos = gamma * vWhite(stateIndex(kw, nextKb, aux))
          if vPos < best then best = vPos
          if movesLeft > 1 then
            val vNext = search(nextKb, movesLeft - 1)
            if vNext == 0.0f then return 0.0f
            if vNext < best then best = vNext
      best

    search(kb, maxMoves)
