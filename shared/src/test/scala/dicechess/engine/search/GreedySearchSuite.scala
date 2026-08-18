package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class GreedySearchSuite extends FunSuite:

  test("GreedySearch should prefer taking a queen over an empty square") {
    val fen   = "8/8/8/2n1q3/3P4/8/8/8 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = GreedySearch.findBestMove(state.withDicePool(List(1)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.moves.size, 1)
    val move = bestMove.moves.head
    assertEquals(move.toSquare.toNotation, "e5") // Takes the queen
  }

  test("GreedySearch should evaluate sequence of 2 moves to maximize score") {
    // Black queen on h6. White bishop on c1. White pawn on d2 blocks the bishop.
    // White rolls pawn (1) and bishop (3).
    // White must move pawn to unblock the bishop, then bishop c1-h6 to take queen.
    val fen   = "8/8/7q/8/8/8/3P4/2B5 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = GreedySearch.findBestMove(state.withDicePool(List(1, 3)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.moves.size, 2)
    val move1 = bestMove.moves.head
    val move2 = bestMove.moves(1)

    assertEquals(move1.fromSquare.toNotation, "d2")
    assertEquals(move2.fromSquare.toNotation, "c1")
    assertEquals(move2.toSquare.toNotation, "h6")
  }

  test("GreedySearch should maximize score with 3 micro-moves") {
    // Black queen on h8. White rook on a1. White pawn on a2 blocks the rook. White knight on b1.
    // White rolls: pawn (1), knight (2), rook (4).
    // White must move pawn to unblock rook, move knight somewhere, then rook a1-a8-h8? No, rook a1-a8 is blocked?
    // Let's make a simple one:
    // White king on e1. Black queen on e4. White pawn on d2. White bishop on c1.
    // White rolls: pawn(1), bishop(3), king(6).
    // Move pawn d2-d3, then bishop c1-d2, then king e1-d1. (Not maximizing anything).
    // Let's do: black queen on h8. White bishop on a1. White pawn on b2 blocks. White pawn on c2 blocks.
    // Black queen on a8. White rook on a1. White pawn on a2 blocks the rook.
    // Black knight on b3. White pawn on h2.
    // White rolls: pawn(1), pawn(1), rook(4).
    // White must capture a2xb3 to clear the a-file, move h2-h3, then rook a1xa8.
    val fen   = "q7/8/8/8/8/1n6/P6P/R7 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = GreedySearch.findBestMove(state.withDicePool(List(1, 1, 4)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.moves.size, 3)
    val moves = bestMove.moves
    // One of the moves must be rook capturing on a8
    assert(moves.exists(_.toSquare.toNotation == "a8"))
  }

  test("GreedySearch should prefer terminal king capture over a multi-capture material line") {
    // White can either win immediately with Ra1xa8, or spend three rook moves taking a queen and two rooks.
    val fen   = "kr5r/8/8/8/8/8/8/R6q w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = GreedySearch.findBestMove(state.withDicePool(List(4, 4, 4)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.score, SearchScoring.TerminalWinScore)
    assertEquals(bestMove.moves.size, 1)
    assertEquals(bestMove.moves.head.fromSquare.toNotation, "a1")
    assertEquals(bestMove.moves.head.toSquare.toNotation, "a8")
  }

  test("SearchScoring should score king capture as an explicit terminal win") {
    val fen   = "k7/8/8/8/8/8/8/R7 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val kingCapture = Move(Square('a', 1), Square('a', 8), Move.Capture)

    val scored = SearchScoring.scorePath(state, List(kingCapture))

    assertEquals(scored.score, SearchScoring.TerminalWinScore)
  }

  test("GreedySearch should prefer the shortest terminal king capture line") {
    // Ra1xa8 wins in one move; Rh1-h8-a8 also wins, but spends an extra die.
    val fen   = "k7/8/8/8/8/8/8/R6R w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    val bestMoveOpt = GreedySearch.findBestMove(state.withDicePool(List(4, 4)))

    assert(bestMoveOpt.isDefined)
    val bestMove = bestMoveOpt.get
    assertEquals(bestMove.score, SearchScoring.TerminalWinScore)
    assertEquals(bestMove.moves.size, 1)
    assertEquals(bestMove.moves.head.fromSquare.toNotation, "a1")
    assertEquals(bestMove.moves.head.toSquare.toNotation, "a8")
  }

  test("GreedySearch should return None when no legal moves exist") {
    // White king trapped, rolls only pawns, but has no pawns.
    val fen   = "8/8/8/8/8/8/8/k6K w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
    // Roll pawns
    val bestMoveOpt = GreedySearch.findBestMove(state.withDicePool(List(1, 1)))
    assert(bestMoveOpt.isEmpty)
  }

  test("GreedySearch should select non-deterministically among equally optimal moves using mock Random") {
    // White queen on d4. Black knights on c5 and e5. Both are worth 300.
    // White rolls a queen (5) and can take either.
    val fen   = "8/8/8/2n1n3/3Q4/8/8/8 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )

    val mockRandomZero = new scala.util.Random {
      override def nextInt(n: Int): Int = 0
    }
    val mockRandomOne = new scala.util.Random {
      override def nextInt(n: Int): Int = 1
    }

    val resZero = GreedySearch.findBestMove(state.withDicePool(List(5)), mockRandomZero)
    val resOne  = GreedySearch.findBestMove(state.withDicePool(List(5)), mockRandomOne)

    assert(resZero.isDefined)
    assert(resOne.isDefined)

    val targetZero = resZero.get.moves.head.toSquare.toNotation
    val targetOne  = resOne.get.moves.head.toSquare.toNotation

    // Ensure we selected both possible targets and they are different
    assert(Set(targetZero, targetOne) == Set("c5", "e5"))
  }

  test("GreedySearch should statistically select both equally optimal moves over multiple runs with default Random") {
    val fen   = "8/8/8/2n1n3/3Q4/8/8/8 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )

    var chosenTargets = Set.empty[String]
    for _ <- 1 to 50 do {
      val bestMoveOpt = GreedySearch.findBestMove(state.withDicePool(List(5)))
      bestMoveOpt.foreach { bm =>
        chosenTargets += bm.moves.head.toSquare.toNotation
      }
    }

    // Both c5 and e5 must eventually be chosen
    assertEquals(chosenTargets, Set("c5", "e5"))
  }

  test("GreedySearch should not choose castling when Rook die is missing from the pool") {
    // Black king on e8, Black rook on h8, King has castling rights ('k')
    // Dice pool: [3, 6, 6] (Bishop, King, King). Rook (4) is missing.
    val state = FenParser
      .parse("rnq1k2r/pppbpp1p/3p2pQ/8/2BPP3/2N2N2/PPPK1PPP/1R5R b kq - 0 1")
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )
      .withDicePool(List(3, 6, 6))

    val bestMoveOpt = GreedySearch.findBestMove(state)
    // The bot should NOT return a castling move (e8g8 / quiet castle)
    bestMoveOpt.foreach { scoredSeq =>
      val hasCastling = scoredSeq.moves.exists(m => m.fromSquare.toNotation == "e8" && m.toSquare.toNotation == "g8")
      assert(!hasCastling, s"Bot castled illegally without a Rook die: ${scoredSeq.moves.map(m => m.fromSquare.toNotation + m.toSquare.toNotation)}")
    }
  }

  test("Evaluator should apply king exposure penalty only to the moving side (asymmetric)") {
    // White king on e1 is safe. Black king on e8 is attacked by White rook on e5.
    val fen   = "4k3/8/8/4R3/8/8/8/4K3 w - - 0 1"
    val state = FenParser
      .parse(fen)
      .fold(
        err => fail(s"Failed to parse FEN: $err"),
        state => state
      )

    // Evaluate from White's perspective.
    // Material is White: Rook(500) vs Black: 0 => +500
    // King safety: White is safe (0). Black is exposed but we don't count it for White's turn evaluation.
    // evaluate = evaluateMaterial + evaluateKingSafety(white)
    //          = 500 + 0 = 500
    val score = Evaluator.evaluate(state, Color.White)
    assertEquals(score, 500)

    // Evaluate from Black's perspective.
    // Material is Black: 0 vs White: Rook(500) => -500
    // King safety: Black is exposed (-2000).
    // evaluate = evaluateMaterial + evaluateKingSafety(black)
    //          = -500 + (-2000) = -2500
    val scoreBlack = Evaluator.evaluate(state, Color.Black)
    assertEquals(scoreBlack, -2500)
  }
