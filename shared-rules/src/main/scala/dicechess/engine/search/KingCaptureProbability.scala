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

  private inline val AllRollsMask    = 0x00ffffffffffffffL
  private inline val CastlePairIndex = 6 * 7 + 4
  private inline val LightSquares    = 0x55aa55aa55aa55aaL
  private inline val DarkSquares     = 0xaa55aa55aa55aa55L
  private inline val WhitePromoSrc   = 0x00ff000000000000L
  private inline val BlackPromoSrc   = 0x000000000000ff00L

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
      var d2 = 1
      while d2 <= 6 do
        populateRollMasksForPair(d1, d2, rollIdx, byMask, byPair, byTwo, byTriple)
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

  private inline def populateRollMasksForPair(
      d1: Int,
      d2: Int,
      rollIdx: Array[Int],
      byMask: Array[Long],
      byPair: Array[Long],
      byTwo: Array[Long],
      byTriple: Array[Long]
  ): Unit =
    val bit1     = 1 << (d1 - 1)
    val bit2     = 1 << (d2 - 1)
    val pairIdx  = d1 * 7 + d2
    val pairMask = pairShift(pairIdx)
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

  private def pairShift(pairIdx: Int): Int = pairIdx << 6

  private def probabilityFromUnsolvedRolls(unsolvedMask: Long): Double =
    val solvedMask = AllRollsMask & ~unsolvedMask
    val weight     =
      java.lang.Long.bitCount(solvedMask & weight1RollsMask) +
        java.lang.Long.bitCount(solvedMask & weight3RollsMask) * 3 +
        java.lang.Long.bitCount(solvedMask & weight6RollsMask) * 6
    weight.toDouble / DiceRolls.totalOrderedRolls

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

  private def captureProbability(state: GameState, defenderColor: Color, targetBB: Bitboard): Double =
    val defenderPieces = if defenderColor.isWhite then state.whitePieces.value else state.blackPieces.value
    val targets        = targetBB.value & defenderPieces
    if targets == 0L then 0.0
    else
      val myPieces0 = if defenderColor.opponent.isWhite then state.whitePieces.value else state.blackPieces.value
      if myPieces0 == 0L then 0.0
      else captureTargetsProbability(state, defenderColor, targets, myPieces0)

  private def captureTargetsProbability(
      state: GameState,
      defenderColor: Color,
      targets: Long,
      myPieces0: Long
  ): Double = boundary {
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

    val enemyPieces0 = colorPieces(!isWhite, w0, b0)

    val allPieces0       = w0 | b0
    val targetPawnMask   = PawnGeneration.anyAttacks(Bitboard(targets), defenderColor).value
    val targetKnightMask = unionKnightAttacks(targets)
    val targetKingMask   = unionKingAttacks(targets)
    val targetDiagMask   = unionDiagAttacks(targets, 0L)
    val targetOrthoMask  = unionOrthoAttacks(targets, 0L)
    val diagRay0         = unionDiagAttacks(targets, allPieces0)
    val orthoRay0        = unionOrthoAttacks(targets, allPieces0)

    // Capture only immutable position scalars; pending rolls remain an explicit input.
    def applyRootCastle(mv0: Int, flags: Int, unsolved: Long): Long =
      clearCastleCaptureRolls(
        unsolved, {
          val toIdx      = mv0 & 0x3f
          val fromIdx    = (mv0 >>> 6) & 0x3f
          val kingFromTo = (1L << fromIdx) | (1L << toIdx)
          val rookFromTo = castleRookFromTo(flags, isWhite)
          val toggled    = kingFromTo | rookFromTo
          val myPieces1  = myPieces0 ^ toggled
          val allPieces1 = allPieces0 ^ toggled
          val k1         = k0 ^ kingFromTo
          val r1         = r0 ^ rookFromTo
          val diagRay1   = updatedAttackRay(toggled, diagRay0, unionDiagAttacks(targets, allPieces1))
          val orthoRay1  = updatedAttackRay(toggled, orthoRay0, unionOrthoAttacks(targets, allPieces1))
          computeCaptureMask(
            pawnHits = myPieces1 & p0 & targetPawnMask,
            knightHits = myPieces1 & n0 & targetKnightMask,
            bishopHits = myPieces1 & bi0 & diagRay1,
            rookHits = myPieces1 & r1 & orthoRay1,
            queenHits = myPieces1 & q0 & (diagRay1 | orthoRay1),
            kingHits = myPieces1 & k1 & targetKingMask
          )
        }
      )

    val mask0 = captureMaskFromHits(
      pawnHits = myPieces0 & p0 & targetPawnMask,
      knightHits = myPieces0 & n0 & targetKnightMask,
      bishopHits = myPieces0 & bi0 & diagRay0,
      rookHits = myPieces0 & r0 & orthoRay0,
      queenHits = myPieces0 & q0 & (diagRay0 | orthoRay0),
      kingHits = myPieces0 & k0 & targetKingMask
    )
    var unsolved = AllRollsMask & ~rollsWithDieMask(mask0)
    if unsolved == 0L then break(1.0)

    val knightReach2Mask = unionKnightAttacks(targetKnightMask)
    val kingReach2Mask   = unionKingAttacks(targetKingMask)
    val moves0           = new Array[Int](256)
    val moves1           = new Array[Int](256)
    val enemyKings       = enemyPieces0 & k0

    def exploreAfterRootMove(mv0: Int, d1: Int, startUnsolved: Long): Long =
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

      applyMoveKind(
        flags,
        onEnPassant = {
          val victimBB = enPassantVictim(toIdx, isWhite)
          changedSqBB1 |= victimBB
          w1 = movedColorBitboard(w0, isWhite, fromToBB, ~victimBB)
          b1 = movedColorBitboard(b0, !isWhite, fromToBB, ~victimBB)
          p1 = (p0 & ~fromBB & ~victimBB) | toBB
        },
        onPromotion = {
          w1 = movedColorBitboard(w0, isWhite, fromToBB, notToBB)
          b1 = movedColorBitboard(b0, !isWhite, fromToBB, notToBB)
          p1 = p0 & notFromToBB
          val promKind = flags & 3
          n1 = promotedTypeBitboard(n0, promKind == 0, notToBB, toBB)
          bi1 = promotedTypeBitboard(bi0, promKind == 1, notToBB, toBB)
          r1 = promotedTypeBitboard(r0, promKind == 2, notToBB, toBB)
          q1 = promotedTypeBitboard(q0, promKind == 3, notToBB, toBB)
          k1 = k0 & notToBB
        },
        onOrdinary = {
          w1 = movedColorBitboard(w0, isWhite, fromToBB, notToBB)
          b1 = movedColorBitboard(b0, !isWhite, fromToBB, notToBB)
          p1 = moveTypeBits(p0, d1 == 1, notFromToBB, notToBB, toBB)
          n1 = moveTypeBits(n0, d1 == 2, notFromToBB, notToBB, toBB)
          bi1 = moveTypeBits(bi0, d1 == 3, notFromToBB, notToBB, toBB)
          r1 = moveTypeBits(r0, d1 == 4, notFromToBB, notToBB, toBB)
          q1 = moveTypeBits(q0, d1 == 5, notFromToBB, notToBB, toBB)
          k1 = moveTypeBits(k0, d1 == 6, notFromToBB, notToBB, toBB)
        }
      )

      val myPieces1    = colorPieces(isWhite, w1, b1)
      val enemyPieces1 = colorPieces(!isWhite, w1, b1)
      val allPieces1   = w1 | b1
      val diagRay1     = updatedAttackRay(changedSqBB1, diagRay0, unionDiagAttacks(targets, allPieces1))
      val orthoRay1    = updatedAttackRay(changedSqBB1, orthoRay0, unionOrthoAttacks(targets, allPieces1))

      val mask1 = captureMaskFromHits(
        pawnHits = myPieces1 & p1 & targetPawnMask,
        knightHits = myPieces1 & n1 & targetKnightMask,
        bishopHits = myPieces1 & bi1 & diagRay1,
        rookHits = myPieces1 & r1 & orthoRay1,
        queenHits = myPieces1 & q1 & (diagRay1 | orthoRay1),
        kingHits = myPieces1 & k1 & targetKingMask
      )
      var unsolved = clearRootCaptureRolls(d1, startUnsolved, mask1)

      exploreIfNonEmpty(unsolved & rollsWithFirstDie(d1)) {
        val ep1 = computeEp1(ep0, fromIdx, toIdx, flags, d1, isWhite, p0 & colorPieces(!isWhite, w0, b0))
        def exploreLevel1(
            p1: Long,
            n1: Long,
            bi1: Long,
            r1: Long,
            q1: Long,
            k1: Long,
            startUnsolved: Long
        ): Long =
          var unsolved         = startUnsolved
          val promoSrcMask     = promotionSourceMask(isWhite)
          val diagUnblockMask  = unblockingTypes(myPieces1, bi1, q1, targetDiagMask, 4)
          val orthoUnblockMask = unblockingTypes(myPieces1, r1, q1, targetOrthoMask, 8)
          val notMyPieces1     = ~myPieces1

          def evaluateLevel2Move(mv1: Int, d2: Int): Int =
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

            applyMoveKind(
              flags,
              onEnPassant = {
                val rankOffset = enPassantRankOffset(isWhite)
                val victimBB   = 1L << (toIdx + rankOffset)
                allPieces2 &= ~victimBB
                changedSqBB2 |= victimBB
                p2 = (p1 & ~fromBB & ~victimBB) | toBB
              },
              onPromotion = {
                p2 = p1 & notFromToBB
                val promKind = flags & 3
                n2 = promotedTypeBitboard(n1, promKind == 0, notToBB, toBB)
                bi2 = promotedTypeBitboard(bi1, promKind == 1, notToBB, toBB)
                r2 = promotedTypeBitboard(r1, promKind == 2, notToBB, toBB)
                q2 = promotedTypeBitboard(q1, promKind == 3, notToBB, toBB)
                k2 = k1 & notToBB
              },
              onOrdinary = {
                p2 = movedTypeBitboard(p1, d2 == 1, notFromToBB, notToBB, toBB)
                n2 = movedTypeBitboard(n1, d2 == 2, notFromToBB, notToBB, toBB)
                bi2 = movedTypeBitboard(bi1, d2 == 3, notFromToBB, notToBB, toBB)
                r2 = movedTypeBitboard(r1, d2 == 4, notFromToBB, notToBB, toBB)
                q2 = movedTypeBitboard(q1, d2 == 5, notFromToBB, notToBB, toBB)
                k2 = movedTypeBitboard(k1, d2 == 6, notFromToBB, notToBB, toBB)
              }
            )

            val diagRay2  = updatedAttackRay(changedSqBB2, diagRay1, unionDiagAttacks(targets, allPieces2))
            val orthoRay2 = updatedAttackRay(changedSqBB2, orthoRay1, unionOrthoAttacks(targets, allPieces2))

            captureMaskFromHits(
              pawnHits = myPieces2 & p2 & targetPawnMask,
              knightHits = myPieces2 & n2 & targetKnightMask,
              bishopHits = myPieces2 & bi2 & diagRay2,
              rookHits = myPieces2 & r2 & orthoRay2,
              queenHits = myPieces2 & q2 & (diagRay2 | orthoRay2),
              kingHits = myPieces2 & k2 & targetKingMask
            )

          def canPieceReachAttackInOneMove(die: Int, active1: Long): Boolean =
            dispatchReachType(die)(
              canPawnReach(active1 & ~promoSrcMask, ~allPieces1, enemyPieces1, isWhite, targetPawnMask),
              canKnightReach(active1 & knightReach2Mask, targetKnightMask & notMyPieces1),
              canBishopReach(active1, allPieces1, diagRay1 & notMyPieces1),
              canRookReach(active1, allPieces1, orthoRay1 & notMyPieces1),
              canQueenReach(active1, allPieces1, (diagRay1 | orthoRay1) & notMyPieces1),
              canKingReach(active1 & kingReach2Mask, targetKingMask & notMyPieces1)
            )

          forEachPendingDie((unsolved & rollsWithFirstDie(d1)) != 0L) { d2 =>
            val active1 = myPieces1 & selectTypeBB(d2, p1, n1, bi1, r1, q1, k1)
            exploreIfNonEmpty(active1) {
              val pairIdx      = d1 * 7 + d2
              var neededD3Mask = computeNeededD3Mask(pairIdx, unsolved)
              exploreIfNonEmpty(neededD3Mask) {
                val selfBit = 1 << (d2 - 1)
                resolveSelfReach(
                  neededD3Mask,
                  selfBit,
                  canPieceReachAttackInOneMove(d2, active1), {
                    unsolved &= ~tripleRollBit(pairIdx * 7 + d2)
                    neededD3Mask &= ~selfBit
                  }
                )

                exploreIfNonEmpty(neededD3Mask) {
                  var candidatePieces1 = 0L
                  includeRayCandidates(neededD3Mask, diagUnblockMask) {
                    candidatePieces1 |= active1 & diagRay1
                  }
                  includeRayCandidates(neededD3Mask, orthoUnblockMask) {
                    candidatePieces1 |= active1 & orthoRay1
                  }
                  includePawnCandidates(
                    d2,
                    neededD3Mask,
                    ep1,
                    candidatePieces1 |= active1 & promoSrcMask,
                    candidatePieces1 |= active1
                  )

                  exploreCandidateMoves(candidatePieces1) {
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
                    forEachPendingMove(moves1, count1, neededD3Mask != 0) { mv1 =>
                      val mask2 = evaluateLevel2Move(mv1, d2)
                      val hit   = mask2 & neededD3Mask
                      exploreIfNonEmpty(hit) {
                        unsolved &= ~rollsWithTwoDiceAndMask(pairShift(pairIdx) | hit)
                        neededD3Mask &= ~hit
                      }
                    }
                  }
                }
              }
            }
          }
          unsolved

        unsolved = exploreLevel1(p1, n1, bi1, r1, q1, k1, unsolved)
      }
      unsolved

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
          if Move.fromInt(mv0).isCastling then unsolved = applyRootCastle(mv0, flags, unsolved)
          else unsolved = exploreAfterRootMove(mv0, d1, unsolved)
          i0 += 1
      d1 += 1

    probabilityFromUnsolvedRolls(unsolved)
  }

  private def selectTypeBB(
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

  private def computeCaptureMask(
      pawnHits: Long,
      knightHits: Long,
      bishopHits: Long,
      rookHits: Long,
      queenHits: Long,
      kingHits: Long
  ): Int =
    captureMaskFromHits(pawnHits, knightHits, bishopHits, rookHits, queenHits, kingHits)

  private inline def captureMaskFromHits(
      inline pawnHits: Long,
      inline knightHits: Long,
      inline bishopHits: Long,
      inline rookHits: Long,
      inline queenHits: Long,
      inline kingHits: Long
  ): Int =
    var mask = 0
    if pawnHits != 0L then mask |= 1
    if knightHits != 0L then mask |= 2
    if bishopHits != 0L then mask |= 4
    if rookHits != 0L then mask |= 8
    if queenHits != 0L then mask |= 16
    if kingHits != 0L then mask |= 32
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

  private inline def clearCastleCaptureRolls(inline unsolved: Long, inline captureMask: Int): Long =
    if noPendingCastleCapture(unsolved) then unsolved
    else clearCastleHits(unsolved, captureMask)

  private def noPendingCastleCapture(unsolved: Long): Boolean =
    (unsolved & rollsWithTwoDiceAndMask(pairShift(CastlePairIndex) | 0x3f)) == 0L

  private def clearCastleHits(unsolved: Long, mask: Int): Long =
    if mask != 0 then unsolved & ~rollsWithTwoDiceAndMask(pairShift(CastlePairIndex) | mask)
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
      if flags != Move.DoublePawnPush && d1 == 1 then res = clearEpSquare(res, fromIdx + rankOffset)
      if flags != Move.EnPassantCapture && (enemyPawns0 & toBB) != 0L then res = clearEpSquare(res, toIdx - rankOffset)
      res

  private inline def clearEpSquare(targets: Long, squareIdx: Int): Long =
    if squareIdx >= 0 && squareIdx < 64 then targets & ~(1L << squareIdx)
    else targets

  private def clearRootCaptureRolls(d1: Int, startUnsolved: Long, mask: Int): Long =
    if mask != 0 then startUnsolved & ~rollsWithPairAndMask((d1 << 6) | mask)
    else startUnsolved

  private def computeNeededD3Mask(pairIdx: Int, unsolved: Long): Int =
    val rem = unsolved & rollsWithTwoDiceAndMask(pairShift(pairIdx) | 0x3f)
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

  private inline def forEachPendingDie(inline hasPending: Boolean)(inline visit: Int => Unit): Unit =
    var die = 1
    while die <= 6 && hasPending do
      visit(die)
      die += 1

  private inline def forEachPendingMove(moves: Array[Int], count: Int, inline hasPending: Boolean)(
      inline visit: Int => Unit
  ): Unit =
    var index = 0
    while index < count && hasPending do
      visit(moves(index))
      index += 1

  private inline def dispatchReachType(die: Int)(
      inline pawn: Boolean,
      inline knight: Boolean,
      inline bishop: Boolean,
      inline rook: Boolean,
      inline queen: Boolean,
      inline king: Boolean
  ): Boolean =
    die match
      case 1 => pawn
      case 2 => knight
      case 3 => bishop
      case 4 => rook
      case 5 => queen
      case _ => king

  private def promotionSourceMask(isWhite: Boolean): Long =
    if isWhite then WhitePromoSrc else BlackPromoSrc

  private def unblockingTypes(
      myPieces: Long,
      sliderPieces: Long,
      queens: Long,
      alignmentMask: Long,
      sliderTypeBit: Int
  ): Int =
    (if (myPieces & sliderPieces & alignmentMask) != 0L then sliderTypeBit else 0) |
      (if (myPieces & queens & alignmentMask) != 0L then 16 else 0)

  private inline def includeRayCandidates(inline needed: Int, inline unblockTypes: Int)(inline include: Unit): Unit =
    if (needed & unblockTypes) != 0 then include

  private inline def includePawnCandidates(
      inline die: Int,
      inline needed: Int,
      inline ep: Long,
      inline includePromotion: Unit,
      inline includeEnPassant: Unit
  ): Unit =
    if die == 1 then
      if (needed & 0x1e) != 0 then includePromotion
      if ep != 0L then includeEnPassant

  private inline def exploreIfNonEmpty(inline active: Long)(inline explore: Unit): Unit =
    if active != 0L then explore

  private inline def resolveSelfReach(
      inline needed: Int,
      inline selfBit: Int,
      inline canReach: Boolean,
      inline clearRoll: Unit
  ): Unit =
    if (needed & selfBit) != 0 && canReach then clearRoll

  private inline def exploreCandidateMoves(inline candidates: Long)(inline explore: Unit): Unit =
    if candidates != 0L then explore

  private inline def exploreIfNonEmpty(inline hit: Int)(inline clearRolls: Unit): Unit =
    if hit != 0 then clearRolls

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

  private inline def applyMoveKind(
      inline flags: Int,
      inline onEnPassant: Unit,
      inline onPromotion: Unit,
      inline onOrdinary: Unit
  ): Unit =
    if flags == Move.EnPassantCapture then onEnPassant
    else if (flags & 8) != 0 then onPromotion
    else onOrdinary

  private def enPassantRankOffset(isWhite: Boolean): Int =
    if isWhite then -8 else 8

  private inline def updatedAttackRay(inline changed: Long, inline previous: Long, inline refreshed: Long): Long =
    if (changed & previous) != 0L then refreshed else previous

  private def castleRookFromTo(flags: Int, isWhite: Boolean): Long =
    if flags == Move.KingCastle then if isWhite then 0xa0L else 0xa000000000000000L
    else if isWhite then 0x09L
    else 0x0900000000000000L

  private def enPassantVictim(toIdx: Int, isWhite: Boolean): Long =
    val rankOffset = if isWhite then -8 else 8
    1L << (toIdx + rankOffset)

  private def colorPieces(isWhite: Boolean, white: Long, black: Long): Long =
    if isWhite then white else black

  private inline def movedColorBitboard(
      before: Long,
      inline isMovingColor: Boolean,
      fromToBB: Long,
      notCapturedBB: Long
  ): Long =
    if isMovingColor then before ^ fromToBB else before & notCapturedBB

  private def movedTypeBitboard(
      before: Long,
      isMover: Boolean,
      notFromToBB: Long,
      notToBB: Long,
      toBB: Long
  ): Long =
    moveTypeBits(before, isMover, notFromToBB, notToBB, toBB)

  private inline def moveTypeBits(
      before: Long,
      inline isMover: Boolean,
      notFromToBB: Long,
      notToBB: Long,
      toBB: Long
  ): Long =
    if isMover then (before & notFromToBB) | toBB else before & notToBB

  private def promotedTypeBitboard(
      before: Long,
      isPromotedType: Boolean,
      notToBB: Long,
      toBB: Long
  ): Long =
    if isPromotedType then (before & notToBB) | toBB else before & notToBB
