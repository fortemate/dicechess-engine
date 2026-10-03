// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import java.io.File

/** Stochastic Endgame Tablebase (EGTB) solver for King + Pawn vs King (KPvK) in Dice Chess.
  *
  * Uses the precomputed King + Queen vs King (KQvK) tablebase as the boundary condition for pawn promotion on rank 8.
  * Solves the Bellman value function V(s) in [0, 1] for all states via parallel Value Iteration.
  */
object KPEgtbSolver:

  final val StatesPerTurn: Int = 64 * 64 * 64 // 262,144

  @inline def stateIndex(kw: Int, kb: Int, p: Int): Int =
    (kw << 12) | (kb << 6) | p

  @inline def isLegal(kw: Int, kb: Int, p: Int): Boolean =
    val distinct  = kw != kb && kw != p && kb != p
    val validRank = p >= 8 && p <= 55
    distinct && validRank

  private val KingAttacks: Array[Long] = EgtbSearch.KingAttacks

  type SolverConfig = EgtbConfig
  val SolverConfig = EgtbConfig

  type SolverResult = EgtbResult
  val SolverResult = EgtbResult

  def solve(kqTable: EgtbTable, config: SolverConfig = SolverConfig()): SolverResult =
    EgtbTable.runValueIteration(
      name = "KPvK",
      config = config,
      initWhite = 0.70f,
      initBlack = 0.65f,
      auxRange = 8 to 55,
      evalWhite = (kw, kb, p, vB, g) => evaluateWhiteTurn(kw, kb, p, vB, g, kqTable),
      evalBlack = evaluateBlackTurn
    )

  private def evaluateWhiteTurn(
      kw: Int,
      kb: Int,
      p: Int,
      vBlack: Array[Float],
      gamma: Float,
      kqTable: EgtbTable
  ): Float =
    val val00 = gamma * vBlack(stateIndex(kw, kb, p))
    val val10 = bestKingMoves(kw, kb, p, 1, vBlack, gamma)
    val val01 = bestPawnMoves(kw, kb, p, 1, vBlack, gamma, kqTable)
    val val20 = math.max(val10, bestKingMoves(kw, kb, p, 2, vBlack, gamma))
    val val02 = math.max(val01, bestPawnMoves(kw, kb, p, 2, vBlack, gamma, kqTable))
    val val11 = math.max(math.max(val10, val01), bestKingAndPawnMoves(kw, kb, p, vBlack, gamma, kqTable))
    val val30 = math.max(val20, bestKingMoves(kw, kb, p, 3, vBlack, gamma))
    val val03 = math.max(val02, bestPawnMoves(kw, kb, p, 3, vBlack, gamma, kqTable))
    // (2,1) [3/216] and (1,2) [3/216]: 3-dice outcomes mixing King and Pawn moves (6/216 total probability).
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

  private def evaluateBlackTurn(kw: Int, kb: Int, p: Int, vWhite: Array[Float], gamma: Float): Float =
    val val0 = gamma * vWhite(stateIndex(kw, kb, p))
    val val1 = bestBlackKingMoves(kw, kb, p, 1, vWhite, gamma)
    val val2 = math.min(val1, bestBlackKingMoves(kw, kb, p, 2, vWhite, gamma))
    val val3 = math.min(val2, bestBlackKingMoves(kw, kb, p, 3, vWhite, gamma))

    (val0 * 125 +
      val1 * 75 +
      val2 * 15 +
      val3 * 1) / 216.0f

  @inline private def bestKingMoves(
      kw: Int,
      kb: Int,
      p: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    EgtbSearch.bestKingMoves(kw, kb, p, maxMoves, vBlack, gamma)

  private def canPawnCaptureKing(p: Int, kb: Int): Boolean =
    val leftCapture  = p % 8 > 0 && p + 7 == kb
    val rightCapture = p % 8 < 7 && p + 9 == kb
    leftCapture || rightCapture

  private def canDoublePush(kw: Int, kb: Int, p: Int): Boolean =
    if (p >> 3) != 1 then false
    else
      val step1Blocked = (p + 8 == kw) || (p + 8 == kb)
      val step2Blocked = (p + 16 == kw) || (p + 16 == kb)
      !step1Blocked && !step2Blocked

  private def evalPawnSquare(
      kw: Int,
      kb: Int,
      pSq: Int,
      vBlack: Array[Float],
      gamma: Float,
      kqTable: EgtbTable
  ): Float =
    if pSq >= 56 then gamma * kqTable.probe(kw, kb, pSq, false).getOrElse(0.85).toFloat
    else gamma * vBlack(stateIndex(kw, kb, pSq))

  private def searchPawnSinglePushes(
      kw: Int,
      kb: Int,
      curP: Int,
      movesLeft: Int,
      vBlack: Array[Float],
      gamma: Float,
      kqTable: EgtbTable
  ): Float =
    val nextP = curP + 8
    if nextP == kw || nextP == kb then 0.0f
    else
      val vStep = evalPawnSquare(kw, kb, nextP, vBlack, gamma, kqTable)
      if nextP >= 56 || movesLeft <= 1 then vStep
      else if canPawnCaptureKing(nextP, kb) then 1.0f
      else math.max(vStep, searchPawnSinglePushes(kw, kb, nextP, movesLeft - 1, vBlack, gamma, kqTable))

  private def evalDoublePush(
      kw: Int,
      kb: Int,
      p: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float
  ): Float =
    val step2 = p + 16
    var v     = gamma * vBlack(stateIndex(kw, kb, step2))
    if maxMoves >= 2 then
      if canPawnCaptureKing(step2, kb) then return 1.0f
      val p3 = step2 + 8
      if p3 != kw && p3 != kb then
        val v3 = gamma * vBlack(stateIndex(kw, kb, p3))
        if v3 > v then v = v3
    v

  private def bestPawnMoves(
      kw: Int,
      kb: Int,
      p: Int,
      maxMoves: Int,
      vBlack: Array[Float],
      gamma: Float,
      kqTable: EgtbTable
  ): Float =
    if canPawnCaptureKing(p, kb) then return 1.0f

    var best  = 0.0f
    var moved = false

    val p1 = p + 8
    if p1 != kw && p1 != kb then
      moved = true
      val vSingle = searchPawnSinglePushes(kw, kb, p, maxMoves, vBlack, gamma, kqTable)
      if vSingle == 1.0f then return 1.0f
      if vSingle > best then best = vSingle

    if canDoublePush(kw, kb, p) then
      moved = true
      val vDouble = evalDoublePush(kw, kb, p, maxMoves, vBlack, gamma)
      if vDouble == 1.0f then return 1.0f
      if vDouble > best then best = vDouble

    if moved then best else gamma * vBlack(stateIndex(kw, kb, p))

  private def evalPawnAfterKing(
      kw1: Int,
      kb: Int,
      p: Int,
      vBlack: Array[Float],
      gamma: Float,
      kqTable: EgtbTable
  ): Float =
    if canPawnCaptureKing(p, kb) then return 1.0f

    var best      = 0.0f
    var pawnMoved = false

    val p1 = p + 8
    if p1 != kw1 && p1 != kb then
      pawnMoved = true
      val v = evalPawnSquare(kw1, kb, p1, vBlack, gamma, kqTable)
      if v > best then best = v

    if canDoublePush(kw1, kb, p) then
      pawnMoved = true
      val v = gamma * vBlack(stateIndex(kw1, kb, p + 16))
      if v > best then best = v

    if pawnMoved then best else gamma * vBlack(stateIndex(kw1, kb, p))

  private def bestKingThenPawnMoves(
      kw: Int,
      kb: Int,
      p: Int,
      vBlack: Array[Float],
      gamma: Float,
      kqTable: EgtbTable
  ): Float =
    var best = 0.0f
    var bK   = KingAttacks(kw) & ~(1L << p)
    while bK != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(bK)
      bK &= bK - 1
      if kw1 == kb then return 1.0f
      val v = evalPawnAfterKing(kw1, kb, p, vBlack, gamma, kqTable)
      if v == 1.0f then return 1.0f
      if v > best then best = v
    best

  private def searchKingAfterPawn(
      kw: Int,
      kb: Int,
      pNext: Int,
      vBlack: Array[Float],
      gamma: Float,
      kqTable: EgtbTable
  ): Float =
    var best = 0.0f
    var bK2  = KingAttacks(kw) & ~(1L << pNext)
    while bK2 != 0L do
      val kw1 = java.lang.Long.numberOfTrailingZeros(bK2)
      bK2 &= bK2 - 1
      if kw1 == kb then return 1.0f
      val v = evalPawnSquare(kw1, kb, pNext, vBlack, gamma, kqTable)
      if v > best then best = v
    best

  private def bestPawnThenKingMoves(
      kw: Int,
      kb: Int,
      p: Int,
      vBlack: Array[Float],
      gamma: Float,
      kqTable: EgtbTable
  ): Float =
    if canPawnCaptureKing(p, kb) then return 1.0f

    var best = 0.0f
    val p1   = p + 8
    if p1 != kw && p1 != kb then
      val v1 = searchKingAfterPawn(kw, kb, p1, vBlack, gamma, kqTable)
      if v1 == 1.0f then return 1.0f
      if v1 > best then best = v1

    if canDoublePush(kw, kb, p) then
      val v2 = searchKingAfterPawn(kw, kb, p + 16, vBlack, gamma, kqTable)
      if v2 == 1.0f then return 1.0f
      if v2 > best then best = v2

    best

  private def bestKingAndPawnMoves(
      kw: Int,
      kb: Int,
      p: Int,
      vBlack: Array[Float],
      gamma: Float,
      kqTable: EgtbTable
  ): Float =
    val valA = bestKingThenPawnMoves(kw, kb, p, vBlack, gamma, kqTable)
    if valA == 1.0f then 1.0f
    else math.max(valA, bestPawnThenKingMoves(kw, kb, p, vBlack, gamma, kqTable))

  @inline private def bestBlackKingMoves(
      kw: Int,
      kb: Int,
      p: Int,
      maxMoves: Int,
      vWhite: Array[Float],
      gamma: Float
  ): Float =
    EgtbSearch.bestBlackKingMoves(kw, kb, p, maxMoves, vWhite, gamma)

  def saveTable(file: File, vWhite: Array[Float], vBlack: Array[Float], force: Boolean = false): Unit =
    EgtbTable.save(file, dicechess.engine.domain.PieceType.Pawn, vWhite, vBlack, force = force)
