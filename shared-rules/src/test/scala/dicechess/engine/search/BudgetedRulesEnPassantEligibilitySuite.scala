// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import BudgetedRules.*

/** Regression suite: `BudgetedRules` must apply the strict turn-start en-passant eligibility of #419 exactly as
  * [[TurnGenerator]] does, so budgeted and canonical traversals agree on en-passant positions.
  *
  * Before the fix every budgeted traversal called `MoveGenerator.generateMoves(state)` — the `Bitboard.full` mask — and
  * offered `e4e5 e5xd6 e.p.` to a pawn that was not adjacent to `d5` when the turn started.
  */
class BudgetedRulesEnPassantEligibilitySuite extends munit.FunSuite:
  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  /** Pawn `e4` can reach `e5` in one push but was not adjacent to `d5` at turn start: no en passant for it. */
  private val ineligiblePawn = "4k3/8/8/3p4/4P3/8/8/4K3 w - d6 0 1"

  private def uci(paths: List[List[Move]]): List[String] =
    paths.map(_.map(m => m.fromSquare.toNotation + m.toSquare.toNotation).mkString(" "))

  test("turnPaths agrees with TurnGenerator when a pawn becomes adjacent to the target during the turn") {
    for dice <- List(List(1, 1), List(1, 1, 1)) do
      val state     = parse(ineligiblePawn).withDicePool(dice)
      val canonical = TurnGenerator.generateAllLegalTurnPaths(state)
      assert(!canonical.exists(_.exists(_.isEnPassant)), s"canonical generator must not offer en passant for $dice")
      turnPaths(state, new Budget(Long.MaxValue)) match
        case Outcome.Complete(paths, _) => assertEquals(uci(paths), uci(canonical))
        case other                      => fail(s"ample budget exhausted: $other")
  }

  test("visitTurnStates and foldTurnStates emit exactly the canonical paths and states") {
    val state     = parse(ineligiblePawn).withDicePool(List(1, 1))
    val canonical = List.newBuilder[(List[Move], GameState)]
    TurnGenerator.forEachLegalTurnState(state) { (moves, len, resState, _) =>
      canonical += (moves.take(len).toList -> resState)
      true
    }
    val visited = List.newBuilder[(List[Move], GameState)]
    val summary = visitTurnStates(state, new Budget(Long.MaxValue)) { (moves, len, resState, _) =>
      visited += (moves.take(len).toList -> resState)
      true
    }
    val expected = canonical.result()
    assertEquals(uci(visited.result().map(_._1)), uci(expected.map(_._1)))
    assertEquals(visited.result().map(_._2), expected.map(_._2))
    summary match
      case Outcome.Complete(visit, _) => assertEquals(visit, TurnVisitSummary(expected.size, expected.size, false))
      case other                      => fail(s"ample budget exhausted: $other")

    val folded = foldTurnStates(state, List.empty[List[Move]], new Budget(Long.MaxValue)) { (acc, path, _, _) =>
      (path :: acc, true)
    }
    folded match
      case Outcome.Complete(TurnFoldResult(paths, false), _) =>
        assertEquals(uci(paths.reverse), uci(expected.map(_._1)))
      case other => fail(s"unexpected fold outcome: $other")
  }

  test("an eligible pawn still captures en passant on any micro-move in every budgeted traversal") {
    // #351: pawn e5 was adjacent to d5 at turn start, so the capture stays legal as the 1st or 2nd micro-move.
    val state     = parse("4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1").withDicePool(List(4, 1))
    val canonical = TurnGenerator.generateAllLegalTurnPaths(state)
    assert(canonical.exists(_.exists(_.isEnPassant)))
    turnPaths(state, new Budget(Long.MaxValue)) match
      case Outcome.Complete(paths, _) => assertEquals(uci(paths), uci(canonical))
      case other                      => fail(s"ample budget exhausted: $other")
  }

  test("kingCapturePath returns the first canonical capture, never an ineligible en-passant route") {
    // Both `e4xd5 d5d6 d6xc7` (legal) and `e4e5 e5xd6 e.p. d6xc7` (illegal since #419) reach the king on c7.
    val state     = parse("8/2k5/8/3p4/4P3/8/8/4K3 w - d6 0 1").withDicePool(List(1, 1, 1))
    val canonical = TurnGenerator.generateAllLegalTurnPaths(state).find(p => SearchScoringFree.capturesKing(state, p))
    assert(canonical.isDefined, "the position must contain a legal king capture")
    kingCapturePath(state, new Budget(Long.MaxValue)) match
      case Outcome.Complete(Some(path), _) =>
        assertEquals(uci(List(path)), uci(canonical.toList))
        assert(!path.exists(_.isEnPassant))
      case other => fail(s"expected a complete witness, got $other")
  }

  test("kingCaptureRolls applies the same eligibility mask to every roll") {
    val state = parse("8/2k5/8/3p4/4P3/8/8/4K3 b - d6 0 1")
    kingCaptureRolls(state, Color.Black, new Budget(Long.MaxValue)) match
      case Outcome.Complete(count, _) =>
        // Oracle: count the rolls whose canonical legal turns contain a king capture.
        val expected = DiceRolls.weighted.iterator.map { (dice, weight) =>
          val rolled = state.withActiveColor(Color.White).withDicePool(dice)
          if TurnGenerator.generateAllLegalTurnPaths(rolled).exists(p => SearchScoringFree.capturesKing(rolled, p))
          then weight
          else 0
        }.sum
        assertEquals(count, expected)
      case other => fail(s"ample budget exhausted: $other")
  }

/** The rules artifact has no `SearchScoring`; replay the path and test the last move for a king capture. */
private object SearchScoringFree:
  def capturesKing(state: GameState, path: List[Move]): Boolean =
    val beforeLast = path.init.foldLeft(state)((s, m) => s.makeMove(m))
    beforeLast.isKingCapture(path.last)
