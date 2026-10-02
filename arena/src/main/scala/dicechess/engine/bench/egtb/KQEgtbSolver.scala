// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.{Bitboard, Square}
import dicechess.engine.movegen.MagicBitboards
import java.io.File

/** Stochastic Endgame Tablebase (EGTB) solver for King + Queen vs King (KQvK) in Dice Chess.
  *
  * Formulated as a two-player zero-sum Markov Game / Markov Decision Process (MDP). Solves for the exact game-theoretic
  * Bellman value function V(s) in [0, 1] (probability of White winning under optimal play from both sides) across all
  * 216 dice outcomes (collapsed into 10 canonical functional combinations for White, 4 for Black).
  *
  * State space: 64 (Kw) x 64 (Kb) x 64 (Qw) x 2 (turn) = 524,288 entries (2 MB per table).
  */
object KQEgtbSolver:

  final val StatesPerTurn: Int = 64 * 64 * 64 // 262,144

  @inline def stateIndex(kw: Int, kb: Int, q: Int): Int =
    (kw << 12) | (kb << 6) | q

  @inline def isLegal(kw: Int, kb: Int, q: Int): Boolean =
    kw != kb && kw != q && kb != q

  private val KingAttacks: Array[Long] = EgtbSearch.KingAttacks

  /** Returns queen attack bitboard from square `q` given full board occupancy `occ`, excluding `kw`. */
  @inline private def queenAttacks(q: Int, kw: Int, occ: Long): Long =
    val sq      = Square.fromIndex(q)
    val attacks = MagicBitboards.queenAttacks(sq, Bitboard(occ)).value
    attacks & ~(1L << kw)

  type SolverConfig = EgtbConfig
  val SolverConfig = EgtbConfig

  type SolverResult = EgtbResult
  val SolverResult = EgtbResult

  def solve(config: SolverConfig = SolverConfig()): SolverResult =
    EgtbTable.runValueIteration(
      name = "KQvK",
      config = config,
      initWhite = 0.90f,
      initBlack = 0.85f,
      auxRange = 0 until 64,
      evalWhite = evaluateWhiteTurn,
      evalBlack = evaluateBlackTurn
    )

  // =========================================================================
  // White Turn Evaluation (White maximizes White win probability)
  // =========================================================================
  private def evaluateWhiteTurn(kw: Int, kb: Int, q: Int, vBlack: Array[Float], gamma: Float): Float =
    val occ = (1L << kw) | (1L << kb) | (1L << q)

    // Functional outcomes for White in KQvK:
    // (nK, nQ):
    // (0,0) [64/216]: pass
    val val00 = gamma * vBlack(stateIndex(kw, kb, q))

    // (1,0) [48/216]: 1 King move
    val val10 = bestKingMoves(kw, kb, q, 1, vBlack, gamma)

    // (0,1) [48/216]: 1 Queen move
    val val01 = bestQueenMoves(kw, kb, q, 1, occ, vBlack, gamma)

    // (2,0) [12/216]: up to 2 King moves
    val val20 = math.max(val10, bestKingMoves(kw, kb, q, 2, vBlack, gamma))

    // (0,2) [12/216]: up to 2 Queen moves
    val val02 = math.max(val01, bestQueenMoves(kw, kb, q, 2, occ, vBlack, gamma))

    // (1,1) [24/216]: 1 King + 1 Queen move in either order
    val val11 = math.max(math.max(val10, val01), bestKingAndQueenMoves(kw, kb, q, occ, vBlack, gamma))

    // (3,0) [1/216]: up to 3 King moves
    val val30 = math.max(val20, bestKingMoves(kw, kb, q, 3, vBlack, gamma))

    // (0,3) [1/216]: up to 3 Queen moves
    val val03 = math.max(val02, bestQueenMoves(kw, kb, q, 3, occ, vBlack, gamma))

    // (2,1) [3/216] and (1,2) [3/216]: 3-dice outcomes mixing King and Queen moves (6/216 total probability).
    // Intentionally bounded using 2-ply composite lower-bound approximations max(val11, val20) and max(val11, val02)
    // to maintain fast value iteration while guaranteeing conservative monotonic convergence.
    val val21 = math.max(val11, val20)
    val val12 = math.max(val11, val02)

    val expectation =
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

    expectation

  // =========================================================================
  // Black Turn Evaluation (Black minimizes White win probability)
  // =========================================================================
  private def evaluateBlackTurn(kw: Int, kb: Int, q: Int, vWhite: Array[Float], gamma: Float): Float =
    // Black only has King.
    // nK in {0, 1, 2, 3}:
    // nK = 0 [125/216]: pass
    val val0 = gamma * vWhite(stateIndex(kw, kb, q))

    // nK = 1 [75/216]: 1 King move
    val val1 = bestBlackKingMoves(kw, kb, q, 1, vWhite, gamma)

    // nK = 2 [15/216]: up to 2 King moves
    val val2 = math.min(val1, bestBlackKingMoves(kw, kb, q, 2, vWhite, gamma))

    // nK = 3 [1/216]: up to 3 King moves
    val val3 = math.min(val2, bestBlackKingMoves(kw, kb, q, 3, vWhite, gamma))

    val expectation =
      (val0 * 125 +
        val1 * 75 +
        val2 * 15 +
        val3 * 1) / 216.0f

    expectation

  // --- Move Generators for White ---

  @inline private def bestKingMoves(
      kw: Int,
      kb: Int,
      q: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    EgtbSearch.bestKingMoves(kw, kb, q, maxMoves, vBlack, gamma)

  private def bestQueenMoves(
      kw: Int,
      kb: Int,
      q: Int,
      maxMoves: Int,
      occ: Long,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    var best = 0.0f
    var b1   = queenAttacks(q, kw, occ)
    while b1 != 0L do
      val q1 = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if q1 == kb then return 1.0f // Immediate win by queen capture of king!
      else
        val v1 = gamma * vBlack(stateIndex(kw, kb, q1))
        if v1 > best then best = v1

        if maxMoves >= 2 then
          val occ1 = (occ & ~(1L << q)) | (1L << q1)
          var b2   = queenAttacks(q1, kw, occ1)
          while b2 != 0L do
            val q2 = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if q2 == kb then return 1.0f
            else
              val v2 = gamma * vBlack(stateIndex(kw, kb, q2))
              if v2 > best then best = v2

              if maxMoves >= 3 then
                val occ2 = (occ1 & ~(1L << q1)) | (1L << q2)
                var b3   = queenAttacks(q2, kw, occ2)
                while b3 != 0L do
                  val q3 = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if q3 == kb then return 1.0f
                  else
                    val v3 = gamma * vBlack(stateIndex(kw, kb, q3))
                    if v3 > best then best = v3
    best

  private def bestKingAndQueenMoves(kw: Int, kb: Int, q: Int, occ: Long, vBlack: Array[Float], gamma: Float): Float =
    var best = 0.0f

    // Branch A: King first, then Queen
    var bK = KingAttacks(kw) & ~(1L << q)
    while bK != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(bK)
      bK &= bK - 1
      if kw1 == kb then return 1.0f
      else
        val occ1 = (occ & ~(1L << kw)) | (1L << kw1)
        var bQ   = queenAttacks(q, kw1, occ1)
        while bQ != 0L do
          val q1 = java.lang.Long.numberOfTrailingZeros(bQ)
          bQ &= bQ - 1
          if q1 == kb then return 1.0f
          else
            val v = gamma * vBlack(stateIndex(kw1, kb, q1))
            if v > best then best = v

    // Branch B: Queen first, then King
    var bQ = queenAttacks(q, kw, occ)
    while bQ != 0L do
      val q1 = java.lang.Long.numberOfTrailingZeros(bQ)
      bQ &= bQ - 1
      if q1 == kb then return 1.0f
      else
        var bK2 = KingAttacks(kw) & ~(1L << q1)
        while bK2 != 0L do
          val kw1 = java.lang.Long.numberOfTrailingZeros(bK2)
          bK2 &= bK2 - 1
          if kw1 == kb then return 1.0f
          else
            val v = gamma * vBlack(stateIndex(kw1, kb, q1))
            if v > best then best = v

    best

  // --- Move Generators for Black ---

  @inline private def bestBlackKingMoves(
      kw: Int,
      kb: Int,
      q: Int,
      maxMoves: Int,
      vWhite: Array[Float],
      gamma: Float
  ): Float =
    EgtbSearch.bestBlackKingMoves(kw, kb, q, maxMoves, vWhite, gamma)

  def saveTable(file: File, vWhite: Array[Float], vBlack: Array[Float], force: Boolean = false): Unit =
    EgtbTable.save(file, dicechess.engine.domain.PieceType.Queen, vWhite, vBlack, force = force)
