// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import BudgetedRules.*

/** Per-edge en-passant eligibility, canonical ordering and budget boundaries for #446. */
class BudgetedRulesEpConsumptionSuite extends munit.FunSuite:
  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get
  private val white                         = "4k3/8/8/3pP3/4P3/8/8/4K3 w - d6 0 1 PPP"
  // Independently constructed 180-degree rotation with exchanged colors, including the EP target.
  private val black    = "3k4/8/8/3p4/3pP3/8/8/3K4 b - e3 0 1 PPP"
  private val fixtures = List(
    white,
    black,
    "4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RRP",
    "r3k3/8/8/8/3Pp3/8/8/4K3 b q d3 0 1 RRP",
    "4k3/8/8/3p4/4P3/8/8/4K3 w - d6 0 1 PPP",
    "4k3/8/8/4p3/3P4/8/8/4K3 b - d3 0 1 PPP",
    "8/2k5/8/3pP3/4P3/8/8/4K3 w - d6 0 1 PPP",
    "4k3/8/8/4p3/3Pp3/8/2K5/8 b - d3 0 1 PPP"
  )
  private type Row = (List[Move], GameState, Boolean)

  private def uci(path: List[Move]): String =
    path.map(m => m.fromSquare.toNotation + m.toSquare.toNotation).mkString(" ")

  private def complete[A](outcome: Outcome[A]): (A, Long) = outcome match
    case Outcome.Complete(value, used) => (value, used)
    case other                         => fail(s"expected completion: $other")

  private def canonicalRows(state: GameState): List[Row] =
    val rows = List.newBuilder[Row]
    TurnGenerator.forEachLegalTurnState(state) { (moves, len, result, king) =>
      val path = moves.take(len).toList
      val raw  = path.foldLeft(state)((s, m) => s.makeMove(m))
      assertEquals(result, if king then raw else raw.endTurn())
      assertEquals(result.activeColor, if king then state.activeColor else state.activeColor.opponent)
      assertEquals(result.dicePool, Nil)
      rows += ((path, result, king))
      true
    }
    rows.result()

  for (fen, forbidden) <- List(white -> "e5e6 e4e5 e5d6", black -> "d4d3 d5d4 d4e3") do
    test(s"a replacement pawn cannot inherit eligibility: $forbidden") {
      val state     = parse(fen)
      val canonical = TurnGenerator.generateAllLegalTurnPaths(state)
      assertEquals(canonical.size, 17)
      assert(!canonical.map(uci).contains(forbidden))
      val (paths, _) = complete(turnPaths(state, new Budget(Long.MaxValue)))
      assert(!paths.map(uci).contains(forbidden))
      assertEquals(paths.size, 17)
      assertEquals(paths, canonical)
    }

  test("visitors and folds preserve ordered path multiplicity, states and terminal ownership") {
    for fen <- fixtures do
      val state    = parse(fen)
      val expected = canonicalRows(state)
      if fixtures.drop(6).contains(fen) then
        assert(expected.exists(_._3), "capture fixture must exercise terminal ownership")
        assert(expected.exists(row => !row._3), "capture fixture must also exercise normal endTurn ownership")
      val visited      = List.newBuilder[Row]
      val (summary, _) = complete(visitTurnStates(state, new Budget(Long.MaxValue)) { (moves, len, result, king) =>
        visited += ((moves.take(len).toList, result, king))
        true
      })
      assertEquals(visited.result(), expected, fen)
      assertEquals(summary, TurnVisitSummary(expected.size, expected.size, false))
      val (folded, _) = complete(foldTurnStates(state, List.empty[Row], new Budget(Long.MaxValue)) {
        (acc, path, result, king) => ((path, result, king) :: acc, true)
      })
      assertEquals(folded, TurnFoldResult(expected.reverse, false), fen)
      assertEquals(complete(turnPaths(state, new Budget(Long.MaxValue)))._1, expected.map(_._1), fen)
  }

  test("unmoved original pawns retain eligibility on the second and third micro-moves and sibling branches") {
    for fen <- fixtures.slice(2, 4) do
      val state      = parse(fen)
      val (paths, _) = complete(turnPaths(state, new Budget(Long.MaxValue)))
      for index <- 0 to 2 do
        assert(paths.exists(p => p.length > index && p(index).isEnPassant), s"missing EP at $index: $fen")
      assertEquals(paths, TurnGenerator.generateAllLegalTurnPaths(state))
    for fen <- fixtures.slice(4, 6) do
      val (paths, _) = complete(turnPaths(parse(fen), new Budget(Long.MaxValue)))
      assert(!paths.exists(_.exists(_.isEnPassant)), fen)
  }

  test("capture witnesses and weighted-roll existence match canonical traversal for both colors") {
    for fen <- fixtures do
      val state         = parse(fen)
      var expectedCount = 0
      for (dice, weight) <- DiceRolls.weighted do
        val rolled   = state.withDicePool(dice)
        val expected = TurnGenerator.generateAllLegalTurnPaths(rolled).find { path =>
          path.init.foldLeft(rolled)((s, m) => s.makeMove(m)).isKingCapture(path.last)
        }
        val (actual, _) = complete(kingCapturePath(rolled, new Budget(Long.MaxValue)))
        assertEquals(actual, expected, s"$fen / $dice")
        if expected.nonEmpty then expectedCount += weight
      val defender = state.activeColor.opponent
      // Incoming side and dice must not control the rolled attacker's traversal.
      val incoming = state.withActiveColor(defender).withDicePool(List(6))
      assertEquals(complete(kingCaptureRolls(incoming, defender, new Budget(Long.MaxValue)))._1, expectedCount, fen)
  }

  test("visitor and fold early stop keep the corrected total and first canonical state") {
    for fen <- fixtures do
      val state                = parse(fen)
      val expected             = canonicalRows(state)
      val (_, work)            = complete(turnPaths(state, new Budget(Long.MaxValue)))
      val visited              = List.newBuilder[Row]
      val (summary, visitWork) = complete(visitTurnStates(state, new Budget(work)) { (moves, len, result, king) =>
        visited += ((moves.take(len).toList, result, king))
        false
      })
      assertEquals(visited.result(), expected.take(1))
      assertEquals(summary, TurnVisitSummary(1, expected.size, true))
      assertEquals(visitWork, work)
      val (folded, foldWork) = complete(foldTurnStates(state, List.empty[Row], new Budget(work)) {
        (_, path, result, king) => (List((path, result, king)), false)
      })
      assertEquals(folded, TurnFoldResult(expected.take(1), true))
      assertEquals(foldWork, work)
  }

  test("absence proofs spend only canonical prefix work, including each independently rolled tree") {
    // In these sparse positions every path spends all Pawn/King dice; other piece dice cannot move.
    // Kings are too far apart to capture. Thus canonical paths retain every explored branch, and their
    // distinct prefixes give an independent work oracle, including generator calls at dead ends.
    for fen <- List(
        "7k/8/8/3pP3/4P3/8/8/K7 w - d6 0 1 PPP",
        "7k/8/8/3p4/3pP3/8/8/K7 b - e3 0 1 PPP"
      )
    do
      val state    = parse(fen)
      var rollWork = DiceRolls.weighted.length.toLong
      for (dice, _) <- DiceRolls.weighted do
        val rolled   = state.withDicePool(dice)
        val paths    = TurnGenerator.generateAllLegalTurnPaths(rolled)
        val playable = dice.count(d => d == 1 || d == 6)
        assert(paths.forall(_.size == playable))
        val prefixes = paths.flatMap(p => (1 to p.size).map(p.take)).distinct
        // Capture DFS skips generation only once all three structural dice slots are spent.
        val work = 1L + prefixes.size + prefixes.count(_.size < 3)
        assertEquals(kingCapturePath(rolled, new Budget(work)), Outcome.Complete(None, work), s"$fen / $dice")
        rollWork += work
      assertEquals(
        kingCaptureRolls(state, state.activeColor.opponent, new Budget(rollWork)),
        Outcome.Complete(0, rollWork)
      )
  }

  test("exact completion and every smaller budget preserve atomic incomplete semantics") {
    for fen <- List(white, black, fixtures(6), fixtures(7)) do
      val state         = parse(fen)
      val (paths, work) = complete(turnPaths(state, new Budget(Long.MaxValue)))
      assertEquals(turnPaths(state, new Budget(work)), Outcome.Complete(paths, work))
      for limit <- 0L to work do
        var callbacks     = 0
        var foldCallbacks = 0
        val visited       = visitTurnStates(state, new Budget(limit)) { (_, _, _, _) =>
          callbacks += 1
          true
        }
        val folded = foldTurnStates(state, 0, new Budget(limit)) { (acc, _, _, _) =>
          foldCallbacks += 1
          (acc + 1, true)
        }
        if limit == work then
          assertEquals(visited, Outcome.Complete(TurnVisitSummary(paths.size, paths.size, false), work))
          assertEquals(folded, Outcome.Complete(TurnFoldResult(paths.size, false), work))
          assertEquals(callbacks, paths.size)
          assertEquals(foldCallbacks, paths.size)
        else
          assertEquals(turnPaths(state, new Budget(limit)), Outcome.Incomplete(limit))
          assertEquals(visited, Outcome.Incomplete(limit))
          assertEquals(folded, Outcome.Incomplete(limit))
          assertEquals(callbacks, 0)
          assertEquals(foldCallbacks, 0)
      val (witness, witnessWork) = complete(kingCapturePath(state, new Budget(Long.MaxValue)))
      assertEquals(kingCapturePath(state, new Budget(witnessWork)), Outcome.Complete(witness, witnessWork))
      for limit <- 0L until witnessWork do
        assertEquals(kingCapturePath(state, new Budget(limit)), Outcome.Incomplete(limit))
      val defender          = state.activeColor.opponent
      val (count, rollWork) = complete(kingCaptureRolls(state, defender, new Budget(Long.MaxValue)))
      assertEquals(kingCaptureRolls(state, defender, new Budget(rollWork)), Outcome.Complete(count, rollWork))
      for limit <- List(0L, 1L, rollWork / 2, rollWork - 1).distinct do
        assertEquals(kingCaptureRolls(state, defender, new Budget(limit)), Outcome.Incomplete(limit))
      val exhausted = new Budget(work - 1)
      assertEquals(turnPaths(state, exhausted), Outcome.Incomplete(work - 1))
      assertEquals(kingCapturePath(state, exhausted), Outcome.Incomplete(work - 1))
      assertEquals(kingCaptureRolls(state, defender, exhausted), Outcome.Incomplete(work - 1))
  }
