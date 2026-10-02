// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.{Bitboard, Square}
import dicechess.engine.movegen.{LeaperAttacks, MagicBitboards}
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

  private val KingAttacks: Array[Long] =
    Array.tabulate(64)(sq => LeaperAttacks.kingAttacks(sq).value)

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
    val occ = (1L << kw) | (1L << kb) | (1L << b)

    val val00 = gamma * vBlack(stateIndex(kw, kb, b))
    val val10 = bestKingMoves(kw, kb, b, 1, vBlack, gamma)
    val val01 = bestBishopMoves(kw, kb, b, 1, occ, vBlack, gamma)
    val val20 = math.max(val10, bestKingMoves(kw, kb, b, 2, vBlack, gamma))
    val val02 = math.max(val01, bestBishopMoves(kw, kb, b, 2, occ, vBlack, gamma))
    val val11 = math.max(math.max(val10, val01), bestKingAndBishopMoves(kw, kb, b, occ, vBlack, gamma))
    val val30 = math.max(val20, bestKingMoves(kw, kb, b, 3, vBlack, gamma))
    val val03 = math.max(val02, bestBishopMoves(kw, kb, b, 3, occ, vBlack, gamma))
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

  private def bestKingMoves(kw: Int, kb: Int, b: Int, maxMoves: Int, vBlack: Array[Float], gamma: Float): Float =
    var best = 0.0f
    var b1   = KingAttacks(kw) & ~(1L << b)
    while b1 != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if kw1 == kb then return 1.0f
      else
        val v1 = gamma * vBlack(stateIndex(kw1, kb, b))
        if v1 > best then best = v1

        if maxMoves >= 2 then
          var b2 = KingAttacks(kw1) & ~(1L << b)
          while b2 != 0L do
            val kw2 = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if kw2 == kb then return 1.0f
            else
              val v2 = gamma * vBlack(stateIndex(kw2, kb, b))
              if v2 > best then best = v2

              if maxMoves >= 3 then
                var b3 = KingAttacks(kw2) & ~(1L << b)
                while b3 != 0L do
                  val kw3 = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if kw3 == kb then return 1.0f
                  else
                    val v3 = gamma * vBlack(stateIndex(kw3, kb, b))
                    if v3 > best then best = v3
    best

  private def bestBishopMoves(
      kw: Int,
      kb: Int,
      b: Int,
      maxMoves: Int,
      occ: Long,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    var best = 0.0f
    var b1   = bishopAttacks(b, kw, occ)
    while b1 != 0L do
      val b1Sq = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if b1Sq == kb then return 1.0f
      else
        val v1 = gamma * vBlack(stateIndex(kw, kb, b1Sq))
        if v1 > best then best = v1

        if maxMoves >= 2 then
          val occ1 = (occ & ~(1L << b)) | (1L << b1Sq)
          var b2   = bishopAttacks(b1Sq, kw, occ1)
          while b2 != 0L do
            val b2Sq = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if b2Sq == kb then return 1.0f
            else
              val v2 = gamma * vBlack(stateIndex(kw, kb, b2Sq))
              if v2 > best then best = v2

              if maxMoves >= 3 then
                val occ2 = (occ1 & ~(1L << b1Sq)) | (1L << b2Sq)
                var b3   = bishopAttacks(b2Sq, kw, occ2)
                while b3 != 0L do
                  val b3Sq = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if b3Sq == kb then return 1.0f
                  else
                    val v3 = gamma * vBlack(stateIndex(kw, kb, b3Sq))
                    if v3 > best then best = v3
    best

  private def bestKingAndBishopMoves(kw: Int, kb: Int, b: Int, occ: Long, vBlack: Array[Float], gamma: Float): Float =
    var best = 0.0f

    // Branch A: King first, then Bishop
    var bK = KingAttacks(kw) & ~(1L << b)
    while bK != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(bK)
      bK &= bK - 1
      if kw1 == kb then return 1.0f
      else
        val occ1 = (occ & ~(1L << kw)) | (1L << kw1)
        var bB   = bishopAttacks(b, kw1, occ1)
        while bB != 0L do
          val b1Sq = java.lang.Long.numberOfTrailingZeros(bB)
          bB &= bB - 1
          if b1Sq == kb then return 1.0f
          else
            val v = gamma * vBlack(stateIndex(kw1, kb, b1Sq))
            if v > best then best = v

    // Branch B: Bishop first, then King
    var bB = bishopAttacks(b, kw, occ)
    while bB != 0L do
      val b1Sq = java.lang.Long.numberOfTrailingZeros(bB)
      bB &= bB - 1
      if b1Sq == kb then return 1.0f
      else
        var bK2 = KingAttacks(kw) & ~(1L << b1Sq)
        while bK2 != 0L do
          val kw1 = java.lang.Long.numberOfTrailingZeros(bK2)
          bK2 &= bK2 - 1
          if kw1 == kb then return 1.0f
          else
            val v = gamma * vBlack(stateIndex(kw1, kb, b1Sq))
            if v > best then best = v

    best

  private def bestBlackKingMoves(kw: Int, kb: Int, b: Int, maxMoves: Int, vWhite: Array[Float], gamma: Float): Float =
    var best = 1.0f
    var b1   = KingAttacks(kb)
    while b1 != 0L do
      val kb1 = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if kb1 == kw then return 0.0f
      else if kb1 == b then
        if 0.5f < best then best = 0.5f
      else
        val v1 = gamma * vWhite(stateIndex(kw, kb1, b))
        if v1 < best then best = v1

        if maxMoves >= 2 then
          var b2 = KingAttacks(kb1)
          while b2 != 0L do
            val kb2 = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if kb2 == kw then return 0.0f
            else if kb2 == b then
              if 0.5f < best then best = 0.5f
            else
              val v2 = gamma * vWhite(stateIndex(kw, kb2, b))
              if v2 < best then best = v2

              if maxMoves >= 3 then
                var b3 = KingAttacks(kb2)
                while b3 != 0L do
                  val kb3 = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if kb3 == kw then return 0.0f
                  else if kb3 == b then
                    if 0.5f < best then best = 0.5f
                  else
                    val v3 = gamma * vWhite(stateIndex(kw, kb3, b))
                    if v3 < best then best = v3
    best

  def saveTable(file: File, vWhite: Array[Float], vBlack: Array[Float], force: Boolean = false): Unit =
    EgtbTable.save(file, dicechess.engine.domain.PieceType.Bishop, vWhite, vBlack, force = force)
