// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.{Bitboard, Square}
import dicechess.engine.movegen.{LeaperAttacks, MagicBitboards}
import java.io.{BufferedOutputStream, DataOutputStream, File, FileOutputStream}
import java.util.concurrent.{Callable, Executors}
import scala.jdk.CollectionConverters.*

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

  private val KingAttacks: Array[Long] =
    Array.tabulate(64)(sq => LeaperAttacks.kingAttacks(sq).value)

  @inline private def rookAttacks(r: Int, kw: Int, occ: Long): Long =
    val sq      = Square.fromIndex(r)
    val attacks = MagicBitboards.rookAttacks(sq, Bitboard(occ)).value
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

    for kw <- 0 until 64; kb <- 0 until 64; r <- 0 until 64 do
      val idx = stateIndex(kw, kb, r)
      if isLegal(kw, kb, r) then
        vWhiteCurrent(idx) = 0.85f
        vBlackCurrent(idx) = 0.80f

    val executor  = Executors.newFixedThreadPool(config.threads)
    var iteration = 0
    var maxDelta  = 1.0

    try
      while iteration < config.maxIterations && maxDelta > config.epsilon do
        iteration += 1

        val tasks = (0 until config.maxKw).map { kw =>
          new Callable[Double] {
            override def call(): Double =
              var localMaxDelta = 0.0

              for kb <- 0 until 64; r <- 0 until 64 do
                val idx = stateIndex(kw, kb, r)
                if isLegal(kw, kb, r) then
                  val newW   = evaluateWhiteTurn(kw, kb, r, vBlackCurrent, gamma)
                  val deltaW = math.abs(newW - vWhiteCurrent(idx))
                  vWhiteNext(idx) = newW
                  if deltaW > localMaxDelta then localMaxDelta = deltaW

                  val newB   = evaluateBlackTurn(kw, kb, r, vWhiteCurrent, gamma)
                  val deltaB = math.abs(newB - vBlackCurrent(idx))
                  vBlackNext(idx) = newB
                  if deltaB > localMaxDelta then localMaxDelta = deltaB

              localMaxDelta
          }
        }

        val futures = executor.invokeAll(tasks.asJava)
        maxDelta = futures.asScala.map(_.get()).max

        val tmpW = vWhiteCurrent; vWhiteCurrent = vWhiteNext; vWhiteNext = tmpW
        val tmpB = vBlackCurrent; vBlackCurrent = vBlackNext; vBlackNext = tmpB

        if iteration % 25 == 0 || maxDelta <= config.epsilon then
          val elapsed = System.currentTimeMillis() - startTime
          println(
            f"[EGTB KRvK] Iteration $iteration%3d | maxDelta = $maxDelta%.6f | elapsed = ${elapsed / 1000.0}%.2fs"
          )

    finally
      executor.shutdown()

    val elapsed = System.currentTimeMillis() - startTime

    var sumW        = 0.0
    var sumB        = 0.0
    var count       = 0
    var whiteWins   = 0
    var blackUpsets = 0

    for kw <- 0 until 64; kb <- 0 until 64; r <- 0 until 64 do
      val idx = stateIndex(kw, kb, r)
      if isLegal(kw, kb, r) then
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

  private def evaluateWhiteTurn(kw: Int, kb: Int, r: Int, vBlack: Array[Float], gamma: Float): Float =
    val occ = (1L << kw) | (1L << kb) | (1L << r)

    val val_0_0 = gamma * vBlack(stateIndex(kw, kb, r))
    val val_1_0 = bestKingMoves(kw, kb, r, 1, vBlack, gamma)
    val val_0_1 = bestRookMoves(kw, kb, r, 1, occ, vBlack, gamma)
    val val_2_0 = math.max(val_1_0, bestKingMoves(kw, kb, r, 2, vBlack, gamma))
    val val_0_2 = math.max(val_0_1, bestRookMoves(kw, kb, r, 2, occ, vBlack, gamma))
    val val_1_1 = math.max(math.max(val_1_0, val_0_1), bestKingAndRookMoves(kw, kb, r, occ, vBlack, gamma))
    val val_3_0 = math.max(val_2_0, bestKingMoves(kw, kb, r, 3, vBlack, gamma))
    val val_0_3 = math.max(val_0_2, bestRookMoves(kw, kb, r, 3, occ, vBlack, gamma))
    val val_2_1 = math.max(val_1_1, val_2_0)
    val val_1_2 = math.max(val_1_1, val_0_2)

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

  private def evaluateBlackTurn(kw: Int, kb: Int, r: Int, vWhite: Array[Float], gamma: Float): Float =
    val val_0 = gamma * vWhite(stateIndex(kw, kb, r))
    val val_1 = bestBlackKingMoves(kw, kb, r, 1, vWhite, gamma)
    val val_2 = math.min(val_1, bestBlackKingMoves(kw, kb, r, 2, vWhite, gamma))
    val val_3 = math.min(val_2, bestBlackKingMoves(kw, kb, r, 3, vWhite, gamma))

    (val_0 * 125 +
      val_1 * 75 +
      val_2 * 15 +
      val_3 * 1) / 216.0f

  private def bestKingMoves(kw: Int, kb: Int, r: Int, maxMoves: Int, vBlack: Array[Float], gamma: Float): Float =
    var best = 0.0f
    var b1   = KingAttacks(kw) & ~(1L << r)
    while b1 != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if kw1 == kb then return 1.0f
      else
        val v1 = gamma * vBlack(stateIndex(kw1, kb, r))
        if v1 > best then best = v1

        if maxMoves >= 2 then
          var b2 = KingAttacks(kw1) & ~(1L << r)
          while b2 != 0L do
            val kw2 = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if kw2 == kb then return 1.0f
            else
              val v2 = gamma * vBlack(stateIndex(kw2, kb, r))
              if v2 > best then best = v2

              if maxMoves >= 3 then
                var b3 = KingAttacks(kw2) & ~(1L << r)
                while b3 != 0L do
                  val kw3 = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if kw3 == kb then return 1.0f
                  else
                    val v3 = gamma * vBlack(stateIndex(kw3, kb, r))
                    if v3 > best then best = v3
    best

  private def bestRookMoves(
      kw: Int,
      kb: Int,
      r: Int,
      maxMoves: Int,
      occ: Long,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    var best = 0.0f
    var b1   = rookAttacks(r, kw, occ)
    while b1 != 0L do
      val r1 = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if r1 == kb then return 1.0f
      else
        val v1 = gamma * vBlack(stateIndex(kw, kb, r1))
        if v1 > best then best = v1

        if maxMoves >= 2 then
          val occ1 = (occ & ~(1L << r)) | (1L << r1)
          var b2   = rookAttacks(r1, kw, occ1)
          while b2 != 0L do
            val r2 = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if r2 == kb then return 1.0f
            else
              val v2 = gamma * vBlack(stateIndex(kw, kb, r2))
              if v2 > best then best = v2

              if maxMoves >= 3 then
                val occ2 = (occ1 & ~(1L << r1)) | (1L << r2)
                var b3   = rookAttacks(r2, kw, occ2)
                while b3 != 0L do
                  val r3 = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if r3 == kb then return 1.0f
                  else
                    val v3 = gamma * vBlack(stateIndex(kw, kb, r3))
                    if v3 > best then best = v3
    best

  private def bestKingAndRookMoves(kw: Int, kb: Int, r: Int, occ: Long, vBlack: Array[Float], gamma: Float): Float =
    var best = 0.0f

    // Branch A: King first, then Rook
    var bK = KingAttacks(kw) & ~(1L << r)
    while bK != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(bK)
      bK &= bK - 1
      if kw1 == kb then return 1.0f
      else
        val occ1 = (occ & ~(1L << kw)) | (1L << kw1)
        var bR   = rookAttacks(r, kw1, occ1)
        while bR != 0L do
          val r1 = java.lang.Long.numberOfTrailingZeros(bR)
          bR &= bR - 1
          if r1 == kb then return 1.0f
          else
            val v = gamma * vBlack(stateIndex(kw1, kb, r1))
            if v > best then best = v

    // Branch B: Rook first, then King
    var bR = rookAttacks(r, kw, occ)
    while bR != 0L do
      val r1 = java.lang.Long.numberOfTrailingZeros(bR)
      bR &= bR - 1
      if r1 == kb then return 1.0f
      else
        var bK2 = KingAttacks(kw) & ~(1L << r1)
        while bK2 != 0L do
          val kw1 = java.lang.Long.numberOfTrailingZeros(bK2)
          bK2 &= bK2 - 1
          if kw1 == kb then return 1.0f
          else
            val v = gamma * vBlack(stateIndex(kw1, kb, r1))
            if v > best then best = v

    best

  private def bestBlackKingMoves(kw: Int, kb: Int, r: Int, maxMoves: Int, vWhite: Array[Float], gamma: Float): Float =
    var best = 1.0f
    var b1   = KingAttacks(kb)
    while b1 != 0L do
      val kb1 = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if kb1 == kw then return 0.0f
      else if kb1 == r then
        if 0.5f < best then best = 0.5f
      else
        val v1 = gamma * vWhite(stateIndex(kw, kb1, r))
        if v1 < best then best = v1

        if maxMoves >= 2 then
          var b2 = KingAttacks(kb1)
          while b2 != 0L do
            val kb2 = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if kb2 == kw then return 0.0f
            else if kb2 == r then
              if 0.5f < best then best = 0.5f
            else
              val v2 = gamma * vWhite(stateIndex(kw, kb2, r))
              if v2 < best then best = v2

              if maxMoves >= 3 then
                var b3 = KingAttacks(kb2)
                while b3 != 0L do
                  val kb3 = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if kb3 == kw then return 0.0f
                  else if kb3 == r then
                    if 0.5f < best then best = 0.5f
                  else
                    val v3 = gamma * vWhite(stateIndex(kw, kb3, r))
                    if v3 < best then best = v3
    best

  def saveTable(file: File, vWhite: Array[Float], vBlack: Array[Float]): Unit =
    val out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))
    try
      out.writeBytes("EGTB")
      out.writeByte(1) // version
      out.writeByte(1) // type: 1 = KRvK
      out.writeShort(64)

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
