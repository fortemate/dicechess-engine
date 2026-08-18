package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Regression tests for the Maximum Micro-moves Rule and castling (issue #347).
  *
  * Castling is a single move that spends two dice (King + Rook). Turn maximality must therefore be measured in dice
  * consumed — not in move count — otherwise a castling turn is discarded in favor of a longer non-castling path that
  * spends the same dice.
  */
class TurnGeneratorSuite extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)

  private def diceConsumed(path: List[Move]): Int =
    path.foldLeft(0)((acc, m) => acc + (if m.isCastling then 2 else 1))

  private def hasCastlingPath(paths: List[List[Move]]): Boolean =
    paths.exists(p => p.headOption.exists(_.isCastling))

  test("retains king-side castling (1 move, 2 dice) alongside a 2-move King+Rook path") {
    // White king e1, rook h1, king-side right. Castle spends King+Rook in one move; moving the king
    // then the rook spends the same two dice across two moves. The old move-count rule dropped the
    // shorter castling path.
    val state = parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1").withDicePool(List(6, 4))
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    assert(hasCastlingPath(paths), "king-side castling path was filtered out as non-maximal")
  }

  test("retains king-side castling when a longer same-dice non-castle path exists (King, Rook, King)") {
    val state = parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1").withDicePool(List(6, 4, 6))
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    assert(hasCastlingPath(paths), "castling path was filtered out by a longer non-castle alternative")
    val maxDice = paths.map(diceConsumed).max
    assertEquals(maxDice, 3, "the maximal turn consumes all three dice")
    assert(paths.forall(p => diceConsumed(p) == maxDice), "all legal paths must consume the maximum dice")
  }

  test("retains black queen-side castling (mirrors the observed production failure)") {
    val state = parse("r3k3/8/8/8/8/8/8/4K3 b q - 0 1").withDicePool(List(6, 4))
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    assert(hasCastlingPath(paths), "queen-side castling path missing")
  }

  test("non-castling position: maximality is unchanged (legal paths share the max length)") {
    // Two pawns, no castling rights, two Pawn dice -> the maximal turn pushes both pawns.
    val state = parse("4k3/8/8/8/8/8/3PP3/4K3 w - - 0 1").withDicePool(List(1, 1))
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    assert(paths.nonEmpty, "expected at least one legal path")
    assert(!hasCastlingPath(paths), "no castling is possible in this position")
    val maxLen = paths.map(_.size).max
    assert(paths.forall(_.size == maxLen), "without castling, all legal paths share the maximum length")
  }
