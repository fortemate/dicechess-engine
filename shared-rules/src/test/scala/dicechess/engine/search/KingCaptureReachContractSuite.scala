// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite

private[search] object KingCaptureReachCases:
  final case class Fixture(name: String, fen: String, die: Int, reachesAttack: Boolean, counts: List[Int])

  private val white = List(
    Fixture("Pawn reaches an attack", "8/8/3k4/8/8/8/4P2P/8 w - - 0 1", 1, true, List(0, 0, 1, 0)),
    Fixture("Pawn stays out of range", "8/3k4/8/8/8/8/4P2P/8 w - - 0 1", 1, false, List(0, 0, 0, 0)),
    Fixture("Knight reaches an attack", "8/8/8/8/3k4/8/7P/N7 w - - 0 1", 2, true, List(0, 0, 16, 0)),
    Fixture("Knight stays out of range", "8/8/8/8/8/8/1k5P/N7 w - - 0 1", 2, false, List(0, 0, 0, 0)),
    Fixture("Bishop reaches an attack", "8/8/8/8/8/k7/7P/B7 w - - 0 1", 3, true, List(0, 0, 16, 0)),
    Fixture("Bishop stays on the wrong color", "8/8/8/8/8/1k6/7P/B7 w - - 0 1", 3, false, List(0, 0, 0, 0)),
    Fixture("Rook reaches an attack", "8/8/8/8/8/2k5/7P/R7 w - - 0 1", 4, true, List(0, 0, 16, 0)),
    Fixture("Rook stays blocked", "RK6/P7/8/8/5k2/8/7P/8 w - - 0 1", 4, false, List(0, 0, 3, 0)),
    Fixture("Queen reaches an attack", "8/8/8/8/2k5/8/7P/Q7 w - - 0 1", 5, true, List(0, 1, 16, 0)),
    Fixture("Queen stays blocked", "QK6/PP6/8/8/5k2/8/7P/8 w - - 0 1", 5, false, List(0, 0, 15, 0)),
    Fixture("King reaches an attack", "8/8/8/8/8/8/7P/K1k5 w - - 0 1", 6, true, List(16, 0, 16, 0)),
    Fixture("King stays out of range", "8/8/8/8/8/8/7P/K3k3 w - - 0 1", 6, false, List(0, 0, 0, 0))
  )

  val cases: List[(Fixture, Color)] = white.flatMap { fixture =>
    val board =
      fixture.fen.takeWhile(_ != ' ').split('/').reverse.map(_.map(c => if c.isUpper then c.toLower else c.toUpper))
    val mirrored = fixture.copy(
      name = s"mirrored ${fixture.name}",
      fen = s"${board.mkString("/")} b - - 0 1",
      counts = fixture.counts.drop(2) ++ fixture.counts.take(2)
    )
    List((fixture, Color.White), (mirrored, Color.Black))
  }

class KingCaptureReachContractSuite extends FunSuite:
  private def oriented(square: Square, attacker: Color): Square =
    if attacker.isWhite then square else Square.fromIndex(square.index ^ 56)

  for (fixture, attacker) <- KingCaptureReachCases.cases do
    test(fixture.name) {
      val state   = FenParser.parse(fixture.fen).fold(fail(_), identity).withDicePool(List(1, fixture.die, fixture.die))
      val enemy   = if attacker.isWhite then state.blackPieces else state.whitePieces
      val targets = state.kings & enemy
      assertEquals(java.lang.Long.bitCount(targets.value), 1)
      val target = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(targets.value))
      assertEquals(AttackerTypes.mask(state, target, attacker), 0)
      val (from, to) =
        if fixture.die == 1 then (Square('e', 2), Square('e', 4)) else (Square('h', 2), Square('h', 3))
      val root = MoveGenerator
        .generatePieceMoves(state, PieceType.Pawn)
        .find(move => move.fromSquare == oriented(from, attacker) && move.toSquare == oriented(to, attacker))
        .getOrElse(fail("Missing preparation Pawn move"))
      val continuation = state.makeMove(root)
      assertEquals(AttackerTypes.mask(continuation, target, attacker), 0)
      val kind    = PieceType.fromDice(fixture.die).getOrElse(fail("Invalid fixture die"))
      val reaches = MoveGenerator.generatePieceMoves(continuation, kind).filterNot(_.isCastling).exists { move =>
        val after = continuation.makeMove(move)
        (AttackerTypes.mask(after, target, attacker) & AttackerTypes.bit(kind)) != 0
      }
      assertEquals(reaches, fixture.reachesAttack, fixture.fen)
      val actual = List(
        KingCaptureProbability.kingCaptureProbability(state, Color.White),
        KingCaptureProbability.queenCaptureProbability(state, Color.White),
        KingCaptureProbability.kingCaptureProbability(state, Color.Black),
        KingCaptureProbability.queenCaptureProbability(state, Color.Black)
      )
      assertEquals(actual, fixture.counts.map(_.toDouble / 216), fixture.fen)
    }
