// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.{Bitboard, Square}
import dicechess.engine.movegen.{LeaperAttacks, MagicBitboards}
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

  // Precomputed king attack masks (Long)
  private val KingAttacks: Array[Long] =
    Array.tabulate(64)(sq => LeaperAttacks.kingAttacks(sq).value)

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
    val val_0_0 = gamma * vBlack(stateIndex(kw, kb, q))

    // (1,0) [48/216]: 1 King move
    val val_1_0 = bestKingMoves(kw, kb, q, 1, vBlack, gamma)

    // (0,1) [48/216]: 1 Queen move
    val val_0_1 = bestQueenMoves(kw, kb, q, 1, occ, vBlack, gamma)

    // (2,0) [12/216]: up to 2 King moves
    val val_2_0 = math.max(val_1_0, bestKingMoves(kw, kb, q, 2, vBlack, gamma))

    // (0,2) [12/216]: up to 2 Queen moves
    val val_0_2 = math.max(val_0_1, bestQueenMoves(kw, kb, q, 2, occ, vBlack, gamma))

    // (1,1) [24/216]: 1 King + 1 Queen move in either order
    val val_1_1 = math.max(math.max(val_1_0, val_0_1), bestKingAndQueenMoves(kw, kb, q, occ, vBlack, gamma))

    // (3,0) [1/216]: up to 3 King moves
    val val_3_0 = math.max(val_2_0, bestKingMoves(kw, kb, q, 3, vBlack, gamma))

    // (0,3) [1/216]: up to 3 Queen moves
    val val_0_3 = math.max(val_0_2, bestQueenMoves(kw, kb, q, 3, occ, vBlack, gamma))

    // (2,1) [3/216]: 2 King + 1 Queen
    val val_2_1 = math.max(val_1_1, val_2_0)

    // (1,2) [3/216]: 1 King + 2 Queen
    val val_1_2 = math.max(val_1_1, val_0_2)

    val expectation =
      (val_0_0 * 64 +
        val_1_0 * 48 +
        val_0_1 * 48 +
        val_2_0 * 12 +
        val_0_2 * 12 +
        val_1_1 * 24 +
        val_3_0 * 1 +
        val_0_3 * 1 +
        val_2_1 * 3 +
        val_1_2 * 3) / 216.0f

    expectation

  // =========================================================================
  // Black Turn Evaluation (Black minimizes White win probability)
  // =========================================================================
  private def evaluateBlackTurn(kw: Int, kb: Int, q: Int, vWhite: Array[Float], gamma: Float): Float =
    // Black only has King.
    // nK in {0, 1, 2, 3}:
    // nK = 0 [125/216]: pass
    val val_0 = gamma * vWhite(stateIndex(kw, kb, q))

    // nK = 1 [75/216]: 1 King move
    val val_1 = bestBlackKingMoves(kw, kb, q, 1, vWhite, gamma)

    // nK = 2 [15/216]: up to 2 King moves
    val val_2 = math.min(val_1, bestBlackKingMoves(kw, kb, q, 2, vWhite, gamma))

    // nK = 3 [1/216]: up to 3 King moves
    val val_3 = math.min(val_2, bestBlackKingMoves(kw, kb, q, 3, vWhite, gamma))

    val expectation =
      (val_0 * 125 +
        val_1 * 75 +
        val_2 * 15 +
        val_3 * 1) / 216.0f

    expectation

  // --- Move Generators for White ---

  private def bestKingMoves(kw: Int, kb: Int, q: Int, maxMoves: Int, vBlack: Array[Float], gamma: Float): Float =
    var best = 0.0f
    var b1   = KingAttacks(kw) & ~(1L << q)
    while b1 != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if kw1 == kb then return 1.0f // Immediate win by king capture!
      else
        val v1 = gamma * vBlack(stateIndex(kw1, kb, q))
        if v1 > best then best = v1

        if maxMoves >= 2 then
          var b2 = KingAttacks(kw1) & ~(1L << q)
          while b2 != 0L do
            val kw2 = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if kw2 == kb then return 1.0f
            else
              val v2 = gamma * vBlack(stateIndex(kw2, kb, q))
              if v2 > best then best = v2

              if maxMoves >= 3 then
                var b3 = KingAttacks(kw2) & ~(1L << q)
                while b3 != 0L do
                  val kw3 = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if kw3 == kb then return 1.0f
                  else
                    val v3 = gamma * vBlack(stateIndex(kw3, kb, q))
                    if v3 > best then best = v3
    best

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

  private def bestBlackKingMoves(kw: Int, kb: Int, q: Int, maxMoves: Int, vWhite: Array[Float], gamma: Float): Float =
    var best = 1.0f // Black minimizes White's win probability!
    var b1   = KingAttacks(kb)
    while b1 != 0L do
      val kb1 = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if kb1 == kw then return 0.0f // Black captures White King -> Instant Black Win!
      else if kb1 == q then
        // Black captures White Queen -> Game becomes K vs K (Draw = 0.5)
        if 0.5f < best then best = 0.5f
      else
        val v1 = gamma * vWhite(stateIndex(kw, kb1, q))
        if v1 < best then best = v1

        if maxMoves >= 2 then
          var b2 = KingAttacks(kb1)
          while b2 != 0L do
            val kb2 = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if kb2 == kw then return 0.0f
            else if kb2 == q then
              if 0.5f < best then best = 0.5f
            else
              val v2 = gamma * vWhite(stateIndex(kw, kb2, q))
              if v2 < best then best = v2

              if maxMoves >= 3 then
                var b3 = KingAttacks(kb2)
                while b3 != 0L do
                  val kb3 = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if kb3 == kw then return 0.0f
                  else if kb3 == q then
                    if 0.5f < best then best = 0.5f
                  else
                    val v3 = gamma * vWhite(stateIndex(kw, kb3, q))
                    if v3 < best then best = v3
    best

  def saveTable(file: File, vWhite: Array[Float], vBlack: Array[Float], force: Boolean = false): Unit =
    EgtbTable.save(file, dicechess.engine.domain.PieceType.Queen, vWhite, vBlack, force = force)
