// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.{LeaperAttacks, MagicBitboards, MoveGenerator, PawnGeneration}
import scala.util.boundary, boundary.break

/** Probabilistic king (and queen) capture analysis for Dice Chess.
  *
  * Given a position, enumerates all 216 possible 3d6 outcomes (grouped into 56 weighted multisets) and determines
  * whether the opponent can capture the defender's king (or queen) on their next turn.
  *
  * King-capture paths are always legal regardless of the Maximum Micro-moves Rule, so a depth-first search with early
  * exit yields exact probabilities. Queen-capture paths respect the Maximum Micro-moves Rule; the returned probability
  * may therefore be slightly overestimated in edge cases where a queen capture is not part of any max-length sequence.
  */
object KingCaptureProbability:

  private inline val AllRollsMask  = 0x00ffffffffffffffL
  private inline val LightSquares  = 0x55aa55aa55aa55aaL
  private inline val DarkSquares   = 0xaa55aa55aa55aa55L
  private inline val WhitePromoSrc = 0x00ff000000000000L
  private inline val BlackPromoSrc = 0x000000000000ff00L

  // Static bitmask lookup tables over the 56 multisets in DiceRolls.weighted (bits 0..55).
  private val (
    weight1RollsMask: Long,
    weight3RollsMask: Long,
    weight6RollsMask: Long,
    rollsWithDieMask: IArray[Long],
    rollsWithFirstDie: IArray[Long],
    rollsWithPairAndMask: IArray[Long],
    rollsWithTwoDiceAndMask: IArray[Long],
    tripleRollBit: IArray[Long]
  ) = buildRollMaskTables()

  private def buildRollMaskTables(): (
      Long,
      Long,
      Long,
      IArray[Long],
      IArray[Long],
      IArray[Long],
      IArray[Long],
      IArray[Long]
  ) =
    var w1       = 0L
    var w3       = 0L
    var w6       = 0L
    val rollIdx  = new Array[Int](7 * 7 * 7)
    val byMask   = new Array[Long](64)
    val byFirst  = new Array[Long](7)
    val byPair   = new Array[Long](7 * 64)
    val byTwo    = new Array[Long](7 * 7 * 64)
    val byTriple = new Array[Long](7 * 7 * 7)

    var i = 0
    while i < DiceRolls.weighted.length do
      val (dice, weight) = DiceRolls.weighted(i)
      val bit            = 1L << i
      if weight == 1 then w1 |= bit
      else if weight == 3 then w3 |= bit
      else w6 |= bit
      val a = dice.head
      val b = dice.tail.head
      val c = dice.tail.tail.head
      rollIdx((a * 7 + b) * 7 + c) = i
      i += 1

    var d1 = 1
    while d1 <= 6 do
      val bit1 = 1 << (d1 - 1)
      var d2   = 1
      while d2 <= 6 do
        val bit2     = 1 << (d2 - 1)
        val pairIdx  = d1 * 7 + d2
        val pairMask = PairShift(pairIdx)
        var d3       = 1
        while d3 <= 6 do
          val bit3 = 1 << (d3 - 1)
          val s1   = math.min(d1, math.min(d2, d3))
          val s3   = math.max(d1, math.max(d2, d3))
          val s2   = d1 + d2 + d3 - s1 - s3
          val bit  = 1L << rollIdx((s1 * 7 + s2) * 7 + s3)
          byTriple(pairIdx * 7 + d3) = bit

          val all3Bits = bit1 | bit2 | bit3
          val rem2Bits = bit2 | bit3
          var m        = 1
          while m < 64 do
            if (m & all3Bits) != 0 then byMask(m) |= bit
            if (m & rem2Bits) != 0 then byPair((d1 << 6) | m) |= bit
            if (m & bit3) != 0 then byTwo(pairMask | m) |= bit
            m += 1
          d3 += 1
        d2 += 1
      d1 += 1

    var d = 1
    while d <= 6 do
      byFirst(d) = byMask(1 << (d - 1))
      d += 1

    (
      w1,
      w3,
      w6,
      IArray.unsafeFromArray(byMask),
      IArray.unsafeFromArray(byFirst),
      IArray.unsafeFromArray(byPair),
      IArray.unsafeFromArray(byTwo),
      IArray.unsafeFromArray(byTriple)
    )

  private inline def PairShift(pairIdx: Int): Int = pairIdx << 6

  private inline def solvedWeightSum(solvedMask: Long): Int =
    java.lang.Long.bitCount(solvedMask & weight1RollsMask) +
      java.lang.Long.bitCount(solvedMask & weight3RollsMask) * 3 +
      java.lang.Long.bitCount(solvedMask & weight6RollsMask) * 6

  /** Returns the probability `[0.0, 1.0]` that the opponent can capture the defender's king on their next turn. */
  def kingCaptureProbability(state: GameState, defenderColor: Color): Double =
    captureProbability(state, defenderColor, state.kings)

  /** Returns the probability `[0.0, 1.0]` that the opponent can capture a defender's queen on their next turn.
    *
    * This method applies the same DFS used for king capture and may slightly overestimate the true probability in edge
    * cases where a queen capture is not part of any max‑length micro‑move sequence (Maximum Micro‑moves Rule).
    */
  def queenCaptureProbability(state: GameState, defenderColor: Color): Double =
    captureProbability(state, defenderColor, state.queens)

  private def captureProbability(state: GameState, defenderColor: Color, targetBB: Bitboard): Double = boundary {
    val defenderPieces = if defenderColor.isWhite then state.whitePieces.value else state.blackPieces.value
    val targets        = targetBB.value & defenderPieces
    if targets == 0L then break(0.0)

    val opponent = defenderColor.opponent
    val isWhite  = opponent.isWhite
    val w0       = state.whitePieces.value
    val b0       = state.blackPieces.value
    val p0       = state.pawns.value
    val n0       = state.knights.value
    val bi0      = state.bishops.value
    val r0       = state.rooks.value
    val q0       = state.queens.value
    val k0       = state.kings.value
    val ep0      = state.enPassant.value
    val cr0      = state.flags.castlingRights

    val myPieces0    = if isWhite then w0 else b0
    val enemyPieces0 = if isWhite then b0 else w0
    if myPieces0 == 0L then break(0.0)

    val allPieces0       = w0 | b0
    val targetPawnMask   = PawnGeneration.anyAttacks(Bitboard(targets), defenderColor).value
    val targetKnightMask = unionKnightAttacks(targets)
    val targetKingMask   = unionKingAttacks(targets)
    val targetDiagMask   = unionDiagAttacks(targets, 0L)
    val targetOrthoMask  = unionOrthoAttacks(targets, 0L)
    val diagRay0         = unionDiagAttacks(targets, allPieces0)
    val orthoRay0        = unionOrthoAttacks(targets, allPieces0)

    val mask0 = computeCaptureMask(
      myPieces0,
      p0,
      n0,
      bi0,
      r0,
      q0,
      k0,
      targetPawnMask,
      targetKnightMask,
      diagRay0,
      orthoRay0,
      targetKingMask
    )
    var unsolved = AllRollsMask & ~rollsWithDieMask(mask0)
    if unsolved == 0L then break(1.0)

    val knightReach2Mask = unionKnightAttacks(targetKnightMask)
    val kingReach2Mask   = unionKingAttacks(targetKingMask)
    val moves0           = new Array[Int](256)
    val moves1           = new Array[Int](256)
    val enemyKings       = enemyPieces0 & k0

    var d1 = 1
    while d1 <= 6 && unsolved != 0L do
      val active0 = myPieces0 & selectTypeBB(d1, p0, n0, bi0, r0, q0, k0)
      if active0 != 0L && (unsolved & rollsWithFirstDie(d1)) != 0L then
        val count0 = MoveGenerator.generatePieceMovesInto(
          myPieces0,
          enemyPieces0,
          active0,
          enemyKings,
          myPieces0 & r0,
          ep0,
          cr0,
          isWhite,
          d1,
          moves0
        )
        var i0 = 0
        while i0 < count0 && (unsolved & rollsWithFirstDie(d1)) != 0L do
          val mv0   = moves0(i0)
          val flags = (mv0 >>> 12) & 0x0f
          if flags == Move.KingCastle || flags == Move.QueenCastle then
            unsolved = applyRootCastle(
              mv0,
              flags,
              isWhite,
              myPieces0,
              allPieces0,
              p0,
              n0,
              bi0,
              r0,
              q0,
              k0,
              targets,
              targetPawnMask,
              targetKnightMask,
              targetKingMask,
              diagRay0,
              orthoRay0,
              unsolved
            )
          else
            unsolved = exploreAfterRootMove(
              mv0,
              d1,
              isWhite,
              w0,
              b0,
              p0,
              n0,
              bi0,
              r0,
              q0,
              k0,
              ep0,
              enemyKings,
              targets,
              targetPawnMask,
              targetKnightMask,
              targetKingMask,
              targetDiagMask,
              targetOrthoMask,
              knightReach2Mask,
              kingReach2Mask,
              diagRay0,
              orthoRay0,
              moves1,
              unsolved
            )
          i0 += 1
      d1 += 1

    val solved = AllRollsMask & ~unsolved
    solvedWeightSum(solved).toDouble / DiceRolls.totalOrderedRolls
  }

  private inline def selectTypeBB(
      die: Int,
      p: Long,
      n: Long,
      bi: Long,
      r: Long,
      q: Long,
      k: Long
  ): Long =
    die match
      case 1 => p
      case 2 => n
      case 3 => bi
      case 4 => r
      case 5 => q
      case _ => k

  private inline def computeCaptureMask(
      myPieces: Long,
      p: Long,
      n: Long,
      bi: Long,
      r: Long,
      q: Long,
      k: Long,
      targetPawnMask: Long,
      targetKnightMask: Long,
      diagRay: Long,
      orthoRay: Long,
      targetKingMask: Long
  ): Int =
    var mask = 0
    if (myPieces & p & targetPawnMask) != 0L then mask |= 1
    if (myPieces & n & targetKnightMask) != 0L then mask |= 2
    if (myPieces & bi & diagRay) != 0L then mask |= 4
    if (myPieces & r & orthoRay) != 0L then mask |= 8
    if (myPieces & q & (diagRay | orthoRay)) != 0L then mask |= 16
    if (myPieces & k & targetKingMask) != 0L then mask |= 32
    mask

  private def unionKnightAttacks(squares: Long): Long =
    var acc = 0L
    var rem = squares
    while rem != 0L do
      val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(rem))
      acc |= LeaperAttacks.knightAttacksFor(sq).value
      rem &= rem - 1L
    acc

  private def unionKingAttacks(squares: Long): Long =
    var acc = 0L
    var rem = squares
    while rem != 0L do
      val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(rem))
      acc |= LeaperAttacks.kingAttacksFor(sq).value
      rem &= rem - 1L
    acc

  private def unionDiagAttacks(squares: Long, occupied: Long): Long =
    val occ = Bitboard(occupied)
    var acc = 0L
    var rem = squares
    while rem != 0L do
      val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(rem))
      acc |= MagicBitboards.bishopAttacks(sq, occ).value
      rem &= rem - 1L
    acc

  private def unionOrthoAttacks(squares: Long, occupied: Long): Long =
    val occ = Bitboard(occupied)
    var acc = 0L
    var rem = squares
    while rem != 0L do
      val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(rem))
      acc |= MagicBitboards.rookAttacks(sq, occ).value
      rem &= rem - 1L
    acc

  private def applyRootCastle(
      mv0: Int,
      flags: Int,
      isWhite: Boolean,
      myPieces0: Long,
      allPieces0: Long,
      p0: Long,
      n0: Long,
      bi0: Long,
      r0: Long,
      q0: Long,
      k0: Long,
      targets: Long,
      targetPawnMask: Long,
      targetKnightMask: Long,
      targetKingMask: Long,
      diagRay0: Long,
      orthoRay0: Long,
      unsolved: Long
  ): Long =
    val castlePairIdx = 6 * 7 + 4
    if (unsolved & rollsWithTwoDiceAndMask(PairShift(castlePairIdx) | 0x3f)) == 0L then unsolved
    else
      val toIdx      = mv0 & 0x3f
      val fromIdx    = (mv0 >>> 6) & 0x3f
      val kingFromTo = (1L << fromIdx) | (1L << toIdx)
      val rookFromTo =
        if flags == Move.KingCastle then if isWhite then 0xa0L else 0xa000000000000000L
        else if isWhite then 0x09L
        else 0x0900000000000000L
      val toggled    = kingFromTo | rookFromTo
      val myPieces1  = myPieces0 ^ toggled
      val allPieces1 = allPieces0 ^ toggled
      val k1         = k0 ^ kingFromTo
      val r1         = r0 ^ rookFromTo
      val diagRay1   = if (toggled & diagRay0) != 0L then unionDiagAttacks(targets, allPieces1) else diagRay0
      val orthoRay1  = if (toggled & orthoRay0) != 0L then unionOrthoAttacks(targets, allPieces1) else orthoRay0
      val mask1      = computeCaptureMask(
        myPieces1,
        p0,
        n0,
        bi0,
        r1,
        q0,
        k1,
        targetPawnMask,
        targetKnightMask,
        diagRay1,
        orthoRay1,
        targetKingMask
      )
      if mask1 != 0 then unsolved & ~rollsWithTwoDiceAndMask(PairShift(castlePairIdx) | mask1)
      else unsolved

  private def computeEp1(
      ep0: Long,
      fromIdx: Int,
      toIdx: Int,
      flags: Int,
      d1: Int,
      isWhite: Boolean,
      enemyPawns0: Long
  ): Long =
    if ep0 == 0L then 0L
    else
      val rankOffset = if isWhite then -8 else 8
      val toBB       = 1L << toIdx
      var res        = if flags == Move.EnPassantCapture then ep0 & ~toBB else ep0
      if flags != Move.DoublePawnPush && d1 == 1 then
        val passedIdx = fromIdx + rankOffset
        if passedIdx >= 0 && passedIdx < 64 then res &= ~(1L << passedIdx)
      if flags != Move.EnPassantCapture && (enemyPawns0 & toBB) != 0L then
        val passedIdx = toIdx - rankOffset
        if passedIdx >= 0 && passedIdx < 64 then res &= ~(1L << passedIdx)
      res

  private def exploreAfterRootMove(
      mv0: Int,
      d1: Int,
      isWhite: Boolean,
      w0: Long,
      b0: Long,
      p0: Long,
      n0: Long,
      bi0: Long,
      r0: Long,
      q0: Long,
      k0: Long,
      ep0: Long,
      enemyKings: Long,
      targets: Long,
      targetPawnMask: Long,
      targetKnightMask: Long,
      targetKingMask: Long,
      targetDiagMask: Long,
      targetOrthoMask: Long,
      knightReach2Mask: Long,
      kingReach2Mask: Long,
      diagRay0: Long,
      orthoRay0: Long,
      moves1: Array[Int],
      startUnsolved: Long
  ): Long =
    val toIdx        = mv0 & 0x3f
    val fromIdx      = (mv0 >>> 6) & 0x3f
    val flags        = (mv0 >>> 12) & 0x0f
    val fromBB       = 1L << fromIdx
    val toBB         = 1L << toIdx
    val fromToBB     = fromBB | toBB
    val notFromToBB  = ~fromToBB
    val notToBB      = ~toBB
    var w1           = w0
    var b1           = b0
    var p1           = p0
    var n1           = n0
    var bi1          = bi0
    var r1           = r0
    var q1           = q0
    var k1           = k0
    var changedSqBB1 = fromToBB

    if flags == Move.EnPassantCapture then
      val rankOffset = if isWhite then -8 else 8
      val victimBB   = 1L << (toIdx + rankOffset)
      changedSqBB1 |= victimBB
      w1 = if isWhite then w0 ^ fromToBB else w0 & ~victimBB
      b1 = if isWhite then b0 & ~victimBB else b0 ^ fromToBB
      p1 = (p0 & ~fromBB & ~victimBB) | toBB
    else if (flags & 8) != 0 then
      w1 = if isWhite then w0 ^ fromToBB else w0 & notToBB
      b1 = if isWhite then b0 & notToBB else b0 ^ fromToBB
      p1 = p0 & notFromToBB
      val promKind = flags & 3
      n1 = if promKind == 0 then (n0 & notToBB) | toBB else n0 & notToBB
      bi1 = if promKind == 1 then (bi0 & notToBB) | toBB else bi0 & notToBB
      r1 = if promKind == 2 then (r0 & notToBB) | toBB else r0 & notToBB
      q1 = if promKind == 3 then (q0 & notToBB) | toBB else q0 & notToBB
      k1 = k0 & notToBB
    else
      w1 = if isWhite then w0 ^ fromToBB else w0 & notToBB
      b1 = if isWhite then b0 & notToBB else b0 ^ fromToBB
      p1 = if d1 == 1 then (p0 & notFromToBB) | toBB else p0 & notToBB
      n1 = if d1 == 2 then (n0 & notFromToBB) | toBB else n0 & notToBB
      bi1 = if d1 == 3 then (bi0 & notFromToBB) | toBB else bi0 & notToBB
      r1 = if d1 == 4 then (r0 & notFromToBB) | toBB else r0 & notToBB
      q1 = if d1 == 5 then (q0 & notFromToBB) | toBB else q0 & notToBB
      k1 = if d1 == 6 then (k0 & notFromToBB) | toBB else k0 & notToBB

    val myPieces1    = if isWhite then w1 else b1
    val enemyPieces1 = if isWhite then b1 else w1
    val allPieces1   = w1 | b1
    val diagRay1     = if (changedSqBB1 & diagRay0) != 0L then unionDiagAttacks(targets, allPieces1) else diagRay0
    val orthoRay1    = if (changedSqBB1 & orthoRay0) != 0L then unionOrthoAttacks(targets, allPieces1) else orthoRay0

    val mask1 = computeCaptureMask(
      myPieces1,
      p1,
      n1,
      bi1,
      r1,
      q1,
      k1,
      targetPawnMask,
      targetKnightMask,
      diagRay1,
      orthoRay1,
      targetKingMask
    )
    var unsolved =
      if mask1 != 0 then startUnsolved & ~rollsWithPairAndMask((d1 << 6) | mask1)
      else startUnsolved

    if (unsolved & rollsWithFirstDie(d1)) != 0L then
      val ep1 = computeEp1(ep0, fromIdx, toIdx, flags, d1, isWhite, p0 & (if isWhite then b0 else w0))
      unsolved = exploreLevel1(
        d1,
        isWhite,
        myPieces1,
        enemyPieces1,
        allPieces1,
        p1,
        n1,
        bi1,
        r1,
        q1,
        k1,
        ep1,
        enemyKings,
        targets,
        targetPawnMask,
        targetKnightMask,
        targetKingMask,
        targetDiagMask,
        targetOrthoMask,
        knightReach2Mask,
        kingReach2Mask,
        diagRay1,
        orthoRay1,
        moves1,
        unsolved
      )
    unsolved

  private def computeNeededD3Mask(pairIdx: Int, unsolved: Long): Int =
    val rem = unsolved & rollsWithTwoDiceAndMask(PairShift(pairIdx) | 0x3f)
    if rem == 0L then 0
    else
      val base = pairIdx * 7
      var mask = 0
      if (rem & tripleRollBit(base + 1)) != 0L then mask |= 1
      if (rem & tripleRollBit(base + 2)) != 0L then mask |= 2
      if (rem & tripleRollBit(base + 3)) != 0L then mask |= 4
      if (rem & tripleRollBit(base + 4)) != 0L then mask |= 8
      if (rem & tripleRollBit(base + 5)) != 0L then mask |= 16
      if (rem & tripleRollBit(base + 6)) != 0L then mask |= 32
      mask

  private def exploreLevel1(
      d1: Int,
      isWhite: Boolean,
      myPieces1: Long,
      enemyPieces1: Long,
      allPieces1: Long,
      p1: Long,
      n1: Long,
      bi1: Long,
      r1: Long,
      q1: Long,
      k1: Long,
      ep1: Long,
      enemyKings: Long,
      targets: Long,
      targetPawnMask: Long,
      targetKnightMask: Long,
      targetKingMask: Long,
      targetDiagMask: Long,
      targetOrthoMask: Long,
      knightReach2Mask: Long,
      kingReach2Mask: Long,
      diagRay1: Long,
      orthoRay1: Long,
      moves1: Array[Int],
      startUnsolved: Long
  ): Long =
    var unsolved        = startUnsolved
    val promoSrcMask    = if isWhite then WhitePromoSrc else BlackPromoSrc
    val diagUnblockMask =
      (if (myPieces1 & bi1 & targetDiagMask) != 0L then 4 else 0) |
        (if (myPieces1 & q1 & targetDiagMask) != 0L then 16 else 0)
    val orthoUnblockMask =
      (if (myPieces1 & r1 & targetOrthoMask) != 0L then 8 else 0) |
        (if (myPieces1 & q1 & targetOrthoMask) != 0L then 16 else 0)
    val notMyPieces1 = ~myPieces1

    var d2 = 1
    while d2 <= 6 && (unsolved & rollsWithFirstDie(d1)) != 0L do
      val active1 = myPieces1 & selectTypeBB(d2, p1, n1, bi1, r1, q1, k1)
      if active1 != 0L then
        val pairIdx      = d1 * 7 + d2
        var neededD3Mask = computeNeededD3Mask(pairIdx, unsolved)
        if neededD3Mask != 0 then
          val selfBit = 1 << (d2 - 1)
          if (neededD3Mask & selfBit) != 0 then
            val hitSelf = canPieceReachAttackInOneMove(
              d2,
              active1,
              promoSrcMask,
              allPieces1,
              enemyPieces1,
              notMyPieces1,
              isWhite,
              targetPawnMask,
              targetKnightMask,
              targetKingMask,
              knightReach2Mask,
              kingReach2Mask,
              diagRay1,
              orthoRay1
            )
            if hitSelf then
              unsolved &= ~tripleRollBit(pairIdx * 7 + d2)
              neededD3Mask &= ~selfBit

          if neededD3Mask != 0 then
            var candidatePieces1 = 0L
            if (neededD3Mask & diagUnblockMask) != 0 then candidatePieces1 |= active1 & diagRay1
            if (neededD3Mask & orthoUnblockMask) != 0 then candidatePieces1 |= active1 & orthoRay1
            if d2 == 1 then
              if (neededD3Mask & 0x1e) != 0 then candidatePieces1 |= active1 & promoSrcMask
              if ep1 != 0L then candidatePieces1 |= active1

            if candidatePieces1 != 0L then
              val count1 = MoveGenerator.generatePieceMovesInto(
                myPieces1,
                enemyPieces1,
                candidatePieces1,
                enemyKings,
                0L,
                ep1,
                0,
                isWhite,
                d2,
                moves1
              )
              var i1 = 0
              while i1 < count1 && neededD3Mask != 0 do
                val mask2 = evaluateLevel2Move(
                  moves1(i1),
                  d2,
                  isWhite,
                  myPieces1,
                  allPieces1,
                  p1,
                  n1,
                  bi1,
                  r1,
                  q1,
                  k1,
                  targets,
                  targetPawnMask,
                  targetKnightMask,
                  targetKingMask,
                  diagRay1,
                  orthoRay1
                )
                val hit = mask2 & neededD3Mask
                if hit != 0 then
                  unsolved &= ~rollsWithTwoDiceAndMask(PairShift(pairIdx) | hit)
                  neededD3Mask &= ~hit
                i1 += 1
      d2 += 1
    unsolved

  private def canPieceReachAttackInOneMove(
      d2: Int,
      active1: Long,
      promoSrcMask: Long,
      allPieces1: Long,
      enemyPieces1: Long,
      notMyPieces1: Long,
      isWhite: Boolean,
      targetPawnMask: Long,
      targetKnightMask: Long,
      targetKingMask: Long,
      knightReach2Mask: Long,
      kingReach2Mask: Long,
      diagRay1: Long,
      orthoRay1: Long
  ): Boolean =
    d2 match
      case 1 => canPawnReach(active1 & ~promoSrcMask, ~allPieces1, enemyPieces1, isWhite, targetPawnMask)
      case 2 => canKnightReach(active1 & knightReach2Mask, targetKnightMask & notMyPieces1)
      case 3 => canBishopReach(active1, allPieces1, diagRay1 & notMyPieces1)
      case 4 => canRookReach(active1, allPieces1, orthoRay1 & notMyPieces1)
      case 5 => canQueenReach(active1, allPieces1, (diagRay1 | orthoRay1) & notMyPieces1)
      case _ => canKingReach(active1 & kingReach2Mask, targetKingMask & notMyPieces1)

  private def canPawnReach(
      nonPromoPawns: Long,
      emptySquares: Long,
      enemyPieces: Long,
      isWhite: Boolean,
      targetPawnMask: Long
  ): Boolean =
    if nonPromoPawns == 0L || targetPawnMask == 0L then false
    else
      val color   = if isWhite then Color.White else Color.Black
      val pawnsBB = Bitboard(nonPromoPawns)
      val emptyBB = Bitboard(emptySquares)
      val enemyBB = Bitboard(enemyPieces)
      val push1   = PawnGeneration.singlePushes(pawnsBB, emptyBB, color)
      val push2   = PawnGeneration.doublePushes(push1, emptyBB, color)
      val east    = PawnGeneration.eastCaptures(pawnsBB, enemyBB, color)
      val west    = PawnGeneration.westCaptures(pawnsBB, enemyBB, color)
      ((push1 | push2 | east | west).value & targetPawnMask) != 0L

  private def canKnightReach(knightsOnReach2: Long, validLandings: Long): Boolean =
    if knightsOnReach2 == 0L || validLandings == 0L then false
    else
      var p   = knightsOnReach2
      var hit = false
      while p != 0L && !hit do
        val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(p))
        if (LeaperAttacks.knightAttacksFor(sq).value & validLandings) != 0L then hit = true
        p &= p - 1L
      hit

  private def canKingReach(kingsOnReach2: Long, validLandings: Long): Boolean =
    if kingsOnReach2 == 0L || validLandings == 0L then false
    else
      var p   = kingsOnReach2
      var hit = false
      while p != 0L && !hit do
        val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(p))
        if (LeaperAttacks.kingAttacksFor(sq).value & validLandings) != 0L then hit = true
        p &= p - 1L
      hit

  private def canBishopReach(activeBishops: Long, allPieces: Long, validLandings: Long): Boolean =
    if validLandings == 0L then false
    else
      val colorMask =
        (if (validLandings & LightSquares) != 0L then LightSquares else 0L) |
          (if (validLandings & DarkSquares) != 0L then DarkSquares else 0L)
      var p   = activeBishops & colorMask
      val occ = Bitboard(allPieces)
      var hit = false
      while p != 0L && !hit do
        val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(p))
        if (MagicBitboards.bishopAttacks(sq, occ).value & validLandings) != 0L then hit = true
        p &= p - 1L
      hit

  private def canRookReach(activeRooks: Long, allPieces: Long, validLandings: Long): Boolean =
    if validLandings == 0L then false
    else
      val occ = Bitboard(allPieces)
      var p   = activeRooks
      var hit = false
      while p != 0L && !hit do
        val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(p))
        if (MagicBitboards.rookAttacks(sq, occ).value & validLandings) != 0L then hit = true
        p &= p - 1L
      hit

  private def canQueenReach(activeQueens: Long, allPieces: Long, validLandings: Long): Boolean =
    if validLandings == 0L then false
    else
      val occ = Bitboard(allPieces)
      var p   = activeQueens
      var hit = false
      while p != 0L && !hit do
        val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(p))
        if (MagicBitboards.queenAttacks(sq, occ).value & validLandings) != 0L then hit = true
        p &= p - 1L
      hit

  private def evaluateLevel2Move(
      mv1: Int,
      d2: Int,
      isWhite: Boolean,
      myPieces1: Long,
      allPieces1: Long,
      p1: Long,
      n1: Long,
      bi1: Long,
      r1: Long,
      q1: Long,
      k1: Long,
      targets: Long,
      targetPawnMask: Long,
      targetKnightMask: Long,
      targetKingMask: Long,
      diagRay1: Long,
      orthoRay1: Long
  ): Int =
    val toIdx        = mv1 & 0x3f
    val fromIdx      = (mv1 >>> 6) & 0x3f
    val flags        = (mv1 >>> 12) & 0x0f
    val fromBB       = 1L << fromIdx
    val toBB         = 1L << toIdx
    val fromToBB     = fromBB | toBB
    val notFromToBB  = ~fromToBB
    val notToBB      = ~toBB
    val myPieces2    = myPieces1 ^ fromToBB
    var allPieces2   = (allPieces1 & ~fromBB) | toBB
    var p2           = p1
    var n2           = n1
    var bi2          = bi1
    var r2           = r1
    var q2           = q1
    var k2           = k1
    var changedSqBB2 = fromToBB

    if flags == Move.EnPassantCapture then
      val rankOffset = if isWhite then -8 else 8
      val victimBB   = 1L << (toIdx + rankOffset)
      allPieces2 &= ~victimBB
      changedSqBB2 |= victimBB
      p2 = (p1 & ~fromBB & ~victimBB) | toBB
    else if (flags & 8) != 0 then
      p2 = p1 & notFromToBB
      val promKind = flags & 3
      n2 = if promKind == 0 then (n1 & notToBB) | toBB else n1 & notToBB
      bi2 = if promKind == 1 then (bi1 & notToBB) | toBB else bi1 & notToBB
      r2 = if promKind == 2 then (r1 & notToBB) | toBB else r1 & notToBB
      q2 = if promKind == 3 then (q1 & notToBB) | toBB else q1 & notToBB
      k2 = k1 & notToBB
    else
      p2 = if d2 == 1 then (p1 & notFromToBB) | toBB else p1 & notToBB
      n2 = if d2 == 2 then (n1 & notFromToBB) | toBB else n1 & notToBB
      bi2 = if d2 == 3 then (bi1 & notFromToBB) | toBB else bi1 & notToBB
      r2 = if d2 == 4 then (r1 & notFromToBB) | toBB else r1 & notToBB
      q2 = if d2 == 5 then (q1 & notFromToBB) | toBB else q1 & notToBB
      k2 = if d2 == 6 then (k1 & notFromToBB) | toBB else k1 & notToBB

    val diagRay2  = if (changedSqBB2 & diagRay1) != 0L then unionDiagAttacks(targets, allPieces2) else diagRay1
    val orthoRay2 = if (changedSqBB2 & orthoRay1) != 0L then unionOrthoAttacks(targets, allPieces2) else orthoRay1

    computeCaptureMask(
      myPieces2,
      p2,
      n2,
      bi2,
      r2,
      q2,
      k2,
      targetPawnMask,
      targetKnightMask,
      diagRay2,
      orthoRay2,
      targetKingMask
    )
