// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.search.BudgetedRules.*
import munit.FunSuite

class TurnStateVisitorSuite extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)

  private val referencePositions = List(
    "4k3/8/8/8/8/8/8/4K3 w - - 0 1",
    "4k3/8/8/8/8/8/8/4K2R w K - 0 1",
    "r3k3/8/8/8/8/8/8/4K3 b q - 0 1",
    "4k3/P7/8/8/8/8/8/4K3 w - - 0 1",
    "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
    "8/8/8/8/8/2k5/8/K7 w - - 0 1"
  )

  private val dicePermutations = List(
    Nil,
    List(1),
    List(4),
    List(6),
    List(1, 6),
    List(6, 4),
    List(4, 6),
    List(6, 4, 6),
    List(6, 1, 1),
    List(1, 1, 6),
    List(1, 4, 6),
    List(6, 4, 1)
  )

  private def replayPath(state: GameState, path: List[Move], isKingCapture: Boolean): GameState =
    val moved = path.foldLeft(state)(_.makeMove(_))
    if isKingCapture then moved else moved.endTurn()

  test("TurnGenerator.forEachLegalTurnState parity across reference positions and dice pools") {
    for
      fen  <- referencePositions
      dice <- dicePermutations
    do
      val state    = parse(fen).withDicePool(dice)
      val expected = TurnGenerator.generateAllLegalTurnPaths(state)

      val emittedPaths  = List.newBuilder[List[Move]]
      val emittedStates = List.newBuilder[GameState]
      val kingFlags     = List.newBuilder[Boolean]

      val count = TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, isKingCapture) =>
        emittedPaths += moves.take(len).toList
        emittedStates += resState
        kingFlags += isKingCapture
        true
      }

      val paths  = emittedPaths.result()
      val states = emittedStates.result()
      val flags  = kingFlags.result()

      assertEquals(count, expected.size, s"Count mismatch for fen=$fen dice=$dice")
      assertEquals(paths, expected, s"Paths mismatch for fen=$fen dice=$dice")

      // Verify each emitted resulting state matches exact sequential replay and endTurn contract
      var idx = 0
      while idx < paths.size do
        val p            = paths(idx)
        val s            = states(idx)
        val isKing       = flags(idx)
        val expectedKing = state.isKingCapture(p.last)
        assertEquals(isKing, expectedKing, s"isKingCapture mismatch at path $p")

        val expectedState = replayPath(state, p, isKing)
        assertEquals(s, expectedState, s"Resulting state mismatch at path $p")
        assertEquals(s.zobristHash, expectedState.zobristHash, s"Zobrist key mismatch at path $p")

        if isKing then assertEquals(s.activeColor, state.activeColor, "King capture must preserve mover activeColor")
        else assertEquals(s.activeColor, state.activeColor.opponent, "Normal turn must flip activeColor to opponent")

        idx += 1
  }

  test("Concurrent invocations of forEachLegalTurnState, visitTurnStates, and foldTurnStates do not interfere") {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.Future

    val testStates = for
      fen  <- referencePositions.take(3)
      dice <- dicePermutations.take(6)
    yield parse(fen).withDicePool(dice)

    val expectedResults = testStates.map { state =>
      (state, TurnGenerator.generateAllLegalTurnPaths(state))
    }

    val tasks = (1 to 4).flatMap { _ =>
      expectedResults.map { case (state, expectedPaths) =>
        Future {
          // 1. forEachLegalTurnState
          val collectedPaths = List.newBuilder[List[Move]]
          val count          = TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, isKingCapture) =>
            val p = moves.take(len).toList
            collectedPaths += p
            assertEquals(isKingCapture, state.isKingCapture(p.last))
            val expectedState = replayPath(state, p, isKingCapture)
            assertEquals(resState, expectedState)
            true
          }
          assertEquals(count, expectedPaths.size)
          assertEquals(collectedPaths.result(), expectedPaths)

          // 2. visitTurnStates
          val budget       = new Budget(Long.MaxValue)
          val visitPaths   = List.newBuilder[List[Move]]
          val visitOutcome = visitTurnStates(state, budget) { (moves, len, _, _) =>
            visitPaths += moves.take(len).toList
            true
          }
          visitOutcome match
            case Outcome.Complete(summary, _) =>
              assertEquals(summary.emittedPaths, expectedPaths.size)
              assertEquals(summary.totalLegalPaths, expectedPaths.size)
              assertEquals(summary.stoppedEarly, false)
              assertEquals(visitPaths.result(), expectedPaths)
            case Outcome.Incomplete(_) =>
              fail("visitTurnStates exhausted on Long.MaxValue during concurrent execution")

          // 3. foldTurnStates
          val foldOutcome = foldTurnStates(state, List.empty[List[Move]], new Budget(Long.MaxValue)) {
            (acc, path, _, _) =>
              (acc :+ path, true)
          }
          foldOutcome match
            case Outcome.Complete(foldResult, _) =>
              assertEquals(foldResult.value, expectedPaths)
              assertEquals(foldResult.stoppedEarly, false)
            case Outcome.Incomplete(_) =>
              fail("foldTurnStates exhausted on Long.MaxValue during concurrent execution")
        }
      }
    }

    Future.sequence(tasks).map(_ => ())
  }

  test("BudgetedRules.visitTurnStates and foldTurnStates parity with turnPaths on ample budget") {
    for
      fen  <- referencePositions
      dice <- dicePermutations
    do
      val state               = parse(fen).withDicePool(dice)
      val expectedTurnOutcome = turnPaths(state, new Budget(Long.MaxValue))

      expectedTurnOutcome match
        case Outcome.Complete(expectedPaths, usedWork) =>
          // 1. Test visitTurnStates
          val collectedPaths = List.newBuilder[List[Move]]
          val visitOutcome   = visitTurnStates(state, new Budget(Long.MaxValue)) { (moves, len, _, _) =>
            collectedPaths += moves.take(len).toList
            true
          }

          visitOutcome match
            case Outcome.Complete(summary, visitUsed) =>
              assertEquals(summary.emittedPaths, expectedPaths.size)
              assertEquals(summary.totalLegalPaths, expectedPaths.size)
              assertEquals(summary.stoppedEarly, false)
              assertEquals(visitUsed, usedWork, s"Work mismatch for fen=$fen dice=$dice")
              assertEquals(collectedPaths.result(), expectedPaths)
            case Outcome.Incomplete(_) =>
              fail(s"visitTurnStates exhausted on ample budget for fen=$fen dice=$dice")

          // 2. Test foldTurnStates
          val foldOutcome = foldTurnStates(state, List.empty[List[Move]], new Budget(Long.MaxValue)) {
            (acc, path, _, _) =>
              (acc :+ path, true)
          }

          foldOutcome match
            case Outcome.Complete(foldResult, foldUsed) =>
              assertEquals(foldResult.value, expectedPaths)
              assertEquals(foldResult.stoppedEarly, false)
              assertEquals(foldUsed, usedWork, s"Fold work mismatch for fen=$fen dice=$dice")
            case Outcome.Incomplete(_) =>
              fail(s"foldTurnStates exhausted on ample budget for fen=$fen dice=$dice")

        case Outcome.Incomplete(_) =>
          fail("turnPaths exhausted on Long.MaxValue")
  }

  test("F1: King-only ADEP & forced pass produces 0 emitted paths and 0 totalLegalPaths") {
    // White king on e1, roll contains only Queen die -> no legal move exists (forced pass).
    val state = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").withDicePool(List(5))

    var callbacks       = 0
    val unbudgetedCount = TurnGenerator.forEachLegalTurnState(state) { (_, _, _, _) =>
      callbacks += 1
      true
    }
    assertEquals(unbudgetedCount, 0)
    assertEquals(callbacks, 0)

    visitTurnStates(state, new Budget(Long.MaxValue)) { (_, _, _, _) =>
      fail("Callback must not be invoked on forced pass")
    } match
      case Outcome.Complete(summary, used) =>
        assertEquals(summary, TurnVisitSummary(0, 0, false))
        assertEquals(used, 1L) // 1 admission for MoveGenerator.generateMoves
      case Outcome.Incomplete(_) =>
        fail("Forced pass exhausted ample budget")

    foldTurnStates(state, 42, new Budget(Long.MaxValue)) { (acc, _, _, _) =>
      (acc + 1, true)
    } match
      case Outcome.Complete(result, used) =>
        assertEquals(result, TurnFoldResult(42, false))
        assertEquals(used, 1L)
      case Outcome.Incomplete(_) =>
        fail("Forced pass fold exhausted ample budget")
  }

  test("F2: Promotion on rank 7 with same-turn piece use and prefix reuse") {
    // White pawn on e7 promotes to Queen on e8, then the newly promoted Queen moves.
    val state = parse("k7/4P3/8/8/8/8/8/K7 w - - 0 1").withDicePool(List(1, 5))

    val paths  = List.newBuilder[List[Move]]
    val states = List.newBuilder[GameState]

    val count = TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, isKingCapture) =>
      val p = moves.take(len).toList
      paths += p
      states += resState
      assertEquals(isKingCapture, state.isKingCapture(p.last))
      val expected = replayPath(state, p, isKingCapture)
      assertEquals(resState, expected)
      if isKingCapture then assertEquals(resState.activeColor, Color.White)
      else assertEquals(resState.activeColor, Color.Black)
      true
    }

    val pathList = paths.result()
    assert(count > 0)
    assert(pathList.exists(_.size == 2), "Must contain 2-move paths (pawn push + promoted queen move)")
  }

  test("F3: Promotion on rank 6 with same-turn piece use (3 moves, 2-step prefix cache)") {
    // White pawn on e6, dice: Pawn + Pawn + Queen.
    // Move 1: e6-e7 (Pawn)
    // Move 2: e7-e8=Q (Pawn promotes to Queen)
    // Move 3: Queen moves from e8 (Queen)
    val state = parse("7k/8/4P3/8/8/8/8/K7 w - - 0 1").withDicePool(List(1, 1, 5))

    val paths  = List.newBuilder[List[Move]]
    val states = List.newBuilder[GameState]

    val count = TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, isKingCapture) =>
      val p = moves.take(len).toList
      paths += p
      states += resState
      assertEquals(isKingCapture, state.isKingCapture(p.last))
      val expected = replayPath(state, p, isKingCapture)
      assertEquals(resState, expected)
      if isKingCapture then assertEquals(resState.activeColor, Color.White)
      else assertEquals(resState.activeColor, Color.Black)
      true
    }

    val pathList = paths.result()
    assert(count > 0)
    assert(pathList.exists(_.size == 3), "Must contain 3-move paths with 2-step prefix cache")
  }

  test("F4: Non-empty roll forced pass") {
    // White has only Pawns blocked by opponent pieces or board with impossible dice
    val state = parse("k7/8/8/8/8/8/8/K7 w - - 0 1").withDicePool(List(1)) // Pawn die, but no pawns

    val count = TurnGenerator.forEachLegalTurnState(state) { (_, _, _, _) =>
      fail("No legal moves possible")
    }
    assertEquals(count, 0)

    val summary = visitTurnStates(state, new Budget(10)) { (_, _, _, _) =>
      fail("No legal moves possible")
    }
    assertEquals(summary, Outcome.Complete(TurnVisitSummary(0, 0, false), 1L))
  }

  test("F5: Castling 2-dice maximality vs 1-die branch") {
    // White king e1, rook h1, king-side right. Dice: King(6) + Rook(4).
    val state = parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1").withDicePool(List(6, 4))

    var castleCount = 0
    val total       = TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, isKingCapture) =>
      val path = moves.take(len).toList
      if path.head.isCastling then
        castleCount += 1
        assertEquals(len, 1)
        assertEquals(resState.activeColor, Color.Black)
        assertEquals(resState.castlingRights, "-")
      assertEquals(resState, replayPath(state, path, isKingCapture))
      true
    }

    assert(castleCount > 0, "King-side castling must be retained as maximal")
    assert(total >= castleCount)
  }

  test("F6: Intra-turn en-passant retention vs endTurn() clearing") {
    // White pawn e5, Black pawn moved d7-d5 (EP square on d6). Dice: King(6) + Pawn(1).
    val state = parse("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1").withDicePool(List(6, 1))

    var foundEpCapture = false
    TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, isKingCapture) =>
      val path = moves.take(len).toList
      if path.exists(_.isEnPassant) then
        foundEpCapture = true
        // The resulting state after EP capture has no EP targets
        assertEquals(resState.enPassant.value, 0L)
      assertEquals(resState, replayPath(state, path, isKingCapture))
      true
    }

    assert(foundEpCapture, "Pawn capturing EP must be generated")
  }

  test("F7: Non-maximal early king capture (endTurn is NOT called)") {
    // White Queen on a2, Black King on a1. Dice: Queen(5), Queen(5), Queen(5).
    val state = parse("8/8/8/8/8/8/Q7/k6K w - - 0 1").withDicePool(List(5, 5, 5))

    var kingCapturesSeen            = 0
    var immediateOneMoveCaptureSeen = false
    TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, isKingCapture) =>
      val p = moves.take(len).toList
      if isKingCapture then
        kingCapturesSeen += 1
        if len == 1 then immediateOneMoveCaptureSeen = true
        // endTurn() was NOT called: activeColor remains White, dicePool unchanged
        assertEquals(resState.activeColor, Color.White)
        assertEquals(resState.blackPieces & resState.kings, Bitboard.empty)
        val expected = replayPath(state, p, isKingCapture = true)
        assertEquals(resState, expected)
      true
    }

    assert(kingCapturesSeen > 0, "King capture must be emitted")
    assert(immediateOneMoveCaptureSeen, "Immediate 1-move king capture must be emitted")
  }

  test("F8: Commuting micro-move transposition reaches identical resulting state") {
    // Two pawns on a2 and b2. Dice: Pawn(1), Pawn(1).
    val state = parse("4k3/8/8/8/8/8/PP6/4K3 w - - 0 1").withDicePool(List(1, 1))

    val statesByPath = collection.mutable.Map.empty[String, GameState]
    TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, _) =>
      val notation = moves.take(len).map(_.toUci).mkString(" ")
      statesByPath(notation) = resState
      true
    }

    // Both a2a3 b2b3 and b2b3 a2a3 reach the exact same board
    assert(statesByPath.contains("a2a3 b2b3"), "a2a3 b2b3 should be present")
    assert(statesByPath.contains("b2b3 a2a3"), "b2b3 a2a3 should be present")

    val stateAB = statesByPath("a2a3 b2b3")
    val stateBA = statesByPath("b2b3 a2a3")
    assertEquals(stateAB, stateBA, "Transposed paths must yield equal GameState")
    assertEquals(stateAB.zobristHash, stateBA.zobristHash, "Transposed paths must yield equal Zobrist hash")
  }

  test("F10: Deterministic budget boundaries for visitTurnStates and foldTurnStates") {
    val state                           = parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1").withDicePool(List(6, 4, 6))
    val Outcome.Complete(summary, work) =
      visitTurnStates(state, new Budget(Long.MaxValue))((_, _, _, _) => true): @unchecked
    assert(work > 3)

    // Boundaries below exact work return Incomplete with cumulative work
    for limit <- List(0L, 1L, 2L, work - 1) do
      assertEquals(visitTurnStates(state, new Budget(limit))((_, _, _, _) => true), Outcome.Incomplete(limit))
      assertEquals(
        foldTurnStates(state, 0, new Budget(limit))((acc, _, _, _) => (acc, true)),
        Outcome.Incomplete(limit)
      )

    // Exactly sufficient budget completes
    assertEquals(visitTurnStates(state, new Budget(work))((_, _, _, _) => true), Outcome.Complete(summary, work))
  }

  test("Early stop requested by callback terminates immediately and yields stoppedEarly = true") {
    val state = parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1").withDicePool(List(6, 4, 6))

    // 1. TurnGenerator.forEachLegalTurnState stops on first path
    var visits          = 0
    val unbudgetedCount = TurnGenerator.forEachLegalTurnState(state) { (_, _, _, _) =>
      visits += 1
      false // Stop immediately
    }
    assertEquals(unbudgetedCount, 1)
    assertEquals(visits, 1)

    // 2. BudgetedRules.visitTurnStates stops on first path
    var budgetedVisits                    = 0
    val Outcome.Complete(visitSummary, _) = visitTurnStates(state, new Budget(Long.MaxValue)) { (_, _, _, _) =>
      budgetedVisits += 1
      false
    }: @unchecked
    assertEquals(budgetedVisits, 1)
    assertEquals(visitSummary.emittedPaths, 1)
    assert(visitSummary.totalLegalPaths > 1)
    assertEquals(visitSummary.stoppedEarly, true)

    // 3. BudgetedRules.foldTurnStates stops on first path
    var foldVisits                      = 0
    val Outcome.Complete(foldResult, _) = foldTurnStates(state, 0, new Budget(Long.MaxValue)) { (acc, _, _, _) =>
      foldVisits += 1
      (acc + 1, false)
    }: @unchecked
    assertEquals(foldVisits, 1)
    assertEquals(foldResult.value, 1)
    assertEquals(foldResult.stoppedEarly, true)
  }

  test("Budgeted visitors and folds return the sequentially replayed state and capture flag for every path") {
    val positions = List(
      ("8/8/8/8/8/8/Q7/k6K w - - 0 1", List(5, 5)),
      ("7k/8/4P3/8/8/8/8/K7 w - - 0 1", List(1, 1, 5)),
      ("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", List(6, 1)),
      ("r3k3/8/8/8/8/8/8/4K3 b q - 4 12", List(6, 4))
    )

    for (fen, dice) <- positions do
      val state    = parse(fen).withDicePool(dice)
      val expected = TurnGenerator.generateAllLegalTurnPaths(state).map { path =>
        val prefix    = path.init.foldLeft(state)(_.makeMove(_))
        val isCapture = prefix.isKingCapture(path.last)
        val result    = replayPath(state, path, isCapture)
        (path, result, isCapture, result.zobristHash)
      }
      assert(expected.nonEmpty, s"Fixture must have legal paths: $fen")

      val visited      = List.newBuilder[(List[Move], GameState, Boolean, Long)]
      val visitOutcome = visitTurnStates(state, new Budget(Long.MaxValue)) { (moves, len, result, isCapture) =>
        visited += ((moves.take(len).toList, result, isCapture, result.zobristHash))
        true
      }
      val folded = foldTurnStates(state, List.empty[(List[Move], GameState, Boolean, Long)], new Budget(Long.MaxValue)) {
        (acc, path, result, isCapture) =>
          ((path, result, isCapture, result.zobristHash) :: acc, true)
      }
      val Outcome.Complete(summary, visitWork)   = visitOutcome: @unchecked
      val Outcome.Complete(foldResult, foldWork) = folded: @unchecked
      assertEquals(summary, TurnVisitSummary(expected.size, expected.size, false))
      assertEquals(visited.result(), expected, s"Visitor mismatch for $fen, dice=$dice")
      assertEquals(foldResult.value.reverse, expected, s"Fold mismatch for $fen, dice=$dice")
      assertEquals(foldResult.stoppedEarly, false)
      assertEquals(foldWork, visitWork)
  }

  test("Every insufficient budget discards discovery without invoking the visitor or fold") {
    val state                         = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").withDicePool(List(6))
    val Outcome.Complete(paths, work) = turnPaths(state, new Budget(Long.MaxValue)): @unchecked
    assert(paths.nonEmpty)
    for limit <- 0L until work do
      var visitCalls = 0
      val visit      = visitTurnStates(state, new Budget(limit)) { (_, _, _, _) =>
        visitCalls += 1
        true
      }
      var foldCalls = 0
      val fold      = foldTurnStates(state, 17, new Budget(limit)) { (acc, _, _, _) =>
        foldCalls += 1
        (acc + 1, true)
      }
      assertEquals(visit, Outcome.Incomplete(limit))
      assertEquals(fold, Outcome.Incomplete(limit))
      assertEquals(visitCalls, 0)
      assertEquals(foldCalls, 0)

    val exactVisit = visitTurnStates(state, new Budget(work))((_, _, _, _) => true)
    val exactFold  = foldTurnStates(state, 17, new Budget(work))((acc, _, _, _) => (acc + 1, true))
    assertEquals(exactVisit, Outcome.Complete(TurnVisitSummary(paths.size, paths.size, false), work))
    assertEquals(exactFold, Outcome.Complete(TurnFoldResult(17 + paths.size, false), work))
  }

  test("Shared budgets report cumulative work and keep exhaustion sticky across visitor and fold calls") {
    val pass                          = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").withDicePool(List(1))
    val state                         = pass.withDicePool(List(6))
    val Outcome.Complete(paths, work) = turnPaths(state, new Budget(Long.MaxValue)): @unchecked
    val budget                        = new Budget(work + 1)

    assertEquals(turnPaths(pass, budget), Outcome.Complete(Nil, 1L))
    assertEquals(
      visitTurnStates(state, budget)((_, _, _, _) => true),
      Outcome.Complete(TurnVisitSummary(paths.size, paths.size, false), work + 1)
    )
    var calls         = 0
    val exhaustedFold = foldTurnStates(state, 9, budget) { (acc, _, _, _) =>
      calls += 1
      (acc + 1, true)
    }
    assertEquals(exhaustedFold, Outcome.Incomplete(work + 1))
    assertEquals(visitTurnStates(pass, budget) { (_, _, _, _) => calls += 1; true }, Outcome.Incomplete(work + 1))
    assertEquals(calls, 0)
  }

  test("Stopping at the first normal path keeps the preceding captures and the stopping fold value") {
    val state = parse("8/8/8/8/8/8/Q7/k6K w - - 0 1").withDicePool(List(5, 5))
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    val flags = paths.map { path =>
      path.init.foldLeft(state)(_.makeMove(_)).isKingCapture(path.last)
    }
    val firstNormal = flags.indexOf(false)
    assert(firstNormal > 0, "Fixture must have captures followed by normal paths")
    assert(flags.take(firstNormal).forall(identity))
    assert(flags.drop(firstNormal).forall(flag => !flag))
    val expected = paths.take(firstNormal + 1)

    val visited                              = List.newBuilder[List[Move]]
    val Outcome.Complete(summary, visitWork) = visitTurnStates(state, new Budget(Long.MaxValue)) {
      (moves, len, _, isCapture) =>
        visited += moves.take(len).toList
        isCapture
    }: @unchecked
    val Outcome.Complete(result, foldWork) = foldTurnStates(state, List.empty[List[Move]], new Budget(Long.MaxValue)) {
      (acc, path, _, isCapture) =>
        (path :: acc, isCapture)
    }: @unchecked
    assertEquals(visited.result(), expected)
    assertEquals(summary, TurnVisitSummary(expected.size, paths.size, true))
    assertEquals(result, TurnFoldResult(expected.reverse, true))
    assertEquals(visitWork, foldWork)
  }
