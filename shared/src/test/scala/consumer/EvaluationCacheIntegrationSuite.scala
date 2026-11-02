// SPDX-License-Identifier: AGPL-3.0-only
package consumer

import dicechess.engine.domain.*
import dicechess.engine.search.*
import munit.FunSuite

import scala.util.Random

/** Compiles consumer usage outside the engine package on every target. */
class EvaluationCacheIntegrationSuite extends FunSuite:
  private def parse(dfen: String): GameState = FenParser.parse(dfen).fold(fail(_), identity)

  private def scoreCandidates(
      state: GameState,
      candidates: List[List[Move]],
      fullEval: (GameState, Color) => Int,
      mayContinue: () => Boolean
  ): List[ScoredSequence] =
    val cachedEval = new EvaluationCache(fullEval)
    candidates.iterator
      .takeWhile(_ => mayContinue())
      .map { moves =>
        SearchScoring.scorePath(state, moves, cachedEval.apply)
      }
      .toList

  test("legal transposing paths preserve scores, multiplicity, shortlist, chosen path and next RNG value") {
    val state = parse("7k/8/8/8/8/8/P6P/4K3 w - - 0 1 PP")
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    assert(paths.nonEmpty)
    assert(paths.forall(_.length == 2))
    val finalStates = paths.map(_.foldLeft(state)((s, m) => s.makeMove(m)).endTurn())
    assert(finalStates.distinct.size < paths.size, "fixture must contain real transpositions")

    var observed                            = List.empty[(GameState, Color)]
    val fullEval: (GameState, Color) => Int = (s, c) =>
      observed = (s, c) :: observed
      Evaluator.evaluateMaterial(s, c)
    val cachedEval = new EvaluationCache(fullEval)
    val cached     = paths.map(path => SearchScoring.scorePath(state, path, cachedEval.apply))
    val uncached   = paths.map(path => SearchScoring.scorePath(state, path, Evaluator.evaluateMaterial))
    assertEquals(cached, uncached)
    assertEquals(cached.map(_.moves), paths)
    assertEquals(observed.map(_._1).toSet, finalStates.toSet)
    assert(observed.forall((s, c) => s.activeColor == Color.Black && s.dicePool.isEmpty && c == Color.White))
    assertEquals(cachedEval.evaluatorCalls, finalStates.distinct.size.toLong)
    assertEquals(cachedEval.cacheHits, (paths.size - finalStates.distinct.size).toLong)
    assertEquals(cachedEval.entryCount, finalStates.distinct.size)

    def shortlist(scored: List[ScoredSequence]): List[ScoredSequence] =
      val best = scored.map(_.score).max
      scored.filter(_.score == best)
    val cachedBest = shortlist(cached)
    val plainBest  = shortlist(uncached)
    assertEquals(cachedBest, plainBest)
    assert(cachedBest.size > 1, "fixture must exercise RNG tie-breaking")
    (0L until 20L).foreach { seed =>
      val cachedRng = new Random(seed)
      val plainRng  = new Random(seed)
      assertEquals(cachedBest(cachedRng.nextInt(cachedBest.size)), plainBest(plainRng.nextInt(plainBest.size)))
      assertEquals(cachedRng.nextLong(), plainRng.nextLong())
    }
  }

  test("black mover is evaluated after endTurn, including full-move count and EP cleanup") {
    val state      = parse("4k3/p7/8/8/4P3/8/8/7K b - e3 0 7 p")
    val path       = TurnGenerator.generateAllLegalTurnPaths(state).head
    val expected   = path.foldLeft(state)((s, m) => s.makeMove(m)).endTurn()
    val cachedEval = new EvaluationCache((s, c) =>
      assertEquals(s, expected)
      assertEquals(s.activeColor, Color.White)
      assertEquals(s.fullMoveNumber, 8)
      assert(!s.enPassant.contains(Square('e', 3)))
      assertEquals(c, Color.Black)
      73
    )
    assertEquals(SearchScoring.scorePath(state, path, cachedEval.apply), ScoredSequence(path, 73))
    assertEquals(cachedEval.evaluatorCalls, 1L)
  }

  test("king capture bypasses the callback and cache telemetry entirely") {
    val state = parse("k7/8/8/8/8/8/8/R6K w - - 0 1 R")
    val paths = TurnGenerator.generateAllLegalTurnPaths(state).filter(SearchScoring.isTerminalWin(state, _))
    assert(paths.nonEmpty)
    val cachedEval = new EvaluationCache((_, _) => fail("terminal capture must bypass evaluator"))
    paths.foreach { path =>
      assertEquals(SearchScoring.scorePath(state, path, cachedEval.apply).score, SearchScoring.TerminalWinScore)
    }
    assertEquals(cachedEval.evaluatorCalls, 0L)
    assertEquals(cachedEval.cacheHits, 0L)
    assertEquals(cachedEval.entryCount, 0)
  }

  test("empty paths preserve scorePath's current-state and current-mover contract") {
    val state      = parse("7k/8/8/8/8/8/P6P/4K3 w - - 0 1 PP")
    val cachedEval = new EvaluationCache((s, c) =>
      assertEquals(s, state)
      assertEquals(c, state.activeColor)
      -5
    )
    assertEquals(SearchScoring.scorePath(state, Nil, cachedEval.apply), ScoredSequence(Nil, -5))
  }

  test("consumer checks cancellation before warm hits and retains fallback ownership") {
    val state  = parse("7k/8/8/8/8/8/P6P/4K3 w - - 0 1 PP")
    val path   = TurnGenerator.generateAllLegalTurnPaths(state).head
    var guards = 0
    var calls  = 0
    val scored = scoreCandidates(
      state,
      List(path, path, path),
      (_, _) => { calls += 1; 42 },
      () =>
        guards += 1
        guards <= 2
    )
    assertEquals(scored, List(ScoredSequence(path, 42), ScoredSequence(path, 42)))
    assertEquals(guards, 3)
    assertEquals(calls, 1)
    val fallback = ScoredSequence(Nil, -1)
    assertEquals(
      scoreCandidates(state, List(path), (_, _) => fail("cancelled"), () => false).headOption.getOrElse(fallback),
      fallback
    )
  }
