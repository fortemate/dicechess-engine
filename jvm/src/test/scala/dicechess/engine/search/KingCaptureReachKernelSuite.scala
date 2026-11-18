// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite

class KingCaptureReachKernelSuite extends FunSuite:
  private def evaluate(state: GameState, targets: Bitboard, attacker: Color, unsolved: Long): Long =
    KingCaptureLevel1KernelTestAccess.evaluate(state, targets, attacker, unsolved, new Array[Int](256))

  final private case class PreparedCase(
      fixture: KingCaptureReachCases.Fixture,
      attacker: Color,
      state: GameState,
      targets: Bitboard,
      pending: Long
  )

  private def prepare(fixture: KingCaptureReachCases.Fixture, attacker: Color): PreparedCase =
    val state   = FenParser.parse(fixture.fen).fold(fail(_), identity).withDicePool(List(1, fixture.die, fixture.die))
    val enemy   = if attacker.isWhite then state.blackPieces else state.whitePieces
    val targets = state.kings & enemy
    val (from, to) =
      if fixture.die == 1 then (Square('e', 2), Square('e', 4)) else (Square('h', 2), Square('h', 3))
    def oriented(square: Square): Square =
      if attacker.isWhite then square else Square.fromIndex(square.index ^ 56)
    val move = MoveGenerator
      .generatePieceMoves(state, PieceType.Pawn)
      .find(m => m.fromSquare == oriented(from) && m.toSquare == oriented(to))
      .getOrElse(fail("Missing preparation Pawn move"))
    val roll  = List(1, fixture.die, fixture.die).sorted
    val index = DiceRolls.weighted.indexWhere(_._1 == roll)
    assert(index >= 0, fixture.name)
    PreparedCase(fixture, attacker, state.makeMove(move), targets, 1L << index)

  test("each selected type clears exactly its isolated roll when it can reach an attack") {
    assertEquals(KingCaptureReachCases.cases.length, 24)
    for (fixture, attacker) <- KingCaptureReachCases.cases do
      val state   = FenParser.parse(fixture.fen).fold(fail(_), identity).withDicePool(List(1, fixture.die, fixture.die))
      val enemy   = if attacker.isWhite then state.blackPieces else state.whitePieces
      val targets = state.kings & enemy
      val (from, to) =
        if fixture.die == 1 then (Square('e', 2), Square('e', 4)) else (Square('h', 2), Square('h', 3))
      def oriented(square: Square): Square =
        if attacker.isWhite then square else Square.fromIndex(square.index ^ 56)
      val move = MoveGenerator
        .generatePieceMoves(state, PieceType.Pawn)
        .find(m => m.fromSquare == oriented(from) && m.toSquare == oriented(to))
        .getOrElse(fail("Missing preparation Pawn move"))
      val roll  = List(1, fixture.die, fixture.die).sorted
      val index = DiceRolls.weighted.indexWhere(_._1 == roll)
      assert(index >= 0, fixture.name)
      val bit      = 1L << index
      val expected = if fixture.reachesAttack then 0L else bit
      assertEquals(evaluate(state.makeMove(move), targets, attacker, bit), expected, fixture.name)
  }

  private val rollsWithoutRootDieOne =
    (0 until DiceRolls.weighted.length).foldLeft(0L) { (mask, index) =>
      if DiceRolls.weighted(index)._1.contains(1) then mask else mask | (1L << index)
    }

  test("unrelated roll bits survive isolated reach checks with a reused workspace") {
    val cases = KingCaptureReachCases.cases.map((fixture, attacker) => prepare(fixture, attacker))
    assertEquals(cases.length, 24)
    assertEquals(java.lang.Long.bitCount(rollsWithoutRootDieOne), 35)
    val moves   = new Array[Int](256)
    val forward = cases.map { current =>
      val actual = KingCaptureLevel1KernelTestAccess.evaluate(
        current.state,
        current.targets,
        current.attacker,
        current.pending | rollsWithoutRootDieOne,
        moves
      )
      val expected = (if current.fixture.reachesAttack then 0L else current.pending) | rollsWithoutRootDieOne
      assertEquals(actual, expected, current.fixture.name)
      actual
    }
    cases.reverse.zip(forward.reverse).foreach { (current, expectedForward) =>
      val actual = KingCaptureLevel1KernelTestAccess.evaluate(
        current.state,
        current.targets,
        current.attacker,
        current.pending | rollsWithoutRootDieOne,
        moves
      )
      val expected = (if current.fixture.reachesAttack then 0L else current.pending) | rollsWithoutRootDieOne
      assertEquals(actual, expected, current.fixture.name)
      assertEquals(actual, expectedForward, current.fixture.name)
    }
  }

  test("empty or unrelated pending rolls leave the caller workspace untouched") {
    val cases = KingCaptureReachCases.cases.map((fixture, attacker) => prepare(fixture, attacker))
    assertEquals(cases.length, 24)
    val moves    = Array.tabulate(256)(index => index ^ 0x5a5a5a5a)
    val sentinel = moves.toList
    cases.foreach { current =>
      assertEquals(
        KingCaptureLevel1KernelTestAccess.evaluate(current.state, current.targets, current.attacker, 0L, moves),
        0L,
        current.fixture.name
      )
      assertEquals(moves.toList, sentinel, current.fixture.name)
      assertEquals(
        KingCaptureLevel1KernelTestAccess.evaluate(
          current.state,
          current.targets,
          current.attacker,
          rollsWithoutRootDieOne,
          moves
        ),
        rollsWithoutRootDieOne,
        current.fixture.name
      )
      assertEquals(moves.toList, sentinel, current.fixture.name)
    }
  }
