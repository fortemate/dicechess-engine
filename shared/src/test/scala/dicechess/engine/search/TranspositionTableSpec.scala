// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Unit and ownership-boundary regressions for [[TranspositionTable]] (#59, #397). */
class TranspositionTableSpec extends FunSuite:

  private def parse(dfen: String): GameState = FenParser.parse(dfen).fold(fail(_), identity)

  test("store and probe retrieve exact entries correctly"):
    val tt  = new TranspositionTable(1024)
    val key = 0x123456789abcdef0L

    assertEquals(tt.probe(key), None)
    assertEquals(tt.misses, 1L)

    tt.store(key, 123.45, TTBound.Exact, depth = 2, lossTainted = false)
    assertEquals(tt.stores, 1L)

    val probed = tt.probe(key)
    assert(probed.isDefined)
    val entry = probed.get
    assertEquals(entry.key, key)
    assertEquals(entry.value, 123.45)
    assertEquals(entry.bound, TTBound.Exact)
    assertEquals(entry.depth, 2)
    assertEquals(entry.lossTainted, false)
    assertEquals(tt.hits, 1L)
    assertEquals(tt.hitRate, 0.5) // 1 hit, 1 miss

  test("store and probe preserve bounds and lossTainted"):
    val tt   = new TranspositionTable(256)
    val key1 = 111111L
    val key2 = 222222L

    tt.store(key1, -100.0, TTBound.UpperBound, depth = 1, lossTainted = true)
    tt.store(key2, 50.0, TTBound.LowerBound, depth = 3, lossTainted = false)

    val entry1 = tt.probe(key1).get
    assertEquals(entry1.bound, TTBound.UpperBound)
    assertEquals(entry1.lossTainted, true)

    val entry2 = tt.probe(key2).get
    assertEquals(entry2.bound, TTBound.LowerBound)
    assertEquals(entry2.lossTainted, false)

  test("depth-preferred replacement policy"):
    val tt  = new TranspositionTable(256)
    val key = 99999L

    tt.store(key, 10.0, TTBound.Exact, depth = 1, lossTainted = false)
    assertEquals(tt.probe(key).get.depth, 1)

    // Deeper search replaces shallower search
    tt.store(key, 20.0, TTBound.Exact, depth = 3, lossTainted = false)
    assertEquals(tt.probe(key).get.value, 20.0)
    assertEquals(tt.probe(key).get.depth, 3)

    // Shallower search does NOT replace deeper exact entry
    tt.store(key, 5.0, TTBound.Exact, depth = 1, lossTainted = false)
    assertEquals(tt.probe(key).get.value, 20.0)
    assertEquals(tt.probe(key).get.depth, 3)

  test("telemetry metrics and reset work as expected"):
    val tt = new TranspositionTable(128)

    tt.probe(1L) // miss
    tt.probe(2L) // miss
    tt.store(1L, 1.0, TTBound.Exact, 1, false)
    tt.probe(1L) // hit

    assertEquals(tt.misses, 2L)
    assertEquals(tt.hits, 1L)
    assertEquals(tt.stores, 1L)
    assertEquals(tt.hitRate, 1.0 / 3.0)

    tt.resetTelemetry()
    assertEquals(tt.hits, 0L)
    assertEquals(tt.misses, 0L)
    assertEquals(tt.stores, 0L)
    assertEquals(tt.hitRate, 0.0)

  test("index collisions: an evicting deeper entry wins the slot and the evicted key probes as a miss"):
    // With 256 entries, `key` and `key + 256` share a slot — the branch that could return another position's value.
    val tt   = new TranspositionTable(256)
    val key1 = 512L
    val key2 = key1 + 256L

    tt.store(key1, 10.0, TTBound.Exact, depth = 1, lossTainted = false)
    tt.store(key2, 20.0, TTBound.Exact, depth = 2, lossTainted = false) // deeper → evicts

    assertEquals(tt.probe(key1), None, "evicted key must probe as a miss, never as the winner's value")
    val winner = tt.probe(key2).getOrElse(fail("winning key must be present"))
    assertEquals(winner.key, key2)
    assertEquals(winner.value, 20.0)

    // A shallower collider must NOT evict a deeper entry.
    tt.store(key1, 30.0, TTBound.Exact, depth = 1, lossTainted = false)
    assertEquals(tt.probe(key2).map(_.value), Some(20.0))
    assertEquals(tt.probe(key1), None)

  test("clear wipes table entries"):
    val tt  = new TranspositionTable(128)
    val key = 777L

    tt.store(key, 42.0, TTBound.Exact, 2, false)
    assert(tt.probe(key).isDefined)

    tt.clear()
    assertEquals(tt.probe(key), None)

  test("F6 boundary (#397): inherited en-passant target stays in both Zobrist and GameState keys until endTurn()"):
    val f6 = parse("4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RP")
    assert(f6.enPassant.contains(Square('d', 6)))
    val rookStep = Move(Square('a', 1), Square('a', 2))
    val pawnStep = Move(Square('e', 5), Square('e', 6))
    val midTurn  = f6.makeMove(rookStep)
    // Within a turn, activeColor remains White and inherited d6 en-passant target remains set in both keys:
    assert(midTurn.enPassant.contains(Square('d', 6)))
    val withoutEpMid = midTurn.copy(enPassant = Bitboard.empty, zobristKey = 0L)
    assertNotEquals(midTurn.zobristHash, Zobrist.computeKey(withoutEpMid))
    // After the non-EP turn completes, endTurn() clears the unexercised d6 target:
    val afterTurn = midTurn.makeMove(pawnStep).endTurn()
    assertEquals(afterTurn.enPassant, Bitboard.empty)
    assertNotEquals(f6.zobristHash, afterTurn.zobristHash)
    val evalCache = new EvaluationCache((s, _) => if s.enPassant.contains(Square('d', 6)) then 1 else 0)
    assertEquals(evalCache(midTurn, Color.White), 1)
    assertEquals(evalCache(afterTurn, Color.White), 0)
    assertEquals(evalCache.entryCount, 2)

  test(
    "F8 boundary (#397): commuting pushes share Zobrist and EvaluationCache keys; move counters split only EvaluationCache"
  ):
    val f8    = parse("4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP")
    val paths = TurnGenerator.generateAllLegalTurnPaths(f8)
    val a2a3  = Move(Square('a', 2), Square('a', 3))
    val h2h3  = Move(Square('h', 2), Square('h', 3))
    val path1 = List(a2a3, h2h3)
    val path2 = List(h2h3, a2a3)
    assert(paths.contains(path1) && paths.contains(path2))

    val state1 = path1.foldLeft(f8)(_.makeMove(_)).endTurn()
    val state2 = path2.foldLeft(f8)(_.makeMove(_)).endTurn()
    assertEquals(state1, state2)
    assertEquals(state1.zobristHash, state2.zobristHash)

    // EvaluationCache memoizes the post-endTurn() evaluation while preserving candidate multiplicity (2 paths -> 1 eval call):
    val evalCache = new EvaluationCache(Evaluator.evaluateMaterial)
    val scored    = List(path1, path2).map(p => SearchScoring.scorePath(f8, p, evalCache.apply))
    assertEquals(scored.size, 2)
    assertEquals(evalCache.evaluatorCalls, 1L)
    assertEquals(evalCache.cacheHits, 1L)

    // Counter difference: Zobrist is counter-blind (TT hits across fullMoveNumber / halfMoveClock),
    // whereas EvaluationCache keys by full GameState.equals and keeps them distinct.
    val laterMove = state1.copy(
      flags = state1.flags.withHalfMoveClock(4),
      fullMoveNumber = state1.fullMoveNumber + 3
    )
    assertNotEquals(state1, laterMove)
    assertEquals(state1.zobristHash, laterMove.zobristHash)

    val tt = new TranspositionTable(256)
    tt.store(state1.zobristHash, value = 42.0, bound = TTBound.Exact, depth = 2, lossTainted = false)
    assertEquals(tt.probe(laterMove.zobristHash).map(_.value), Some(42.0))

    val counterAwareEval = new EvaluationCache((s, _) => s.fullMoveNumber * 10 + s.flags.halfMoveClock)
    assertEquals(counterAwareEval(state1, Color.White), 10)
    assertEquals(counterAwareEval(laterMove, Color.White), 44)
    assertEquals(counterAwareEval.evaluatorCalls, 2L)
    assertEquals(counterAwareEval.cacheHits, 0L)
    assertEquals(counterAwareEval.entryCount, 2)

  test("F10 boundary (#397): cancellation is checked before warm hits and Incomplete never writes to TT"):
    val f10 = parse("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP")
    val tt  = new TranspositionTable(1024)
    val key = f10.zobristHash

    def searchSubtree(control: SearchControl, compute: () => SearchOutcome[Double]): SearchOutcome[Double] =
      if control.isCancelled then SearchOutcome.Incomplete(control.workUsed)
      else
        tt.probe(key) match
          case Some(entry) if entry.depth == 2 && entry.bound == TTBound.Exact =>
            SearchOutcome.Exact(entry.value, control.workUsed)
          case _ =>
            val outcome = compute()
            outcome match
              case SearchOutcome.Exact(v, _) =>
                tt.store(key, v, TTBound.Exact, depth = 2, lossTainted = false)
              case SearchOutcome.LowerBound(v, _) =>
                tt.store(key, v, TTBound.LowerBound, depth = 2, lossTainted = false)
              case SearchOutcome.UpperBound(v, _) =>
                tt.store(key, v, TTBound.UpperBound, depth = 2, lossTainted = false)
              case SearchOutcome.Incomplete(_) =>
                () // Incomplete carries no rankable or cacheable value
            outcome

    // 1. Cancelled computation returns Incomplete and never stores an entry:
    val expired = SearchControl.untilDeadline(deadlineNanos = 0L, clockNanos = () => 1L)
    assertEquals(
      searchSubtree(expired, () => SearchOutcome.Exact(99.0, 0L)),
      SearchOutcome.Incomplete(0L)
    )
    assertEquals(tt.stores, 0L)
    assertEquals(tt.hits, 0L)

    // 2. An interrupted mid-tree computation returning Incomplete does not write to TT:
    assertEquals(
      searchSubtree(SearchControl.Unbounded, () => SearchOutcome.Incomplete(7L)),
      SearchOutcome.Incomplete(7L)
    )
    assertEquals(tt.stores, 0L)
    assertEquals(tt.probe(key), None)

    // 3. Completed computation stores Exact; subsequent unbounded lookup hits, but an expired control still returns Incomplete before probing:
    assertEquals(
      searchSubtree(SearchControl.Unbounded, () => SearchOutcome.Exact(125.0, 12L)),
      SearchOutcome.Exact(125.0, 12L)
    )
    assertEquals(tt.stores, 1L)
    assertEquals(
      searchSubtree(SearchControl.Unbounded, () => fail("warm TT hit must bypass recomputation")),
      SearchOutcome.Exact(125.0, 0L)
    )
    assertEquals(tt.hits, 1L)
    assertEquals(
      searchSubtree(expired, () => fail("expired control must stop before probing")),
      SearchOutcome.Incomplete(0L)
    )
    assertEquals(tt.hits, 1L, "expired call must not increment TT hits")
