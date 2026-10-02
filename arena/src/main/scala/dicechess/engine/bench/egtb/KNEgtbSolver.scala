// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.movegen.LeaperAttacks
import java.io.File

/** Stochastic Endgame Tablebase (EGTB) solver for King + Knight vs King (KNvK) in Dice Chess.
  *
  * Solves the Bellman value function V(s) in [0, 1] for all 524,288 states (64 x 64 x 64 x 2) via parallel Value
  * Iteration across all available CPU threads.
  */
object KNEgtbSolver:

  final val StatesPerTurn: Int = 64 * 64 * 64 // 262,144

  @inline def stateIndex(kw: Int, kb: Int, n: Int): Int =
    (kw << 12) | (kb << 6) | n

  @inline def isLegal(kw: Int, kb: Int, n: Int): Boolean =
    kw != kb && kw != n && kb != n

  private val KingAttacks: Array[Long] = EgtbSearch.KingAttacks

  private val KnightAttacks: Array[Long] =
    Array.tabulate(64)(sq => LeaperAttacks.knightAttacks(sq).value)

  type SolverConfig = EgtbConfig
  val SolverConfig = EgtbConfig

  type SolverResult = EgtbResult
  val SolverResult = EgtbResult

  def solve(config: SolverConfig = SolverConfig()): SolverResult =
    EgtbTable.runValueIteration(
      name = "KNvK",
      config = config,
      initWhite = 0.80f,
      initBlack = 0.75f,
      auxRange = 0 until 64,
      evalWhite = evaluateWhiteTurn,
      evalBlack = evaluateBlackTurn
    )

  private def evaluateWhiteTurn(kw: Int, kb: Int, n: Int, vBlack: Array[Float], gamma: Float): Float =
    val val00 = gamma * vBlack(stateIndex(kw, kb, n))
    val val10 = bestKingMoves(kw, kb, n, 1, vBlack, gamma)
    val val01 = bestKnightMoves(kw, kb, n, 1, vBlack, gamma)
    val val20 = math.max(val10, bestKingMoves(kw, kb, n, 2, vBlack, gamma))
    val val02 = math.max(val01, bestKnightMoves(kw, kb, n, 2, vBlack, gamma))
    val val11 = math.max(math.max(val10, val01), bestKingAndKnightMoves(kw, kb, n, vBlack, gamma))
    val val30 = math.max(val20, bestKingMoves(kw, kb, n, 3, vBlack, gamma))
    val val03 = math.max(val02, bestKnightMoves(kw, kb, n, 3, vBlack, gamma))
    // (2,1) [3/216] and (1,2) [3/216]: 3-dice outcomes mixing King and Knight moves (6/216 total probability).
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

  private def evaluateBlackTurn(kw: Int, kb: Int, n: Int, vWhite: Array[Float], gamma: Float): Float =
    val val0 = gamma * vWhite(stateIndex(kw, kb, n))
    val val1 = bestBlackKingMoves(kw, kb, n, 1, vWhite, gamma)
    val val2 = math.min(val1, bestBlackKingMoves(kw, kb, n, 2, vWhite, gamma))
    val val3 = math.min(val2, bestBlackKingMoves(kw, kb, n, 3, vWhite, gamma))

    (val0 * 125 +
      val1 * 75 +
      val2 * 15 +
      val3 * 1) / 216.0f

  @inline private def bestKingMoves(
      kw: Int,
      kb: Int,
      n: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    EgtbSearch.bestKingMoves(kw, kb, n, maxMoves, vBlack, gamma)

  private def bestKnightMoves(
      kw: Int,
      kb: Int,
      n: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    var best = 0.0f
    var b1   = KnightAttacks(n) & ~(1L << kw)
    while b1 != 0L do
      val n1Sq = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if n1Sq == kb then return 1.0f
      else
        val v1 = gamma * vBlack(stateIndex(kw, kb, n1Sq))
        if v1 > best then best = v1

        if maxMoves >= 2 then
          var b2 = KnightAttacks(n1Sq) & ~(1L << kw)
          while b2 != 0L do
            val n2Sq = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if n2Sq == kb then return 1.0f
            else
              val v2 = gamma * vBlack(stateIndex(kw, kb, n2Sq))
              if v2 > best then best = v2

              if maxMoves >= 3 then
                var b3 = KnightAttacks(n2Sq) & ~(1L << kw)
                while b3 != 0L do
                  val n3Sq = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if n3Sq == kb then return 1.0f
                  else
                    val v3 = gamma * vBlack(stateIndex(kw, kb, n3Sq))
                    if v3 > best then best = v3
    best

  private def bestKingAndKnightMoves(kw: Int, kb: Int, n: Int, vBlack: Array[Float], gamma: Float): Float =
    var best = 0.0f

    // Branch A: King first, then Knight
    var bK = KingAttacks(kw) & ~(1L << n)
    while bK != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(bK)
      bK &= bK - 1
      if kw1 == kb then return 1.0f
      else
        var bN = KnightAttacks(n) & ~(1L << kw1)
        while bN != 0L do
          val n1Sq = java.lang.Long.numberOfTrailingZeros(bN)
          bN &= bN - 1
          if n1Sq == kb then return 1.0f
          else
            val v = gamma * vBlack(stateIndex(kw1, kb, n1Sq))
            if v > best then best = v

    // Branch B: Knight first, then King
    var bN = KnightAttacks(n) & ~(1L << kw)
    while bN != 0L do
      val n1Sq = java.lang.Long.numberOfTrailingZeros(bN)
      bN &= bN - 1
      if n1Sq == kb then return 1.0f
      else
        var bK2 = KingAttacks(kw) & ~(1L << n1Sq)
        while bK2 != 0L do
          val kw1 = java.lang.Long.numberOfTrailingZeros(bK2)
          bK2 &= bK2 - 1
          if kw1 == kb then return 1.0f
          else
            val v = gamma * vBlack(stateIndex(kw1, kb, n1Sq))
            if v > best then best = v

    best

  @inline private def bestBlackKingMoves(
      kw: Int,
      kb: Int,
      n: Int,
      maxMoves: Int,
      vWhite: Array[Float],
      gamma: Float
  ): Float =
    EgtbSearch.bestBlackKingMoves(kw, kb, n, maxMoves, vWhite, gamma)

  def saveTable(file: File, vWhite: Array[Float], vBlack: Array[Float], force: Boolean = false): Unit =
    EgtbTable.save(file, dicechess.engine.domain.PieceType.Knight, vWhite, vBlack, force = force)
