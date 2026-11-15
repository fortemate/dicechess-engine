// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite

private[search] object KingCaptureRootCastleCases:
  final case class Fixture(name: String, fen: String, flags: Int, kingMask: Int, queenMask: Int)

  private val white = List(
    Fixture("king-side King attack", "8/8/8/8/8/8/6k1/4K2R w K - 0 1", Move.KingCastle, 32, 0),
    Fixture("queen-side King attack", "8/8/8/8/8/8/1k6/R3K3 w Q - 0 1", Move.QueenCastle, 32, 0),
    Fixture("king-side Rook attack", "5k2/8/8/8/8/8/8/4K2R w K - 0 1", Move.KingCastle, 8, 0),
    Fixture("queen-side Rook attack", "3k4/8/8/8/8/8/8/R3K3 w Q - 0 1", Move.QueenCastle, 8, 0),
    Fixture("king-side removes old Rook attack", "7k/8/8/8/8/8/8/4K2R w K - 0 1", Move.KingCastle, 0, 0),
    Fixture("queen-side removes old Rook attack", "k7/8/8/8/8/8/8/R3K3 w Q - 0 1", Move.QueenCastle, 0, 0),
    Fixture("all six Queen capture types", "5q1k/8/8/3q4/3q4/2q1P3/QB4q1/1N2K2R w K - 0 1", Move.KingCastle, 0, 63)
  )

  private def mirror(fixture: Fixture): Fixture =
    val fields = fixture.fen.split(' ')
    val board  = fields(0).split('/').reverse.map(_.map(c => if c.isUpper then c.toLower else c.toUpper)).mkString("/")
    fixture.copy(name = s"mirrored ${fixture.name}", fen = s"$board b ${fields(2).toLowerCase} - 0 1")

  val cases: List[(Fixture, Color)] = white.flatMap(f => List((f, Color.White), (mirror(f), Color.Black)))

class KingCaptureRootCastleContractSuite extends FunSuite:
  private def mask(state: GameState, targets: Bitboard, attacker: Color): Int =
    (0 until 64)
      .map(Square.fromIndex)
      .filter(targets.contains)
      .foldLeft(0)((bits, square) => bits | AttackerTypes.mask(state, square, attacker))

  for (fixture, attacker) <- KingCaptureRootCastleCases.cases do
    test(fixture.name) {
      val state = FenParser.parse(fixture.fen).fold(fail(_), identity).withDicePool(List(6, 4, 1))
      val enemy = if attacker.isWhite then state.blackPieces else state.whitePieces
      val move  = MoveGenerator
        .generatePieceMoves(state, PieceType.King)
        .find(_.flags == fixture.flags)
        .getOrElse(fail("Missing characterized castle"))
      val after = state.makeMove(move)
      assertEquals(mask(after, state.kings & enemy, attacker), fixture.kingMask)
      assertEquals(mask(after, state.queens & enemy, attacker), fixture.queenMask)
      assertEquals(state.diceAfter(move).dicePool, List(1))
    }
