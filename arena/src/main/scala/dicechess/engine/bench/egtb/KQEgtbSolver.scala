// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.{Bitboard, Square}
import dicechess.engine.movegen.{LeaperAttacks, MagicBitboards}
import java.io.{BufferedOutputStream, DataOutputStream, File, FileOutputStream}
import java.util.concurrent.{Callable, Executors}
import scala.jdk.CollectionConverters.*

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

  final case class SolverConfig(
      discount: Double = 0.995,
      epsilon: Double = 1e-4,
      maxIterations: Int = 500,
      threads: Int = Runtime.getRuntime.availableProcessors(),
      maxKw: Int = 64
  )

  final case class SolverResult(
      iterations: Int,
      maxDelta: Double,
      elapsedMs: Long,
      vWhite: Array[Float],
      vBlack: Array[Float],
      avgWhiteValue: Double,
      avgBlackValue: Double,
      whiteWinCount: Int,
      blackUpsetCount: Int
  )

  def solve(config: SolverConfig = SolverConfig()): SolverResult =
    val startTime = System.currentTimeMillis()
    val gamma     = config.discount.toFloat

    var vWhiteCurrent = new Array[Float](StatesPerTurn)
    var vBlackCurrent = new Array[Float](StatesPerTurn)
    var vWhiteNext    = new Array[Float](StatesPerTurn)
    var vBlackNext    = new Array[Float](StatesPerTurn)

    // Initial estimate: 1.0 for White, assuming winning advantage
    for kw <- 0 until 64; kb <- 0 until 64; q <- 0 until 64 do
      val idx = stateIndex(kw, kb, q)
      if isLegal(kw, kb, q) then
        vWhiteCurrent(idx) = 0.90f
        vBlackCurrent(idx) = 0.85f

    val executor  = Executors.newFixedThreadPool(config.threads)
    var iteration = 0
    var maxDelta  = 1.0

    try
      while iteration < config.maxIterations && maxDelta > config.epsilon do
        iteration += 1

        // Parallel sweep over kw in [0, maxKw)
        val tasks = (0 until config.maxKw).map { kw =>
          new Callable[Double] {
            override def call(): Double =
              var localMaxDelta = 0.0

              for kb <- 0 until 64; q <- 0 until 64 do
                val idx = stateIndex(kw, kb, q)
                if isLegal(kw, kb, q) then
                  // 1. Evaluate White to move
                  val newW   = evaluateWhiteTurn(kw, kb, q, vBlackCurrent, gamma)
                  val deltaW = math.abs(newW - vWhiteCurrent(idx))
                  vWhiteNext(idx) = newW
                  if deltaW > localMaxDelta then localMaxDelta = deltaW

                  // 2. Evaluate Black to move
                  val newB   = evaluateBlackTurn(kw, kb, q, vWhiteCurrent, gamma)
                  val deltaB = math.abs(newB - vBlackCurrent(idx))
                  vBlackNext(idx) = newB
                  if deltaB > localMaxDelta then localMaxDelta = deltaB

              localMaxDelta
          }
        }

        val futures = executor.invokeAll(tasks.asJava)
        maxDelta = futures.asScala.map(_.get()).max

        // Swap buffers
        val tmpW = vWhiteCurrent; vWhiteCurrent = vWhiteNext; vWhiteNext = tmpW
        val tmpB = vBlackCurrent; vBlackCurrent = vBlackNext; vBlackNext = tmpB

        if iteration % 25 == 0 || maxDelta <= config.epsilon then
          val elapsed = System.currentTimeMillis() - startTime
          println(
            f"[EGTB KQvK] Iteration $iteration%3d | maxDelta = $maxDelta%.6f | elapsed = ${elapsed / 1000.0}%.2fs"
          )

    finally
      executor.shutdown()

    val elapsed = System.currentTimeMillis() - startTime

    // Compute stats over legal states
    var sumW        = 0.0
    var sumB        = 0.0
    var count       = 0
    var whiteWins   = 0
    var blackUpsets = 0

    for kw <- 0 until 64; kb <- 0 until 64; q <- 0 until 64 do
      val idx = stateIndex(kw, kb, q)
      if isLegal(kw, kb, q) then
        count += 1
        val w = vWhiteCurrent(idx)
        val b = vBlackCurrent(idx)
        sumW += w
        sumB += b
        if w >= 0.95f then whiteWins += 1
        if b < 0.60f then blackUpsets += 1

    SolverResult(
      iterations = iteration,
      maxDelta = maxDelta,
      elapsedMs = elapsed,
      vWhite = vWhiteCurrent,
      vBlack = vBlackCurrent,
      avgWhiteValue = if count > 0 then sumW / count else 0.0,
      avgBlackValue = if count > 0 then sumB / count else 0.0,
      whiteWinCount = whiteWins,
      blackUpsetCount = blackUpsets
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

  def saveTable(file: File, vWhite: Array[Float], vBlack: Array[Float]): Unit =
    val out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))
    try
      // Magic header: 4 bytes
      out.writeBytes("EGTB")
      out.writeByte(1)   // version
      out.writeByte(0)   // type: 0 = KQvK
      out.writeShort(64) // board size

      // Save as 16-bit fixed point: value in [0, 1] mapped to [0, 65535]
      // 262,144 * 2 bytes = 512 KB per turn -> 1 MB total!
      var i = 0
      while i < StatesPerTurn do
        val wFixed = (vWhite(i).min(1.0f).max(0.0f) * 65535.0f).round.toShort
        out.writeShort(wFixed)
        i += 1

      i = 0
      while i < StatesPerTurn do
        val bFixed = (vBlack(i).min(1.0f).max(0.0f) * 65535.0f).round.toShort
        out.writeShort(bFixed)
        i += 1

      println(s"Saved compressed EGTB to ${file.getAbsolutePath} (${file.length()} bytes)")
    finally out.close()
