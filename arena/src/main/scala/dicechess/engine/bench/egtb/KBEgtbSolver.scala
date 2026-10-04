// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import scala.util.boundary
import dicechess.engine.domain.{Bitboard, Square}
import dicechess.engine.movegen.MagicBitboards
import java.io.File

/** Stochastic Endgame Tablebase (EGTB) solver for King + Bishop vs King (KBvK) in Dice Chess.
  *
  * Solves the Bellman value function V(s) in [0, 1] for all 524,288 states (64 x 64 x 64 x 2) via parallel Value
  * Iteration across all available CPU threads.
  */
object KBEgtbSolver:

  final val StatesPerTurn: Int = 64 * 64 * 64 // 262,144

  @inline def stateIndex(kw: Int, kb: Int, b: Int): Int =
    (kw << 12) | (kb << 6) | b

  @inline def isLegal(kw: Int, kb: Int, b: Int): Boolean =
    kw != kb && kw != b && kb != b

  private val KingAttacks: Array[Long] = EgtbSearch.KingAttacks

  @inline private def bishopAttacks(b: Int, kw: Int, occ: Long): Long =
    val sq      = Square.fromIndex(b)
    val attacks = MagicBitboards.bishopAttacks(sq, Bitboard(occ)).value
    attacks & ~(1L << kw)

  type SolverConfig = EgtbConfig
  val SolverConfig = EgtbConfig

  type SolverResult = EgtbResult
  val SolverResult = EgtbResult

  def solve(config: SolverConfig = SolverConfig()): SolverResult =
    EgtbTable.runValueIteration(
      name = "KBvK",
      config = config,
      initWhite = 0.80f,
      initBlack = 0.75f,
      auxRange = 0 until 64,
      evalWhite = evaluateWhiteTurn,
      evalBlack = evaluateBlackTurn
    )

  private def evaluateWhiteTurn(kw: Int, kb: Int, b: Int, vBlack: Array[Float], gamma: Float): Float =
    val val00 = gamma * vBlack(stateIndex(kw, kb, b))
    val val10 = bestKingMoves(kw, kb, b, 1, vBlack, gamma)
    val val01 = bestBishopMoves(kw, kb, b, 1, vBlack, gamma)
    val val20 = math.max(val10, bestKingMoves(kw, kb, b, 2, vBlack, gamma))
    val val02 = math.max(val01, bestBishopMoves(kw, kb, b, 2, vBlack, gamma))
    val val11 = math.max(math.max(val10, val01), bestKingAndBishopMoves(kw, kb, b, vBlack, gamma))
    val val30 = math.max(val20, bestKingMoves(kw, kb, b, 3, vBlack, gamma))
    val val03 = math.max(val02, bestBishopMoves(kw, kb, b, 3, vBlack, gamma))
    // (2,1) [3/216] and (1,2) [3/216]: 3-dice outcomes mixing King and Bishop moves (6/216 total probability).
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

  private def evaluateBlackTurn(kw: Int, kb: Int, b: Int, vWhite: Array[Float], gamma: Float): Float =
    val val0 = gamma * vWhite(stateIndex(kw, kb, b))
    val val1 = bestBlackKingMoves(kw, kb, b, 1, vWhite, gamma)
    val val2 = math.min(val1, bestBlackKingMoves(kw, kb, b, 2, vWhite, gamma))
    val val3 = math.min(val2, bestBlackKingMoves(kw, kb, b, 3, vWhite, gamma))

    (val0 * 125 +
      val1 * 75 +
      val2 * 15 +
      val3 * 1) / 216.0f

  @inline private def bestKingMoves(
      kw: Int,
      kb: Int,
      b: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    EgtbSearch.bestKingMoves(kw, kb, b, maxMoves, vBlack, gamma)

  private def evaluateBishopStep(
      nextB: Int,
      kw: Int,
      kb: Int,
      movesLeft: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    if nextB == kb then 1.0f
    else
      val vPos = gamma * vBlack(stateIndex(kw, kb, nextB))
      if movesLeft <= 1 then vPos
      else math.max(vPos, bestBishopMoves(kw, kb, nextB, movesLeft - 1, vBlack, gamma))

  private def bestBishopMoves(
      kw: Int,
      kb: Int,
      b: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float = boundary:
    val occ  = (1L << kw) | (1L << kb) | (1L << b)
    var best = 0.0f
    var b1   = bishopAttacks(b, kw, occ)
    while b1 != 0L do
      val nextB = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      val stepVal = evaluateBishopStep(nextB, kw, kb, maxMoves, vBlack, gamma)
      if stepVal == 1.0f then boundary.break(1.0f)
      if stepVal > best then best = stepVal
    best

  private def searchBishopMoves(kw1: Int, kb: Int, b: Int, vBlack: Array[Float], gamma: Float): Float = boundary:
    val occ  = (1L << kw1) | (1L << kb) | (1L << b)
    var best = 0.0f
    var bB   = bishopAttacks(b, kw1, occ)
    while bB != 0L do
      val b1Sq = java.lang.Long.numberOfTrailingZeros(bB)
      bB &= bB - 1
      if b1Sq == kb then boundary.break(1.0f)
      val v = gamma * vBlack(stateIndex(kw1, kb, b1Sq))
      if v > best then best = v
    best

  private def bestKingThenBishopMoves(kw: Int, kb: Int, b: Int, vBlack: Array[Float], gamma: Float): Float = boundary:
    var best = 0.0f
    var bK   = KingAttacks(kw) & ~(1L << b)
    while bK != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(bK)
      bK &= bK - 1
      if kw1 == kb then boundary.break(1.0f)
      val v = searchBishopMoves(kw1, kb, b, vBlack, gamma)
      if v == 1.0f then boundary.break(1.0f)
      if v > best then best = v
    best

  private def searchKingMoves(kw: Int, kb: Int, b1Sq: Int, vBlack: Array[Float], gamma: Float): Float = boundary:
    var best = 0.0f
    var bK   = KingAttacks(kw) & ~(1L << b1Sq)
    while bK != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(bK)
      bK &= bK - 1
      if kw1 == kb then boundary.break(1.0f)
      val v = gamma * vBlack(stateIndex(kw1, kb, b1Sq))
      if v > best then best = v
    best

  private def bestBishopThenKingMoves(kw: Int, kb: Int, b: Int, vBlack: Array[Float], gamma: Float): Float = boundary:
    val occ  = (1L << kw) | (1L << kb) | (1L << b)
    var best = 0.0f
    var bB   = bishopAttacks(b, kw, occ)
    while bB != 0L do
      val b1Sq = java.lang.Long.numberOfTrailingZeros(bB)
      bB &= bB - 1
      if b1Sq == kb then boundary.break(1.0f)
      val v = searchKingMoves(kw, kb, b1Sq, vBlack, gamma)
      if v == 1.0f then boundary.break(1.0f)
      if v > best then best = v
    best

  private def bestKingAndBishopMoves(kw: Int, kb: Int, b: Int, vBlack: Array[Float], gamma: Float): Float =
    val valA = bestKingThenBishopMoves(kw, kb, b, vBlack, gamma)
    if valA == 1.0f then 1.0f
    else math.max(valA, bestBishopThenKingMoves(kw, kb, b, vBlack, gamma))

  @inline private def bestBlackKingMoves(
      kw: Int,
      kb: Int,
      b: Int,
      maxMoves: Int,
      vWhite: Array[Float],
      gamma: Float
  ): Float =
    EgtbSearch.bestBlackKingMoves(kw, kb, b, maxMoves, vWhite, gamma)

  def saveTable(file: File, vWhite: Array[Float], vBlack: Array[Float], force: Boolean = false): Unit =
    EgtbTable.save(file, dicechess.engine.domain.PieceType.Bishop, vWhite, vBlack, force = force)
