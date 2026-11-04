// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite
import scala.concurrent.duration.*

/** Cross-class traversal regressions for #442. The oracle uses an uncached recursive list traversal and plain replay,
  * independently of packed paths, prefix caches and the resulting-state visitors.
  */
class SharedTurnTraversalSuite extends FunSuite:
  override val munitTimeout: Duration = 120.seconds

  private type Entry = (List[Int], String, Long, Boolean)

  private val fixtures = List(
    "initial"                                     -> FenParser.InitialPosition,
    "all piece types"                             -> "7k/8/5b2/3n4/2P5/1N1BR3/5Q2/K7 w - - 7 12",
    "sparse king"                                 -> "4k3/8/8/8/8/8/8/4K3 w - - 5 12",
    "commuting pawns"                             -> "4k3/8/8/8/8/8/PP6/4K3 w - - 0 1",
    "blocked pawn"                                -> "4k3/8/8/8/8/p7/P7/4K3 w - - 0 1",
    "white castling"                              -> "4k3/8/8/8/8/8/8/R3K2R w KQ - 3 12",
    "black castling"                              -> "r3k2r/8/8/8/8/8/8/4K3 b kq - 3 12",
    "white one-step promotion"                    -> "k7/4P3/8/8/8/8/8/4K3 w - - 0 1",
    "white two-step promotion"                    -> "k7/8/4P3/8/8/8/8/4K3 w - - 0 1",
    "black promotion"                             -> "4k3/8/8/8/8/8/4p3/K7 b - - 0 12",
    "capture promotion"                           -> "k4r2/4P3/8/8/8/8/8/4K3 w - - 0 1",
    "white en passant"                            -> "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
    "black en passant"                            -> "4k3/8/8/8/3Pp3/8/8/4K3 b - d3 0 12",
    "pawn moves onto an inherited EP rank"        -> "4k3/8/8/3p4/4P3/8/8/4K3 w - d6 0 1",
    "eligible pawn leaves its original square"    -> "4k3/8/8/3pPP2/8/8/8/4K3 w - d6 0 1",
    "white EP eligibility cannot transfer (#446)" -> "4k3/8/8/3pP3/4P3/8/8/4K3 w - d6 0 1",
    "black EP eligibility cannot transfer (#446)" -> "4k3/8/8/4p3/3Pp3/8/8/4K3 b - d3 0 1",
    "king captures"                               -> "8/8/8/8/8/4k3/4R3/4K3 w - - 0 1"
  )

  fixtures.foreach { (name, fen) =>
    test(s"every class matches independent exhaustive generation and plain replay: $name"):
      val initial = parse(fen)
      val shared  = new TurnGenerator.SharedTraversal(initial)
      val classes = ActiveDicePartitioner.partitionsFor(initial)
      assertEquals(classes.iterator.map(_.weight).sum, 216)
      // A second pass in reverse order exercises warmed caches and makes class-order dependence visible.
      for entry <- classes.toList ++ classes.toList.reverse do
        val rolled   = entry.roll.applyToState(initial)
        val expected = exhaustive(rolled).map(path => result(rolled, path))
        val actual   = collect(shared, entry.roll)
        assertEquals(actual, expected, s"$name roll=${entry.roll.toList}")
        assertEquals(actual.map(_._1), TurnGenerator.generateAllLegalTurnPaths(rolled).map(_.map(_.toInt)))
  }

  test("the full visitor preserves duplicate resulting states from distinct commuting paths"):
    val initial = parse("4k3/8/8/8/8/8/PP6/4K3 w - - 0 1")
    val roll    = pool(1, 1)
    val entries = collect(new TurnGenerator.SharedTraversal(initial), roll)
    assert(entries.size > entries.map(_._2).distinct.size)
    assertEquals(entries, exhaustive(roll.applyToState(initial)).map(result(roll.applyToState(initial), _)))

  test("shared prefixes retain the 17 legal replacement-pawn paths from #446"):
    for (fen, forbidden) <- List(
        "4k3/8/8/3pP3/4P3/8/8/4K3 w - d6 0 1" -> "e5e6 e4e5 e5d6",
        "3k4/8/8/3p4/3pP3/8/8/3K4 b - e3 0 1" -> "d4d3 d5d4 d4e3"
      )
    do
      val initial   = parse(fen)
      val traversal = new TurnGenerator.SharedTraversal(initial)
      // Warm shorter overlapping classes before requesting the discriminating three-pawn roll.
      collect(traversal, pool(1))
      collect(traversal, pool(1, 1))
      val paths   = List.newBuilder[String]
      val outcome = traversal.visit(pool(1, 1, 1)) { (moves, len, _, _) =>
        paths += moves.take(len).map(m => m.fromSquare.toNotation + m.toSquare.toNotation).mkString(" ")
        true
      }
      assertComplete(outcome, 17, stopped = false)
      assert(!paths.result().contains(forbidden))

  test("promotion-retaining classes can move every underpromoted piece in the same turn"):
    val initial   = parse("k7/4P3/8/8/8/8/8/4K3 w - - 0 1")
    val traversal = new TurnGenerator.SharedTraversal(initial)
    for die <- 2 to 5 do
      val roll = pool(1, die, 6)
      assert(ActiveDicePartitioner.partitionsFor(initial).exists(_.roll == roll))
      val entries = collect(traversal, roll)
      assert(entries.exists(_._1.size == 3), s"promoted piece die=$die must remain usable")
      assertEquals(entries, exhaustive(roll.applyToState(initial)).map(result(roll.applyToState(initial), _)))

  test("a caller stop returns exactly the canonical prefix for capture and normal phases"):
    for (fen, dice) <- List(
        ("8/8/8/8/8/4k3/4R3/4K3 w - - 0 1", List(4, 4)),
        ("4k3/8/8/8/8/8/8/4K3 w - - 0 1", List(6, 6)),
        ("4k3/8/8/8/8/8/8/4K2R w K - 0 1", List(4, 6, 6))
      )
    do
      val initial     = parse(fen)
      val roll        = CompactRoll.fromSortedList(dice)
      val expected    = exhaustive(roll.applyToState(initial)).map(result(roll.applyToState(initial), _))
      val firstNormal = expected.indexWhere(!_._4)
      val stops       = List(1, 2, firstNormal + 1, expected.size).filter(n => n > 0 && n <= expected.size).distinct
      for stop <- stops do
        val traversal = new TurnGenerator.SharedTraversal(initial)
        val entries   = List.newBuilder[Entry]
        var count     = 0
        val outcome   = traversal.visit(roll) { (moves, len, state, capture) =>
          entries += entry(moves.take(len).toList, state, capture)
          count += 1
          count < stop
        }
        assertEquals(entries.result(), expected.take(stop))
        assertComplete(outcome, stop, stopped = true)
        // A caller stop is local to this visit and must not poison later classes or visits.
        assertEquals(collect(traversal, roll), expected)

  test("a proven king capture stops generation before the remaining branches are explored"):
    val initial = parse("8/8/8/8/8/4k3/4R3/4K3 w - - 0 1")
    val roll    = pool(4, 4, 4)
    val full    = new TurnGenerator.SharedTraversal(initial)
    collect(full, roll)
    val early = new TurnGenerator.SharedTraversal(initial)
    assertComplete(early.visit(roll) { (_, _, _, capture) => assert(capture); false }, 1, stopped = true)
    assert(early.work.examined < full.work.examined)
    assert(early.work.transitions < full.work.transitions)
    assertEquals(early.work.emitted, 1L)

  test("normal early stop still proves maximality and avoids remaining result replay"):
    val initial = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val roll    = pool(6, 6, 6)
    val full    = new TurnGenerator.SharedTraversal(initial)
    collect(full, roll)
    val early = new TurnGenerator.SharedTraversal(initial)
    assertComplete(early.visit(roll) { (_, _, _, capture) => assert(!capture); false }, 1, stopped = true)
    assertEquals(early.work.generated, full.work.generated)
    assertEquals(early.work.examined, full.work.examined)
    assert(early.work.transitions < full.work.transitions)

  test("prefix generation is reused across classes while every per-class edge remains accounted"):
    val initial = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val rolls   = List(pool(6), pool(6, 6), pool(6, 6, 6))
    val shared  = new TurnGenerator.SharedTraversal(initial)
    rolls.foreach(roll => collect(shared, roll))
    val independent = rolls.map { roll =>
      val traversal = new TurnGenerator.SharedTraversal(initial)
      collect(traversal, roll)
      traversal.work
    }
    assert(shared.work.generated < independent.map(_.generated).sum)
    assertEquals(shared.work.examined, independent.map(_.examined).sum)
    val before = shared.work
    collect(shared, rolls.last)
    assertEquals(shared.work.generated, before.generated)
    assert(shared.work.examined > before.examined)

  test("every insufficient fresh budget remains incomplete and emits no unproved normal turn"):
    val initial        = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val roll           = pool(6, 6)
    val measuredBudget = new BudgetedRules.Budget(Long.MaxValue)
    val complete       = new TurnGenerator.SharedTraversal(initial, Some(measuredBudget))
    val expected       = collect(complete, roll)
    val needed         = measuredBudget.used
    assert(needed > 1)
    for limit <- 0L until needed do
      val budget    = new BudgetedRules.Budget(limit)
      val traversal = new TurnGenerator.SharedTraversal(initial, Some(budget))
      var calls     = 0
      val outcome   = traversal.visit(roll) { (_, _, _, _) => calls += 1; true }
      assertEquals(outcome, BudgetedRules.Outcome.Incomplete(limit))
      assertEquals(calls, 0)
      assert(budget.exhausted)
      assertEquals(
        traversal.visit(CompactRoll.Empty)((_, _, _, _) => fail("exhaustion is not a pass")),
        BudgetedRules.Outcome.Incomplete(limit)
      )
    val exact = new TurnGenerator.SharedTraversal(initial, Some(new BudgetedRules.Budget(needed)))
    assertEquals(collect(exact, roll), expected)

  test("shared budgets are cumulative and charge a cached revisit's examined edges"):
    val initial   = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val roll      = pool(6)
    val budget    = new BudgetedRules.Budget(Long.MaxValue)
    val traversal = new TurnGenerator.SharedTraversal(initial, Some(budget))
    collect(traversal, roll)
    val used   = budget.used
    val before = traversal.work
    collect(traversal, roll)
    assertEquals(traversal.work.generated, before.generated)
    assertEquals(budget.used - used, traversal.work.examined - before.examined)

  test("empty and blocked classes are complete forced passes without fabricated callbacks"):
    val initial   = parse("4k3/8/8/8/8/p7/P7/4K3 w - - 0 1")
    val traversal = new TurnGenerator.SharedTraversal(initial)
    for roll <- List(CompactRoll.Empty, pool(1), pool(1, 1, 1), pool(5)) do
      assertComplete(traversal.visit(roll)((_, _, _, _) => fail("forced pass has no path")), 0, stopped = false)

  test("cancellation is sticky, including before an empty class, and never denotes a pass"):
    val initial   = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    var cancelled = true
    val traversal = new TurnGenerator.SharedTraversal(initial, cancelled = () => cancelled)
    assertEquals(
      traversal.visit(CompactRoll.Empty)((_, _, _, _) => fail("cancelled")),
      BudgetedRules.Outcome.Incomplete(0L)
    )
    cancelled = false
    assertEquals(
      traversal.visit(pool(6))((_, _, _, _) => fail("sticky cancellation")),
      BudgetedRules.Outcome.Incomplete(0L)
    )

  test("cancellation during generation exposes no partially explored normal turn"):
    val initial   = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    var checks    = 0
    val traversal = new TurnGenerator.SharedTraversal(initial, cancelled = () => { checks += 1; checks > 5 })
    var calls     = 0
    val outcome   = traversal.visit(pool(6, 6, 6)) { (_, _, _, _) => calls += 1; true }
    assert(outcome match
      case BudgetedRules.Outcome.Incomplete(_) => true
      case _                                   => false)
    assertEquals(calls, 0)

  test("a proved capture may precede cancellation but the visit still reports incomplete"):
    val initial   = parse("8/8/8/8/8/4k3/4R3/4K3 w - - 0 1")
    var cancelled = false
    val traversal = new TurnGenerator.SharedTraversal(initial, cancelled = () => cancelled)
    var calls     = 0
    val outcome   = traversal.visit(pool(4, 4, 4)) { (_, _, state, capture) =>
      assert(capture)
      assertEquals(state.kings & state.blackPieces, Bitboard.empty)
      assertEquals(state.activeColor, Color.White)
      calls += 1
      cancelled = true
      true
    }
    assert(outcome match
      case BudgetedRules.Outcome.Incomplete(_) => true
      case _                                   => false)
    assertEquals(calls, 1)

  test("a callback stopping on a proved terminal wins over cancellation raised in that callback"):
    val initial      = parse("8/8/8/8/8/4k3/4R3/4K3 w - - 0 1")
    var cancelled    = false
    var checks       = 0
    val traversal    = new TurnGenerator.SharedTraversal(initial, cancelled = () => { checks += 1; cancelled })
    var checksAtStop = 0
    val outcome      = traversal.visit(pool(4, 4, 4)) { (_, _, _, capture) =>
      assert(capture)
      cancelled = true
      checksAtStop = checks
      false
    }
    assertComplete(outcome, 1, stopped = true)
    assertEquals(checks, checksAtStop, "no cancellation poll follows a completed stopping proof")
    val later = traversal.visit(pool(4))((_, _, _, _) => fail("later class must observe cancellation"))
    assert(later match
      case BudgetedRules.Outcome.Incomplete(_) => true
      case _                                   => false)

  test("cancellation during normal replay reports incomplete after only proved maximal callbacks"):
    val initial   = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val roll      = pool(6, 6, 6)
    val rolled    = roll.applyToState(initial)
    val expected  = exhaustive(rolled).map(result(rolled, _))
    var cancelled = false
    val traversal = new TurnGenerator.SharedTraversal(initial, cancelled = () => cancelled)
    val entries   = List.newBuilder[Entry]
    val outcome   = traversal.visit(roll) { (moves, len, state, capture) =>
      assert(!capture)
      assertEquals(len, 3, "partial discovery must not lower the maximal-dice requirement")
      entries += entry(moves.take(len).toList, state, capture)
      cancelled = true
      true
    }
    assert(outcome match
      case BudgetedRules.Outcome.Incomplete(_) => true
      case _                                   => false)
    assertEquals(entries.result(), expected.take(1))
    assertEquals(traversal.work.emitted, 1L)

  test("same-instance recursion is rejected and does not prevent subsequent visits"):
    val initial   = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val roll      = pool(6)
    val traversal = new TurnGenerator.SharedTraversal(initial)
    assertComplete(
      traversal.visit(roll) { (_, _, _, _) =>
        intercept[IllegalArgumentException] {
          traversal.visit(roll)((_, _, _, _) => fail("reentrant callback"))
        }
        false
      },
      1,
      stopped = true
    )
    assertEquals(
      collect(traversal, roll),
      exhaustive(roll.applyToState(initial)).map(result(roll.applyToState(initial), _))
    )

  test("the established state visitor preserves an incoming unsorted dice pool's canonical order"):
    val initial = parse("4k3/8/8/8/8/8/P7/4K3 w - - 0 1")
    for dice <- List(List(6, 1), List(6, 1, 6), List(1, 6, 1)) do
      val rolled  = initial.withDicePool(dice)
      val entries = List.newBuilder[Entry]
      TurnGenerator.forEachLegalTurnState(rolled) { (moves, len, state, capture) =>
        entries += entry(moves.take(len).toList, state, capture)
        true
      }
      assertEquals(entries.result(), exhaustive(rolled).map(result(rolled, _)))

  test("borrowed paths use one array per visit and nested independent invocations cannot overwrite it"):
    val initial                    = parse("4k3/8/8/8/8/8/PP6/4K3 w - - 0 1")
    val roll                       = pool(1, 1)
    val traversal                  = new TurnGenerator.SharedTraversal(initial)
    var first: Option[Array[Move]] = None
    val entries                    = List.newBuilder[Entry]
    val outcome                    = traversal.visit(roll) { (moves, len, state, capture) =>
      first match
        case Some(array) => assert(array eq moves)
        case None        => first = Some(moves)
      val retained      = moves.take(len).toList
      val nestedInitial = parse("4k3/8/8/8/8/8/8/4K3 b - - 0 12")
      val nested        = new TurnGenerator.SharedTraversal(nestedInitial)
      val nestedRoll    = pool(6)
      assertEquals(
        collect(nested, nestedRoll),
        exhaustive(nestedRoll.applyToState(nestedInitial)).map(result(nestedRoll.applyToState(nestedInitial), _))
      )
      assertEquals(moves.take(len).toList, retained)
      entries += entry(retained, state, capture)
      true
    }
    val expected = exhaustive(roll.applyToState(initial)).map(result(roll.applyToState(initial), _))
    assertComplete(outcome, expected.size, stopped = false)
    assertEquals(entries.result(), expected)

  test("mutating borrowed callback slots cannot corrupt discovery or later classes"):
    val initial   = parse("8/8/8/8/8/4k3/4R3/4K3 w - - 0 1")
    val traversal = new TurnGenerator.SharedTraversal(initial)
    for weighted <- ActiveDicePartitioner.partitionsFor(initial) do
      val rolled   = weighted.roll.applyToState(initial)
      val expected = exhaustive(rolled).map(result(rolled, _))
      val entries  = List.newBuilder[Entry]
      var count    = 0
      val outcome  = traversal.visit(weighted.roll) { (moves, len, state, capture) =>
        entries += entry(moves.take(len).toList, state, capture)
        moves(0) = Move.empty
        moves(1) = Move.empty
        moves(2) = Move.empty
        count += 1
        true
      }
      assertComplete(outcome, count, stopped = false)
      assertEquals(entries.result(), expected, s"roll=${weighted.roll.toList}")

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  test("unsupported packed die faces preserve the legacy paths of the remaining valid dice"):
    for
      fen  <- List("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1", "8/8/8/8/8/4k3/4R3/4K3 w - - 0 1")
      dice <- List(List(7), List(7, 7, 1), List(7, 6), List(6, 7), List(7, 4, 6), List(4, 7, 6), List(4, 6, 7))
    do
      val initial    = parse(fen)
      val rolled     = initial.withDicePool(dice)
      val expected   = exhaustive(rolled).map(result(rolled, _))
      val standalone = List.newBuilder[Entry]
      val count      = TurnGenerator.forEachLegalTurnState(rolled) { (moves, length, state, capture) =>
        standalone += entry(moves.take(length).toList, state, capture)
        true
      }
      assertEquals(count, expected.size)
      assertEquals(standalone.result(), expected)
      val shared  = List.newBuilder[Entry]
      val outcome = new TurnGenerator.SharedTraversal(initial).visitFlags(rolled.flags) {
        (moves, length, state, capture) =>
          shared += entry(moves.take(length).toList, state, capture)
          true
      }
      assertComplete(outcome, expected.size, stopped = false)
      assertEquals(shared.result(), expected)

  private def pool(dice: Int*): CompactRoll = CompactRoll.fromSortedList(dice.toList)

  private def entry(path: List[Move], state: GameState, capture: Boolean): Entry =
    (path.map(_.toInt), FenParser.serialize(state), state.zobristHash, capture)

  private def result(initial: GameState, path: List[Move]): Entry =
    val beforeLast = path.init.foldLeft(initial)((s, move) => s.makeMove(move))
    val capture    = beforeLast.isKingCapture(path.last)
    val raw        = beforeLast.makeMove(path.last)
    entry(path, if capture then raw else raw.endTurn(), capture)

  private def collect(traversal: TurnGenerator.SharedTraversal, roll: CompactRoll): List[Entry] =
    val entries = List.newBuilder[Entry]
    var count   = 0
    val outcome = traversal.visit(roll) { (moves, len, state, capture) =>
      entries += entry(moves.take(len).toList, state, capture)
      count += 1
      true
    }
    assertComplete(outcome, count, stopped = false)
    entries.result()

  private def assertComplete(
      outcome: BudgetedRules.Outcome[TurnGenerator.SharedVisitSummary],
      count: Int,
      stopped: Boolean
  ): Unit =
    outcome match
      case BudgetedRules.Outcome.Complete(summary, _) =>
        assertEquals(summary.emittedPaths, count)
        assertEquals(summary.stoppedEarly, stopped)
      case other => fail(s"expected complete traversal, got $other")

  private def exhaustive(initial: GameState): List[List[Move]] =
    val paths              = walkExhaustive(initial, Nil, MoveGenerator.computeEligibleEpPawns(initial))
    val maximum            = paths.map(_.map(move => if move.isCastling then 2 else 1).sum).maxOption.getOrElse(0)
    val (captures, normal) = paths.partition { path =>
      path.init.foldLeft(initial)((s, move) => s.makeMove(move)).isKingCapture(path.last)
    }
    captures ++ normal.filter(_.map(move => if move.isCastling then 2 else 1).sum == maximum)

  private def walkExhaustive(state: GameState, path: List[Move], eligible: Bitboard): List[List[Move]] =
    val continuations = MoveGenerator.generateMoves(state, eligible).flatMap { move =>
      val nextPath = path :+ move
      if state.isKingCapture(move) then List(nextPath)
      else continueExhaustive(state, nextPath, eligible, move)
    }
    if continuations.isEmpty && path.nonEmpty then List(path) else continuations

  private def continueExhaustive(
      state: GameState,
      path: List[Move],
      eligible: Bitboard,
      move: Move
  ): List[List[Move]] =
    val required  = if move.isCastling then List(4, 6) else List(state.mailbox(move.fromSquare).pieceType.diceValue)
    val remaining = state.dicePool.diff(required)
    if state.dicePool.size - remaining.size != required.size then Nil
    else
      val nextEligible =
        if move.isEnPassant || eligible.contains(move.fromSquare) then eligible.remove(move.fromSquare)
        else eligible
      walkExhaustive(state.makeMove(move).withDicePool(remaining), path, nextEligible)
