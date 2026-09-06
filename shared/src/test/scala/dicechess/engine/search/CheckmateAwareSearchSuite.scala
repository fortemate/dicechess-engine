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

  test("CheckmateAwareSearch should prioritize multi-move winning sequence over material captures") {
    // Black King on a8, Black Queen on h8. Black Knight on b3.
    // White Rook on a1, White Pawn on a2.
    // White rolls Pawn (1) and Rook (4).
    // Pawn a2xb3 clears file a, allowing Ra1xa8 capturing Black King (TerminalWinScore).
    val fen   = "k6q/8/8/8/8/1n6/P7/R3K3 w - - 0 1"
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
    assertEquals(bestMove.moves.size, 2)
    val moves = bestMove.moves
    assertEquals(moves.head.fromSquare.toNotation, "a2")
    assertEquals(moves.head.toSquare.toNotation, "b3")
    assertEquals(moves(1).fromSquare.toNotation, "a1")
    assertEquals(moves(1).toSquare.toNotation, "a8")
  }

  test("CheckmateAwareSearch should prioritize winning King capture even when player's King is exposed") {
    // White King on e1 exposed to Black Rook on e8. White Rook on a1. Black King on a8.
    // White rolls Rook (4).
    // White can move King to escape check, but capturing Black King on a8 wins immediately.
    val fen   = "k3r3/8/8/8/8/8/8/R3K3 w - - 0 1"
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

  test("CheckmateAwareSearch should evaluate King safety at the end of turn and avoid exposing King") {
    // White King on e1. Black Queen on a5. White Rook on d2 shields King along diagonal a5-e1. White Rook on h1.
    // White rolls Rook (4).
    // Any d2 Rook move leaves King on e1 exposed to a5 Queen at end of turn.
    // Moving h1 Rook keeps d2 Rook in place, leaving King safely shielded.
    val fen   = "8/8/8/q7/8/8/3R4/4K2R w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = CheckmateAwareSearch.findBestMove(state.withDicePool(List(4)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.moves.head.fromSquare.toNotation, "h1")
  }

  test("CheckmateAwareSearch should treat safe non-winning paths equally regardless of material value") {
    // White King on a1 (safe). Black Queen on d8, Black Pawn on h8. White Rook on d1.
    // White rolls Rook (4).
    // White can capture Queen on d8 or move Rook to quiet square d4. Both leave King safe.
    // Since CheckmateAwareSearch ignores material evaluation, both paths are in safePaths.
    val fen   = "3q3k/8/8/8/8/8/8/R3K3 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )

    val fixedRandomZero = new Random {
      override def nextInt(n: Int): Int = 0
    }
    val fixedRandomLast = new Random {
      override def nextInt(n: Int): Int = n - 1
    }

    val resZero = CheckmateAwareSearch.findBestMove(state.withDicePool(List(4)), fixedRandomZero)
    val resLast = CheckmateAwareSearch.findBestMove(state.withDicePool(List(4)), fixedRandomLast)

    assert(resZero.isDefined)
    assert(resLast.isDefined)

    val targetZero = resZero.get.moves.head.toSquare.toNotation
    val targetLast = resLast.get.moves.head.toSquare.toNotation

    // Ensure that depending on RNG, different safe moves can be picked (proving no strict material bias)
    assert(
      targetZero != targetLast,
      s"Expected different target squares for different RNG seeds, got $targetZero and $targetLast"
    )
  }

  test("CheckmateAwareSearch should work symmetrically for Black active player") {
    // Black active player. Black Rook on a8, Black Pawn on a7. White Knight on b6. White King on a1.
    // Black rolls Pawn (1) and Rook (4).
    // Black plays a7xb6 clearing file a, then Ra8xa1 capturing White King.
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
    assertEquals(bestMove.moves.size, 2)
    val moves = bestMove.moves
    assertEquals(moves.head.fromSquare.toNotation, "a7")
    assertEquals(moves.head.toSquare.toNotation, "b6")
    assertEquals(moves(1).fromSquare.toNotation, "a8")
    assertEquals(moves(1).toSquare.toNotation, "a1")
  }

  test("CheckmateAwareSearch should deterministically pick moves using custom Random across winning paths") {
    // White Rook on a1 and White Queen on a2 both attacking Black King on a8.
    val fen   = "k7/8/8/8/8/8/Q7/R3K3 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )

    val fixedRandomZero = new Random {
      override def nextInt(n: Int): Int = 0
    }
    val fixedRandomOne = new Random {
      override def nextInt(n: Int): Int = 1
    }

    // Roll Rook (4) or Queen (5) or both
    val resZero = CheckmateAwareSearch.findBestMove(state.withDicePool(List(4, 5)), fixedRandomZero)
    val resOne  = CheckmateAwareSearch.findBestMove(state.withDicePool(List(4, 5)), fixedRandomOne)

    assert(resZero.isDefined)
    assert(resOne.isDefined)
    assertEquals(resZero.get.score, SearchScoring.TerminalWinScore)
    assertEquals(resOne.get.score, SearchScoring.TerminalWinScore)

    // Verify deterministic selection
    assertEquals(
      CheckmateAwareSearch.findBestMove(state.withDicePool(List(4, 5)), fixedRandomZero),
      resZero
    )
    assertEquals(
      CheckmateAwareSearch.findBestMove(state.withDicePool(List(4, 5)), fixedRandomOne),
      resOne
    )
  }

  test("CheckmateAwareSearch should deterministically pick fallback move when no safe move exists") {
    // White king on e1. Black queen on e2, Black rooks on d8 and e8.
    // King is on e1. White rolls king (6).
    // All moves leave King under attack.
    val fen   = "3rr3/8/8/8/8/8/4q3/4K3 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )

    val fixedRandomZero = new Random {
      override def nextInt(n: Int): Int = 0
    }
    val fixedRandomOne = new Random {
      override def nextInt(n: Int): Int = 1
    }

    val resZero = CheckmateAwareSearch.findBestMove(state.withDicePool(List(6)), fixedRandomZero)
    val resOne  = CheckmateAwareSearch.findBestMove(state.withDicePool(List(6)), fixedRandomOne)

    assert(resZero.isDefined)
    assert(resOne.isDefined)

    // Confirm deterministic reproducibility
    assertEquals(
      CheckmateAwareSearch.findBestMove(state.withDicePool(List(6)), fixedRandomZero),
      resZero
    )
  }

  test("CheckmateAwareSearch should fall back to any legal move if no safe move exists") {
    // White king on e1. Black queen on e2, Black rooks on d8 and e8.
    // King is on e1. White rolls king (6).
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
