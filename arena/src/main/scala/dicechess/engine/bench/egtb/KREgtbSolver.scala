// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.{Bitboard, Square}
import dicechess.engine.movegen.MagicBitboards
import java.io.File

/** Stochastic Endgame Tablebase (EGTB) solver for King + Rook vs King (KRvK) in Dice Chess.
  *
  * Solves the Bellman value function V(s) in [0, 1] for all 524,288 states (64 x 64 x 64 x 2) via parallel Value
  * Iteration across 32 threads.
  */
object KREgtbSolver:

  final val StatesPerTurn: Int = 64 * 64 * 64 // 262,144

  @inline def stateIndex(kw: Int, kb: Int, r: Int): Int =
    (kw << 12) | (kb << 6) | r

  @inline def isLegal(kw: Int, kb: Int, r: Int): Boolean =
    kw != kb && kw != r && kb != r

  private val KingAttacks: Array[Long] = EgtbSearch.KingAttacks

  @inline private def rookAttacks(r: Int, kw: Int, occ: Long): Long =
    val sq      = Square.fromIndex(r)
    val attacks = MagicBitboards.rookAttacks(sq, Bitboard(occ)).value
    attacks & ~(1L << kw)

  type SolverConfig = EgtbConfig
  val SolverConfig = EgtbConfig

  type SolverResult = EgtbResult
  val SolverResult = EgtbResult

  def solve(config: SolverConfig = SolverConfig()): SolverResult =
    EgtbTable.runValueIteration(
      name = "KRvK",
      config = config,
      initWhite = 0.85f,
      initBlack = 0.80f,
      auxRange = 0 until 64,
      evalWhite = evaluateWhiteTurn,
      evalBlack = evaluateBlackTurn
    )

  private def evaluateWhiteTurn(kw: Int, kb: Int, r: Int, vBlack: Array[Float], gamma: Float): Float =
    val val00 = gamma * vBlack(stateIndex(kw, kb, r))
    val val10 = bestKingMoves(kw, kb, r, 1, vBlack, gamma)
    val val01 = bestRookMoves(kw, kb, r, 1, vBlack, gamma)
    val val20 = math.max(val10, bestKingMoves(kw, kb, r, 2, vBlack, gamma))
    val val02 = math.max(val01, bestRookMoves(kw, kb, r, 2, vBlack, gamma))
    val val11 = math.max(math.max(val10, val01), bestKingAndRookMoves(kw, kb, r, vBlack, gamma))
    val val30 = math.max(val20, bestKingMoves(kw, kb, r, 3, vBlack, gamma))
    val val03 = math.max(val02, bestRookMoves(kw, kb, r, 3, vBlack, gamma))
    // (2,1) [3/216] and (1,2) [3/216]: 3-dice outcomes mixing King and Rook moves (6/216 total probability).
    // Intentionally bounded using 2-ply composite lower-bound approximations max(val11, val20) and max(val11, val02)
    // to maintain fast value iteration while guaranteeing conservative monotonic convergence.
    val val21 = math.max(val11, val20)
    val val12 = math.max(val11, val02)

    (val00 * 64 +
      val10 * 48 +
      val01 * 48 +
      val20 * 12 +
      val02 * 12 +
      val11 * 24 +
      val30 * 1 +
      val03 * 1 +
      val21 * 3 +
      val12 * 3) / 216.0f

  private def evaluateBlackTurn(kw: Int, kb: Int, r: Int, vWhite: Array[Float], gamma: Float): Float =
    val val0 = gamma * vWhite(stateIndex(kw, kb, r))
    val val1 = bestBlackKingMoves(kw, kb, r, 1, vWhite, gamma)
    val val2 = math.min(val1, bestBlackKingMoves(kw, kb, r, 2, vWhite, gamma))
    val val3 = math.min(val2, bestBlackKingMoves(kw, kb, r, 3, vWhite, gamma))

    (val0 * 125 +
      val1 * 75 +
      val2 * 15 +
      val3 * 1) / 216.0f

  @inline private def bestKingMoves(
      kw: Int,
      kb: Int,
      r: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    EgtbSearch.bestKingMoves(kw, kb, r, maxMoves, vBlack, gamma)

  private def evaluateRookStep(
      nextR: Int,
      kw: Int,
      kb: Int,
      movesLeft: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    if nextR == kb then 1.0f
    else
      val vPos = gamma * vBlack(stateIndex(kw, kb, nextR))
      if movesLeft <= 1 then vPos
      else math.max(vPos, bestRookMoves(kw, kb, nextR, movesLeft - 1, vBlack, gamma))

  private def bestRookMoves(
      kw: Int,
      kb: Int,
      r: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    val occ  = (1L << kw) | (1L << kb) | (1L << r)
    var best = 0.0f
    var b    = rookAttacks(r, kw, occ)
    while b != 0L do
      val nextR = java.lang.Long.numberOfTrailingZeros(b)
      b &= b - 1
      val stepVal = evaluateRookStep(nextR, kw, kb, maxMoves, vBlack, gamma)
      if stepVal == 1.0f then return 1.0f
      if stepVal > best then best = stepVal
    best

  private def searchRookMoves(kw1: Int, kb: Int, r: Int, vBlack: Array[Float], gamma: Float): Float =
    val occ  = (1L << kw1) | (1L << kb) | (1L << r)
    var best = 0.0f
    var bR   = rookAttacks(r, kw1, occ)
    while bR != 0L do
      val r1 = java.lang.Long.numberOfTrailingZeros(bR)
      bR &= bR - 1
      if r1 == kb then return 1.0f
      val v = gamma * vBlack(stateIndex(kw1, kb, r1))
      if v > best then best = v
    best

  private def bestKingThenRookMoves(kw: Int, kb: Int, r: Int, vBlack: Array[Float], gamma: Float): Float =
    var best = 0.0f
    var bK   = KingAttacks(kw) & ~(1L << r)
    while bK != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(bK)
      bK &= bK - 1
      if kw1 == kb then return 1.0f
      val v = searchRookMoves(kw1, kb, r, vBlack, gamma)
      if v == 1.0f then return 1.0f
      if v > best then best = v
    best

  private def searchKingMoves(kw: Int, kb: Int, r1: Int, vBlack: Array[Float], gamma: Float): Float =
    var best = 0.0f
    var bK   = KingAttacks(kw) & ~(1L << r1)
    while bK != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(bK)
      bK &= bK - 1
      if kw1 == kb then return 1.0f
      val v = gamma * vBlack(stateIndex(kw1, kb, r1))
      if v > best then best = v
    best

  private def bestRookThenKingMoves(kw: Int, kb: Int, r: Int, vBlack: Array[Float], gamma: Float): Float =
    val occ  = (1L << kw) | (1L << kb) | (1L << r)
    var best = 0.0f
    var bR   = rookAttacks(r, kw, occ)
    while bR != 0L do
      val r1 = java.lang.Long.numberOfTrailingZeros(bR)
      bR &= bR - 1
      if r1 == kb then return 1.0f
      val v = searchKingMoves(kw, kb, r1, vBlack, gamma)
      if v == 1.0f then return 1.0f
      if v > best then best = v
    best

  private def bestKingAndRookMoves(kw: Int, kb: Int, r: Int, vBlack: Array[Float], gamma: Float): Float =
    val valA = bestKingThenRookMoves(kw, kb, r, vBlack, gamma)
    if valA == 1.0f then 1.0f
    else math.max(valA, bestRookThenKingMoves(kw, kb, r, vBlack, gamma))

  @inline private def bestBlackKingMoves(
      kw: Int,
      kb: Int,
      r: Int,
      maxMoves: Int,
      vWhite: Array[Float],
      gamma: Float
  ): Float =
    EgtbSearch.bestBlackKingMoves(kw, kb, r, maxMoves, vWhite, gamma)

  def saveTable(file: File, vWhite: Array[Float], vBlack: Array[Float], force: Boolean = false): Unit =
    EgtbTable.save(file, dicechess.engine.domain.PieceType.Rook, vWhite, vBlack, force = force)
