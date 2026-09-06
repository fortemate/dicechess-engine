package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.util.Random

class CheckmateAwareSearchSuite extends FunSuite:

  test("CheckmateAwareSearch should prioritize an immediate winning king capture") {
    // White can either capture Black king on a8 or Black queen on h1.
    // Material value of Queen is 900, but King capture is TerminalWinScore.
    // CheckmateAwareSearch must pick the King capture.
    val fen   = "k7/8/8/8/8/8/8/R6q w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = CheckmateAwareSearch.findBestMove(state.withDicePool(List(4)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.score, SearchScoring.TerminalWinScore)
    assertEquals(bestMove.moves.size, 1)
    assertEquals(bestMove.moves.head.fromSquare.toNotation, "a1")
    assertEquals(bestMove.moves.head.toSquare.toNotation, "a8")
  }

  test("CheckmateAwareSearch should prioritize keeping its own King safe from e-file rook") {
    val fen   = "4r3/8/8/8/8/8/8/4K3 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )

    // Try a few times to ensure we don't pick e2
    for _ <- 1 to 20 do {
      val bestMoveOpt = CheckmateAwareSearch.findBestMove(state.withDicePool(List(6)))
      assert(bestMoveOpt.isDefined)
      val bestMove   = bestMoveOpt.get
      val toNotation = bestMove.moves.head.toSquare.toNotation
      assert(toNotation != "e2", s"Expected a safe square (d1, f1, d2, f2), but got unsafe e2")
    }
  }

  test("CheckmateAwareSearch should fall back to any legal move if no safe move exists") {
    // White king on e1. Black queen on e2, Black rooks on d8 and e8.
    // King is on e1. White rolls king (6).
    // Legal King moves from e1:
    // - e1-e2 (unsafe, captures queen but it is defended by e8 rook).
    // - e1-d1 (unsafe, attacked by d8 rook).
    // - e1-f1 (unsafe, attacked by e2 queen).
    // - e1-d2 (unsafe, attacked by d8 rook and e2 queen).
    // - e1-f2 (unsafe, attacked by e2 queen).
    // All moves leave King under attack.
    // CheckmateAwareSearch should still successfully return a move (fallback).
    val fen   = "3rr3/8/8/8/8/8/4q3/4K3 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = CheckmateAwareSearch.findBestMove(state.withDicePool(List(6)))
    assert(bestMoveOpt.isDefined)
  }

  test("CheckmateAwareSearch should find a two-move king capture when no single move wins") {
    // White: Ke1, Ra1, Pa2. Black: Ka8, Nb3. White rolls Pawn (1) and Rook (4).
    // No single move captures the king: White's own pawn blocks the a-file. Only the
    // pawn capture a2xb3 followed by Ra1xa8 wins; a pawn push keeps the file blocked and
    // moving the rook first leaves no king capture, so the winning path is unique.
    val fen   = "k7/8/8/8/8/1n6/P7/R3K3 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = CheckmateAwareSearch.findBestMove(state.withDicePool(List(1, 4)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.score, SearchScoring.TerminalWinScore)
    assertEquals(bestMove.moves.map(m => m.fromSquare.toNotation + m.toSquare.toNotation).toList, List("a2b3", "a1a8"))
  }

  test("CheckmateAwareSearch should find a two-move king capture for Black") {
    // Mirror of the previous position with Black to move: Black Ke8, Ra8, Pa7 against
    // White Ka1, Nb6. Black rolls Pawn (1) and Rook (4): a7xb6 opens the a-file for Ra8xa1.
    val fen   = "r3k3/p7/1N6/8/8/8/8/K7 b - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = CheckmateAwareSearch.findBestMove(state.withDicePool(List(1, 4)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.score, SearchScoring.TerminalWinScore)
    assertEquals(bestMove.moves.map(m => m.fromSquare.toNotation + m.toSquare.toNotation).toList, List("a7b6", "a8a1"))
  }

  test("CheckmateAwareSearch should judge King safety at the end of the turn, not before the move") {
    // White: Ke1, Rd2, Rh1. Black: Qa5. White rolls Rook (4).
    // The king is safe right now only because the d2 rook blocks the a5-e1 diagonal, so
    // every d2 rook move exposes the king at the end of the turn while every h1 rook move
    // keeps it shielded. Seeded RNGs make the sample deterministic; each must pick the h1 rook.
    val fen   = "8/8/8/q7/8/8/3R4/4K2R w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )

    for seed <- 0 until 20 do {
      val bestMoveOpt = CheckmateAwareSearch.findBestMove(state.withDicePool(List(4)), new Random(seed))
      assert(bestMoveOpt.isDefined)
      val from = bestMoveOpt.get.moves.head.fromSquare.toNotation
      assertEquals(from, "h1", s"seed $seed moved the shielding d2 rook and exposed the king")
    }
  }

  test("CheckmateAwareSearch should return None when no legal moves exist") {
    val fen   = "8/8/8/8/8/8/8/k6K w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = CheckmateAwareSearch.findBestMove(state.withDicePool(List(1, 1)))
    assert(bestMoveOpt.isEmpty)
  }
