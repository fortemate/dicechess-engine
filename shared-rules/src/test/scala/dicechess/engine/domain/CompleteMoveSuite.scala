// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite

/** Fused normal final-edge construction must equal the independently callable two-step composition (#456). */
class CompleteMoveSuite extends FunSuite:
  private val fixtures = List(
    FenParser.InitialPosition,
    "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 7 12",
    "r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 7 12",
    "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
    "4k3/8/8/8/3Pp3/8/8/4K3 b - d3 0 12",
    "k4r2/4P3/8/8/8/8/8/4K3 w - - 0 1",
    "4k3/8/8/8/8/8/4p3/K4R2 b - - 0 12"
  )

  test("normal final edges preserve all fields, mailbox and derived keys for both colors") {
    for fen <- fixtures do
      val initial = FenParser.parse(fen).fold(fail(_), identity).withDicePool(List(1, 4, 6))
      for counter <- List(0, 126, 127); cachedKey <- List(false, true) do
        val state = initial.copy(flags = initial.flags.withHalfMoveClock(counter), zobristKey = 0L)
        val input = if cachedKey then state.copy(zobristKey = state.zobristHash) else state
        val moves = MoveGenerator.generateMoves(input, MoveGenerator.computeEligibleEpPawns(input))
        assert(moves.nonEmpty)
        for move <- moves if !input.isKingCapture(move) do
          val expected = input.makeMove(move).endTurn()
          val actual   = input.makeMoveAndEndTurn(move)
          assertEquals(actual, expected, s"$fen $move")
          assertEquals(actual.zobristHash, expected.zobristHash)
          assertEquals(actual.zobristHash, actual.copy(zobristKey = 0L).zobristHash)
          assertEquals(actual.mailbox.toArray.toList, expected.mailbox.toArray.toList)
  }

  test("synthetic full EP masks preserve packed files while expiring only the incoming side's targets") {
    for color <- List(Color.White, Color.Black) do
      val base  = FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").fold(fail(_), identity).withActiveColor(color)
      val input = base.copy(enPassant = Bitboard(-1L), fullMoveNumber = Int.MaxValue, zobristKey = 0L)
      for move <- MoveGenerator.generateMoves(input.withDicePool(List(6)), Bitboard.empty) do
        val expected = input.makeMove(move).endTurn()
        val actual   = input.makeMoveAndEndTurn(move)
        assertEquals(actual, expected)
        assertEquals(actual.zobristHash, expected.zobristHash)
  }
