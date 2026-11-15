// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite

private[search] object KingCaptureLevel2TransitionCases:
  final case class Fixture(name: String, fen: String, from: Square, to: Square, flags: Int, mask: Int)

  private val white = List(
    Fixture("Pawn quiet", "8/8/8/8/3k4/8/4P3/6K1 w - - 0 1", Square('e', 2), Square('e', 3), Move.QuietMove, 1),
    Fixture(
      "Pawn double push",
      "8/8/8/3k4/8/8/4P3/6K1 w - - 0 1",
      Square('e', 2),
      Square('e', 4),
      Move.DoublePawnPush,
      1
    ),
    Fixture(
      "Pawn capture opens a Rook ray",
      "4k3/8/8/3n4/4P3/8/8/4R1K1 w - - 0 1",
      Square('e', 4),
      Square('d', 5),
      Move.Capture,
      8
    ),
    Fixture("Knight quiet", "8/8/8/2k5/8/8/8/N5K1 w - - 0 1", Square('a', 1), Square('b', 3), Move.QuietMove, 2),
    Fixture("Bishop quiet", "8/8/8/8/8/k7/8/B5K1 w - - 0 1", Square('a', 1), Square('b', 2), Move.QuietMove, 4),
    Fixture("Rook quiet", "4k3/8/8/8/8/8/R7/6K1 w - - 0 1", Square('a', 2), Square('a', 8), Move.QuietMove, 8),
    Fixture("Queen quiet", "4k3/8/8/8/8/8/Q7/6K1 w - - 0 1", Square('a', 2), Square('a', 8), Move.QuietMove, 16),
    Fixture("King quiet", "8/8/8/8/8/2k5/8/K7 w - - 0 1", Square('a', 1), Square('b', 2), Move.QuietMove, 32),
    Fixture(
      "En passant opens a Bishop ray",
      "8/5k2/8/3pP3/2B5/8/8/6K1 w - d6 0 1",
      Square('e', 5),
      Square('d', 6),
      Move.EnPassantCapture,
      4
    ),
    Fixture(
      "En passant opens a Rook ray",
      "8/8/8/R2pP2k/8/8/8/6K1 w - d6 0 1",
      Square('e', 5),
      Square('d', 6),
      Move.EnPassantCapture,
      8
    ),
    Fixture(
      "Knight promotion",
      "8/P1k5/8/8/8/8/8/6K1 w - - 0 1",
      Square('a', 7),
      Square('a', 8),
      Move.KnightPromotion,
      2
    ),
    Fixture(
      "Bishop promotion",
      "8/P7/8/8/4k3/8/8/6K1 w - - 0 1",
      Square('a', 7),
      Square('a', 8),
      Move.BishopPromotion,
      4
    ),
    Fixture("Rook promotion", "4k3/P7/8/8/8/8/8/6K1 w - - 0 1", Square('a', 7), Square('a', 8), Move.RookPromotion, 8),
    Fixture(
      "Queen promotion",
      "4k3/P7/8/8/8/8/8/6K1 w - - 0 1",
      Square('a', 7),
      Square('a', 8),
      Move.QueenPromotion,
      16
    ),
    Fixture(
      "Knight capture promotion",
      "1r6/P2k4/8/8/8/8/8/6K1 w - - 0 1",
      Square('a', 7),
      Square('b', 8),
      Move.KnightPromoCapture,
      2
    ),
    Fixture(
      "Bishop capture promotion",
      "1n6/P7/8/8/5k2/8/8/6K1 w - - 0 1",
      Square('a', 7),
      Square('b', 8),
      Move.BishopPromoCapture,
      4
    ),
    Fixture(
      "Rook capture promotion",
      "1b3k2/P7/8/8/8/8/8/6K1 w - - 0 1",
      Square('a', 7),
      Square('b', 8),
      Move.RookPromoCapture,
      8
    ),
    Fixture(
      "Queen replaces a captured Queen",
      "1q3k2/P7/8/8/8/8/8/6K1 w - - 0 1",
      Square('a', 7),
      Square('b', 8),
      Move.QueenPromoCapture,
      16
    )
  )

  private def mirror(fixture: Fixture): Fixture =
    val fields = fixture.fen.split(' ')
    val board  = fields(0).split('/').reverse.map(_.map(c => if c.isUpper then c.toLower else c.toUpper)).mkString("/")
    val ep     = if fields(3) == "-" then "-" else s"${fields(3).head}${9 - fields(3).last.asDigit}"
    fixture.copy(
      name = s"mirrored ${fixture.name}",
      fen = s"$board b - $ep 0 1",
      from = Square.fromIndex(fixture.from.index ^ 56),
      to = Square.fromIndex(fixture.to.index ^ 56)
    )

  val cases: List[(Fixture, Color)] = white.flatMap(f => List((f, Color.White), (mirror(f), Color.Black)))

class KingCaptureLevel2TransitionContractSuite extends FunSuite:
  for (fixture, attacker) <- KingCaptureLevel2TransitionCases.cases do
    test(fixture.name) {
      val state   = FenParser.parse(fixture.fen).fold(fail(_), identity)
      val enemy   = if attacker.isWhite then state.blackPieces else state.whitePieces
      val targets = state.kings & enemy
      assertEquals(java.lang.Long.bitCount(targets.value), 1)
      val target = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(targets.value))
      assertEquals(AttackerTypes.mask(state, target, attacker), AttackerTypes.Empty)
      val move = MoveGenerator
        .generateAllMoves(state)
        .find(m => m.fromSquare == fixture.from && m.toSquare == fixture.to && m.flags == fixture.flags)
        .getOrElse(fail("Missing characterized second move"))
      val die   = state.mailbox(move.fromSquare).pieceType.diceValue
      val after = state.withDicePool(List(die)).makeMove(move)
      assertEquals(AttackerTypes.mask(after, target, attacker), fixture.mask, fixture.fen)
    }
