// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.util.Random

class RandomKingCaptureSearchSuite extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)

  private def uci(moves: List[Move]): List[String] =
    moves.map(m => m.fromSquare.toNotation + m.toSquare.toNotation)

  test("always captures the king when the first micro-move can") {
    // The a8 king is under direct attack from the a1 rook; the h1 queen and others are tempting alternatives.
    val state = parse("k7/8/8/8/8/8/8/R6q w - - 0 1").withDicePool(List(4))
    for seed <- 0 until 50 do
      val result = RandomKingCaptureSearch.findBestMove(state, new Random(seed)).getOrElse(fail("no move"))
      assertEquals(result.score, SearchScoring.TerminalWinScore)
      assertEquals(uci(result.moves.toList), List("a1a8"), s"seed $seed declined the king")
  }

  test("captures the king for Black too, even when other dice give other moves") {
    val state = parse("r3k3/8/8/8/8/8/8/K7 b - - 0 1").withDicePool(List(4, 6, 6))
    for seed <- 0 until 50 do
      val result = RandomKingCaptureSearch.findBestMove(state, new Random(seed)).getOrElse(fail("no move"))
      assertEquals(result.score, SearchScoring.TerminalWinScore)
      assertEquals(uci(result.moves.toList).head, "a8a1", s"seed $seed declined the king")
  }

  test("does not look for a king capture that needs a preparatory micro-move") {
    // Only a2xb3 followed by Ra1xa8 wins (CheckmateAwareSearch finds it); no single move does.
    val state    = parse("k7/8/8/8/8/1n6/P7/R3K3 w - - 0 1").withDicePool(List(1, 4))
    val results  = (0 until 200).flatMap(seed => RandomKingCaptureSearch.findBestMove(state, new Random(seed)))
    val finishes = results.count(_.score == SearchScoring.TerminalWinScore)
    assert(finishes < results.size, "every seed found the two-move capture, so the bot is not random")
  }

  test("plays uniformly random paths when no king can be taken") {
    val state = parse("4k3/8/8/8/8/8/8/R3K3 w - - 0 1").withDicePool(List(4))
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    assert(paths.size > 1)
    val seen = (0 until 400).flatMap { seed =>
      RandomKingCaptureSearch.findBestMove(state, new Random(seed)).map(r => uci(r.moves.toList))
    }.toSet
    assertEquals(seen, paths.map(uci).toSet)
  }

  test("returns None when no legal move exists") {
    val state = parse("8/8/8/8/8/8/8/k6K w - - 0 1").withDicePool(List(1, 1))
    assert(RandomKingCaptureSearch.findBestMove(state).isEmpty)
  }
