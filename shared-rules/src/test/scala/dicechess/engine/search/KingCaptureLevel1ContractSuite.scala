// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite

class KingCaptureLevel1ContractSuite extends FunSuite:

  private val WhiteUnblocks = "4R3/4N3/1B6/4K3/8/4k2Q/5P2/8 w - - 0 1"
  private val BlackUnblocks = "8/5p2/4K2q/8/4k3/1b6/4n3/4r3 b - - 0 1"
  private val PawnCompletes = "8/4k2Q/2NK4/8/5P1B/8/8/4R3 w - - 0 1"

  private val cases = List(
    ("White continuation resolves its remaining type before exhausting moves", WhiteUnblocks, List(16, 1, 210, 0)),
    ("Black continuation preserves the mirrored probabilities", BlackUnblocks, List(210, 0, 16, 1)),
    ("only triple Pawn needs a continuation", PawnCompletes, List(91, 1, 216, 0))
  )

  for (name, fen, counts) <- cases do
    test(name) {
      val state  = FenParser.parse(fen).fold(fail(_), identity)
      val actual = List(
        KingCaptureProbability.kingCaptureProbability(state, Color.White),
        KingCaptureProbability.queenCaptureProbability(state, Color.White),
        KingCaptureProbability.kingCaptureProbability(state, Color.Black),
        KingCaptureProbability.queenCaptureProbability(state, Color.Black)
      )
      assertEquals(actual, counts.map(_.toDouble / 216), fen)
    }

  test("fixtures expose the intended continuation paths") {
    val state  = FenParser.parse(WhiteUnblocks).fold(fail(_), identity).withDicePool(List(2, 6, 4))
    val target = Square('e', 3)
    assertEquals(AttackerTypes.mask(state, target, Color.White), 21)
    val knight = MoveGenerator.generatePieceMoves(state, PieceType.Knight).headOption.getOrElse(fail("No Knight move"))
    assertEquals(knight.toSquare, Square('d', 5))
    val afterKnight = state.makeMove(knight)
    assertEquals(AttackerTypes.mask(afterKnight, target, Color.White), 23)
    val kings = MoveGenerator.generatePieceMoves(afterKnight, PieceType.King)
    assert(kings.length > 1)
    val king = kings.headOption.getOrElse(fail("No King move"))
    assertEquals(king.toSquare, Square('d', 4))
    val afterKing = afterKnight.makeMove(king)
    assertEquals(AttackerTypes.mask(afterKing, target, Color.White) & AttackerTypes.bit(PieceType.Rook), 8)

    val pawnState = FenParser.parse(PawnCompletes).fold(fail(_), identity).withDicePool(List(1, 1, 1))
    assertEquals(AttackerTypes.mask(pawnState, Square('e', 7), Color.White), 62)
    val pawn = MoveGenerator.generatePieceMoves(pawnState, PieceType.Pawn).headOption.getOrElse(fail("No Pawn move"))
    assertEquals(pawn.toSquare, Square('f', 5))
    assertEquals(AttackerTypes.mask(pawnState.makeMove(pawn), Square('e', 7), Color.White), 62)
  }
