// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import scala.util.boundary
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
    searchWhiteKing(kw, kb, aux, maxMoves, vBlack, gamma)

  private def evaluateWhiteKingStep(
      nextKw: Int,
      kb: Int,
      aux: Int,
      movesLeft: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    if nextKw == kb then 1.0f
    else
      val vPos = gamma * vBlack(stateIndex(nextKw, kb, aux))
      if movesLeft <= 1 then vPos
      else math.max(vPos, searchWhiteKing(nextKw, kb, aux, movesLeft - 1, vBlack, gamma))

  private def searchWhiteKing(
      curKw: Int,
      kb: Int,
      aux: Int,
      movesLeft: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float = boundary:
    var best    = 0.0f
    var attacks = KingAttacks(curKw) & ~(1L << aux)
    while attacks != 0L do
      val nextKw = java.lang.Long.numberOfTrailingZeros(attacks)
      attacks &= attacks - 1
      val stepVal = evaluateWhiteKingStep(nextKw, kb, aux, movesLeft, vBlack, gamma)
      if stepVal == 1.0f then boundary.break(1.0f)
      if stepVal > best then best = stepVal
    best

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
    searchBlackKing(kb, kw, aux, maxMoves, vWhite, gamma)

  private def evaluateBlackKingStep(
      nextKb: Int,
      kw: Int,
      aux: Int,
      movesLeft: Int,
      vWhite: Array[Float],
      gamma: Float
  ): Float =
    if nextKb == kw then 0.0f
    else if nextKb == aux then 0.5f
    else
      val vPos = gamma * vWhite(stateIndex(kw, nextKb, aux))
      if movesLeft <= 1 then vPos
      else math.min(vPos, searchBlackKing(nextKb, kw, aux, movesLeft - 1, vWhite, gamma))

  private def searchBlackKing(
      curKb: Int,
      kw: Int,
      aux: Int,
      movesLeft: Int,
      vWhite: Array[Float],
      gamma: Float
  ): Float = boundary:
    var best    = 1.0f
    var attacks = KingAttacks(curKb)
    while attacks != 0L do
      val nextKb = java.lang.Long.numberOfTrailingZeros(attacks)
      attacks &= attacks - 1
      val stepVal = evaluateBlackKingStep(nextKb, kw, aux, movesLeft, vWhite, gamma)
      if stepVal == 0.0f then boundary.break(0.0f)
      if stepVal < best then best = stepVal
    best
