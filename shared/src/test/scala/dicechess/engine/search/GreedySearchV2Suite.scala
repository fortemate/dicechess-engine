package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class GreedySearchV2Suite extends FunSuite:

  test("GreedySearchV2 should reject material gain if it leaves the King exposed") {
    // White king on e1, White bishop on c3. Black rook on e8, Black queen on h8.
    // White rolls a bishop (3).
    // White can take the Queen (c3xh8), gaining 900 centipawns, but leaving the King on e1 exposed to the Rook on e8.
    // Alternatively, White can play Bishop e5 (c3-e5) to block the e-file and protect the King.
    // With KingExposurePenalty = 2000, blocking the check should score higher than taking the Queen.
    val fen   = "4r2q/8/8/8/8/2B5/8/4K3 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
      .withDicePool(List(3))

    val bestMoveOpt = GreedySearchV2.findBestMove(state)
    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get

    // The bot should choose c3-e5 to block the check, NOT c3xh8.
    val move = bestMove.moves.head
    assertEquals(move.fromSquare.toNotation, "c3")
    assertNotEquals(move.toSquare.toNotation, "h8", "Bot took the queen but left the king exposed")
    assertEquals(move.toSquare.toNotation, "e5", "Bot should block the check on the e-file")
  }
