package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class AggressiveSearchSuite extends FunSuite:

  test("AggressiveSearch should prefer advancing pawns (pawn storm) when no captures are available") {
    // White king on a1. White pawn on d2.
    // White rolls pawn (1). White can move d2-d3 or d2-d4.
    // d2-d4 advances 2 ranks (bonus = 2 * 15 = 30), d2-d3 advances 1 rank (bonus = 15).
    // AggressiveSearch must prefer d2-d4 because of the pawn storm bonus.
    val fen   = "4k3/8/8/8/8/8/3P4/K7 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = AggressiveSearch.findBestMove(state.withDicePool(List(1)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.moves.size, 1)
    assertEquals(bestMove.moves.head.fromSquare.toNotation, "d2")
    assertEquals(bestMove.moves.head.toSquare.toNotation, "d4") // 2-square pawn storm advance
  }

  test("AggressiveSearch should prefer moves that bring pieces closer to the enemy King") {
    // Black king on h8. White knight on c1.
    // White rolls Knight (2).
    // White can move c1-b3 or c1-d3.
    // Chebyshev distance to h8 (8, 8):
    // - c1 (3, 1): dist = max(5, 7) = 7
    // - b3 (2, 3): dist = max(6, 5) = 6
    // - d3 (4, 3): dist = max(4, 5) = 5 -> Closer!
    // AggressiveSearch must prefer d3 over b3 because it is closer.
    val fen   = "7k/8/8/8/8/8/8/2N1K3 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = AggressiveSearch.findBestMove(state.withDicePool(List(2)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.moves.size, 1)
    assertEquals(bestMove.moves.head.fromSquare.toNotation, "c1")
    assertEquals(bestMove.moves.head.toSquare.toNotation, "d3") // Closer to the enemy King
  }

  test("AggressiveSearch should prioritize putting pressure on the enemy King Ring") {
    // Black king on h8. White rook on a1.
    // White rolls Rook (4).
    // White can move a1-a7 (attacks g7 and h7, adjacent to h8, exerts pressure on king ring).
    // Or a1-a2 (no pressure on king ring, same Chebyshev distance).
    // AggressiveSearch must prefer a7.
    val fen   = "7k/8/8/8/8/8/8/R1K5 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = AggressiveSearch.findBestMove(state.withDicePool(List(4)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.moves.size, 1)
    assertEquals(bestMove.moves.head.fromSquare.toNotation, "a1")
    assertEquals(bestMove.moves.head.toSquare.toNotation, "a7") // Pressures the King Ring
  }
