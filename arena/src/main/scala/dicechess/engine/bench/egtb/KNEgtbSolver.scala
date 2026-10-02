// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.movegen.LeaperAttacks
import java.io.{BufferedOutputStream, DataOutputStream, File, FileOutputStream}
import java.util.concurrent.{Callable, Executors}
import scala.jdk.CollectionConverters.*

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

  private val KingAttacks: Array[Long] =
    Array.tabulate(64)(sq => LeaperAttacks.kingAttacks(sq).value)

  private val KnightAttacks: Array[Long] =
    Array.tabulate(64)(sq => LeaperAttacks.knightAttacks(sq).value)

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

    for kw <- 0 until 64; kb <- 0 until 64; n <- 0 until 64 do
      val idx = stateIndex(kw, kb, n)
      if isLegal(kw, kb, n) then
        vWhiteCurrent(idx) = 0.80f
        vBlackCurrent(idx) = 0.75f

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

              for kb <- 0 until 64; n <- 0 until 64 do
                val idx = stateIndex(kw, kb, n)
                if isLegal(kw, kb, n) then
                  val newW   = evaluateWhiteTurn(kw, kb, n, vBlackCurrent, gamma)
                  val deltaW = math.abs(newW - vWhiteCurrent(idx))
                  vWhiteNext(idx) = newW
                  if deltaW > localMaxDelta then localMaxDelta = deltaW

                  val newB   = evaluateBlackTurn(kw, kb, n, vWhiteCurrent, gamma)
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
            f"[EGTB KNvK] Iteration $iteration%3d | maxDelta = $maxDelta%.6f | elapsed = ${elapsed / 1000.0}%.2fs"
          )

    finally
      executor.shutdown()

    val elapsed = System.currentTimeMillis() - startTime

    var sumW        = 0.0
    var sumB        = 0.0
    var count       = 0
    var whiteWins   = 0
    var blackUpsets = 0

    for kw <- 0 until 64; kb <- 0 until 64; n <- 0 until 64 do
      val idx = stateIndex(kw, kb, n)
      if isLegal(kw, kb, n) then
        count += 1
        val w    = vWhiteCurrent(idx)
        val bVal = vBlackCurrent(idx)
        sumW += w
        sumB += bVal
        if w >= 0.95f then whiteWins += 1
        if bVal < 0.60f then blackUpsets += 1

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

  private def evaluateWhiteTurn(kw: Int, kb: Int, n: Int, vBlack: Array[Float], gamma: Float): Float =
    val val_0_0 = gamma * vBlack(stateIndex(kw, kb, n))
    val val_1_0 = bestKingMoves(kw, kb, n, 1, vBlack, gamma)
    val val_0_1 = bestKnightMoves(kw, kb, n, 1, vBlack, gamma)
    val val_2_0 = math.max(val_1_0, bestKingMoves(kw, kb, n, 2, vBlack, gamma))
    val val_0_2 = math.max(val_0_1, bestKnightMoves(kw, kb, n, 2, vBlack, gamma))
    val val_1_1 = math.max(math.max(val_1_0, val_0_1), bestKingAndKnightMoves(kw, kb, n, vBlack, gamma))
    val val_3_0 = math.max(val_2_0, bestKingMoves(kw, kb, n, 3, vBlack, gamma))
    val val_0_3 = math.max(val_0_2, bestKnightMoves(kw, kb, n, 3, vBlack, gamma))
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

  private def evaluateBlackTurn(kw: Int, kb: Int, n: Int, vWhite: Array[Float], gamma: Float): Float =
    val val_0 = gamma * vWhite(stateIndex(kw, kb, n))
    val val_1 = bestBlackKingMoves(kw, kb, n, 1, vWhite, gamma)
    val val_2 = math.min(val_1, bestBlackKingMoves(kw, kb, n, 2, vWhite, gamma))
    val val_3 = math.min(val_2, bestBlackKingMoves(kw, kb, n, 3, vWhite, gamma))

    (val_0 * 125 +
      val_1 * 75 +
      val_2 * 15 +
      val_3 * 1) / 216.0f

  private def bestKingMoves(kw: Int, kb: Int, n: Int, maxMoves: Int, vBlack: Array[Float], gamma: Float): Float =
    var best = 0.0f
    var b1   = KingAttacks(kw) & ~(1L << n)
    while b1 != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if kw1 == kb then return 1.0f
      else
        val v1 = gamma * vBlack(stateIndex(kw1, kb, n))
        if v1 > best then best = v1

        if maxMoves >= 2 then
          var b2 = KingAttacks(kw1) & ~(1L << n)
          while b2 != 0L do
            val kw2 = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if kw2 == kb then return 1.0f
            else
              val v2 = gamma * vBlack(stateIndex(kw2, kb, n))
              if v2 > best then best = v2

              if maxMoves >= 3 then
                var b3 = KingAttacks(kw2) & ~(1L << n)
                while b3 != 0L do
                  val kw3 = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if kw3 == kb then return 1.0f
                  else
                    val v3 = gamma * vBlack(stateIndex(kw3, kb, n))
                    if v3 > best then best = v3
    best

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

  private def bestBlackKingMoves(kw: Int, kb: Int, n: Int, maxMoves: Int, vWhite: Array[Float], gamma: Float): Float =
    var best = 1.0f
    var b1   = KingAttacks(kb)
    while b1 != 0L do
      val kb1 = java.lang.Long.numberOfTrailingZeros(b1)
      b1 &= b1 - 1
      if kb1 == kw then return 0.0f
      else if kb1 == n then
        if 0.5f < best then best = 0.5f
      else
        val v1 = gamma * vWhite(stateIndex(kw, kb1, n))
        if v1 < best then best = v1

        if maxMoves >= 2 then
          var b2 = KingAttacks(kb1)
          while b2 != 0L do
            val kb2 = java.lang.Long.numberOfTrailingZeros(b2)
            b2 &= b2 - 1
            if kb2 == kw then return 0.0f
            else if kb2 == n then
              if 0.5f < best then best = 0.5f
            else
              val v2 = gamma * vWhite(stateIndex(kw, kb2, n))
              if v2 < best then best = v2

              if maxMoves >= 3 then
                var b3 = KingAttacks(kb2)
                while b3 != 0L do
                  val kb3 = java.lang.Long.numberOfTrailingZeros(b3)
                  b3 &= b3 - 1
                  if kb3 == kw then return 0.0f
                  else if kb3 == n then
                    if 0.5f < best then best = 0.5f
                  else
                    val v3 = gamma * vWhite(stateIndex(kw, kb3, n))
                    if v3 < best then best = v3
    best

  def saveTable(file: File, vWhite: Array[Float], vBlack: Array[Float]): Unit =
    val out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))
    try
      out.writeBytes("EGTB")
      out.writeByte(1) // version
      out.writeByte(3) // type: 3 = KNvK
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
