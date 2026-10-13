// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite

class KingThreatSuite extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)

  // Independent reference roll-by-roll pseudo-legal search, searching each of the 56 rolls on its own.
  private def reaches(state: GameState, target: Square, depth: Int): Boolean =
    val moves = MoveGenerator.generateMoves(state)
    if moves.exists(_.toSquare == target) then true
    else if depth <= 1 then false
    else
      moves.exists { move =>
        val survived = state.diceAfter(move)
        if survived.isValid && !survived.isDicePoolEmpty then
          val next = state.makeMove(move).withDiceSlotsOf(survived)
          reaches(next, target, depth - 1)
        else false
      }

  private def rollByRoll(state: GameState, target: Square, depth: Int): Int =
    var sum = 0
    var i   = 0
    while i < DiceRolls.weighted.length do
      val (dice, weight) = DiceRolls.weighted(i)
      val rolled         = state.withDicePool(dice)
      if reaches(rolled, target, depth) then sum += weight
      i += 1
    sum

  private val fixtures: List[(String, Square, Int, Int, Int)] = List(
    // (fen, target, firstAction, twoAction, threeAction)
    ("8/8/7p/p1p5/k5p1/5K2/n7/5b2 b - - 0 18", Square('f', 3), 91, 104, 104),
    ("r5r1/2pk2R1/p1nb4/1p4P1/NPP5/3PK3/P2BP3/R5N1 w - - 3 13", Square('d', 7), 91, 104, 105),
    ("rn1q1bnr/pppkpppp/8/3p1b2/8/5N2/PPPPPPPP/RNBQKB1R w KQ d6 2 2", Square('d', 7), 0, 16, 22),
    ("3rkb2/ppp3pp/3p2N1/4PP2/8/8/PPPK1PPP/RN5R b - - 1 7", Square('d', 2), 0, 30, 34),
    ("8/k2B4/4n3/8/1R5R/8/8/3K4 w - - 7 55", Square('a', 7), 0, 16, 16),
    ("1nb1k2r/3p1pp1/8/pBp1p1qp/8/b3PP2/PPPP3P/R1B1K2n w k h6 0 6", Square('e', 8), 0, 16, 16),
    ("8/8/7p/p1p4p/k1b5/5pP1/n2p4/4K3 w - - 0 16", Square('a', 4), 0, 0, 0)
  )

  test("the 56 distinct rolls coverage matches known fixtures for depth 1, 2, and 3") {
    for (fen, target, exp1, exp2, exp3) <- fixtures do
      val state = parse(fen)
      assertEquals(KingThreat.threatWeight(state, target, 1), exp1, s"$fen (N=1)")
      assertEquals(KingThreat.threatWeight(state, target, 2), exp2, s"$fen (N=2)")
      assertEquals(KingThreat.threatWeight(state, target, 3), exp3, s"$fen (N=3)")
  }

  test("initial position has zero threat against enemy king at all depths") {
    val state = parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    assertEquals(KingThreat.threatWeight(state, Square('e', 8), 1), 0)
    assertEquals(KingThreat.threatWeight(state, Square('e', 8), 2), 0)
    assertEquals(KingThreat.threatWeight(state, Square('e', 8), 3), 0)
  }

  test("returns zero when target square does not contain enemy king") {
    val state = parse("8/8/7p/p1p5/k5p1/5K2/n7/5b2 b - - 0 18")
    // e1 is empty
    assertEquals(KingThreat.threatWeight(state, Square('e', 1), 3), 0)
    // a4 is the friendly king
    assertEquals(KingThreat.threatWeight(state, Square('a', 4), 3), 0)
  }

  // Generates seeded positions from games played by picking legal turn paths
  private def generateSeededPositions(count: Int): List[(GameState, Square)] =
    var seed                 = 365L
    def nextRandom(): Double =
      seed = (seed * 1103515245L + 12345L) & 0x7fffffffL
      seed.toDouble / 0x7fffffffL

    val builder = List.newBuilder[(GameState, Square)]
    var total   = 0
    val start   = parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

    while total < count do
      var state = start
      var turn  = 0
      var alive = true
      while turn < 60 && total < count && alive do
        val enemyKingSq: Option[Square] =
          if state.activeColor.isWhite then
            val b = state.blackPieces & state.kings
            if b.isEmpty then None else Some(Square.fromIndex(java.lang.Long.numberOfTrailingZeros(b.value)))
          else
            val w = state.whitePieces & state.kings
            if w.isEmpty then None else Some(Square.fromIndex(java.lang.Long.numberOfTrailingZeros(w.value)))

        enemyKingSq.foreach { target =>
          builder += ((state, target))
          total += 1
        }

        val d1     = 1 + (nextRandom() * 6).toInt
        val d2     = 1 + (nextRandom() * 6).toInt
        val d3     = 1 + (nextRandom() * 6).toInt
        val rolled = state.withDicePool(List(d1, d2, d3))
        val paths  = TurnGenerator.generateAllLegalTurnPaths(rolled)
        if paths.isEmpty then alive = false
        else
          val chosenPath = paths((nextRandom() * paths.size).toInt)
          var curr       = rolled
          var p          = chosenPath
          while p.nonEmpty do
            curr = curr.makeMove(p.head)
            p = p.tail
          val next = curr.endTurn()
          val w    = next.whitePieces & next.kings
          val b    = next.blackPieces & next.kings
          if w.isEmpty || b.isEmpty then alive = false
          else state = next
        turn += 1

    builder.result()

  test("on at least 200 positions from seeded games, KingThreat agrees with roll-by-roll search for N=2 and N=3") {
    val positions = generateSeededPositions(200)
    assert(positions.size >= 200)

    var tested = 0
    for (state, target) <- positions do
      val weight2   = KingThreat.threatWeight(state, target, 2)
      val expected2 = rollByRoll(state, target, 2)
      assertEquals(weight2, expected2, s"N=2 mismatch at turn $tested, FEN: ${FenParser.serialize(state)}")

      val weight3   = KingThreat.threatWeight(state, target, 3)
      val expected3 = rollByRoll(state, target, 3)
      assertEquals(weight3, expected3, s"N=3 mismatch at turn $tested, FEN: ${FenParser.serialize(state)}")

      tested += 1
  }
