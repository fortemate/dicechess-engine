// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.util.Random

class TacticianSearchSuite extends FunSuite:
  private def parse(fen: String): GameState        = FenParser.parse(fen).fold(fail(_), identity)
  private def bot(limit: Int = 4): TacticianSearch = new TacticianSearch(TacticianConfig(limit, 100, 1000, 1.0))

  test("invalid configurations are rejected") {
    intercept[IllegalArgumentException](TacticianConfig(0, 0, 0, 0.0))
    intercept[IllegalArgumentException](TacticianConfig(1, -1, 0, 0.0))
    intercept[IllegalArgumentException](TacticianConfig(1, 0, -1, 0.0))
    for value <- List(-1.0, Double.NaN, Double.PositiveInfinity) do
      intercept[IllegalArgumentException](TacticianConfig(1, 0, 0, value))
  }

  test("a forced pass returns None without threat queries") {
    var stats  = TacticianStats(-1, -1, -1, -1)
    val search = new TacticianSearch(TacticianConfig(1, 1, 1, 0.0), s => stats = s)
    assertEquals(search.findBestMove(parse("7k/8/8/8/8/8/8/K7 w - - 0 1 NN")), None)
    assertEquals(stats, TacticianStats(0, 0, 0, 0))
  }

  test("immediate wins bypass rescoring and retain the shortest winning path") {
    var stats  = TacticianStats(-1, -1, -1, -1)
    val search = new TacticianSearch(TacticianConfig(1, 0, Int.MaxValue, 0.0), s => stats = s)
    val state  = parse("7k/8/8/8/8/8/8/K6R w - - 0 1 RRP")
    val result = search.findBestMove(state, new Random(1)).get
    assertEquals(result.score, SearchScoring.TerminalWinScore)
    assertEquals(result.moves.size, 1)
    assertEquals(result.moves.head.toSquare, Square('h', 8))
    assertEquals(stats.threatQueries, 0)
  }

  test("a multi-action king capture cannot be removed by a one-state shortlist") {
    val state  = parse("7k/8/8/8/8/8/8/RK6 w - - 0 1 RR")
    val result = bot(1).findBestMove(state, new Random(2)).get
    assertEquals(result.score, SearchScoring.TerminalWinScore)
    assertEquals(result.moves.size, 2)
  }

  test("duplicate paths occupy only one complete-state shortlist slot") {
    var stats  = TacticianStats(0, 0, 0, 0)
    val search = new TacticianSearch(TacticianConfig(3, 0, 0, 0.0), s => stats = s)
    val state  = parse(FenParser.InitialPosition).withDicePool(List(1, 1, 2))
    val result = search.findBestMove(state, new Random(3)).get
    assert(TurnGenerator.generateAllLegalTurnPaths(state).contains(result.moves))
    assert(stats.legalPaths > stats.distinctStates)
    assertEquals(stats.rescoredStates, 3)
    assertEquals(stats.threatQueries, 0)
  }

  test("the returned score agrees with independent roll-by-roll capture probabilities") {
    val state    = parse("4k3/8/8/3n4/8/4K3/8/R7 w - - 0 1 R")
    val config   = TacticianConfig(100, 100, 1000, 1.0)
    val search   = new TacticianSearch(config)
    val result   = search.findBestMove(state, new Random(4)).get
    val after    = result.moves.foldLeft(state)((s, move) => s.makeMove(move)).endTurn()
    val color    = state.activeColor
    val expected = Evaluator.evaluateAggressive(after, color).toDouble - Evaluator.evaluateKingSafety(after, color) +
      PieceSafety.hangingMaterial(after, color.opponent) - PieceSafety.hangingMaterial(after, color) +
      config.kingThreatCp * KingCaptureProbability.kingCaptureProbability(after, color.opponent) -
      config.kingDangerCp * KingCaptureProbability.kingCaptureProbability(after, color)
    assertEquals(result.score, math.rint(expected).toInt)
    val referenceBest = TurnGenerator
      .generateAllLegalTurnPaths(state)
      .map { path =>
        val leaf  = path.foldLeft(state)((s, move) => s.makeMove(move)).endTurn()
        val score = Evaluator.evaluateAggressive(leaf, color).toDouble - Evaluator.evaluateKingSafety(leaf, color) +
          PieceSafety.hangingMaterial(leaf, color.opponent) - PieceSafety.hangingMaterial(leaf, color) +
          config.kingThreatCp * KingCaptureProbability.kingCaptureProbability(leaf, color.opponent) -
          config.kingDangerCp * KingCaptureProbability.kingCaptureProbability(leaf, color)
        math.rint(score).toInt
      }
      .max
    assertEquals(result.score, referenceBest)
  }

  test("equal seeds give equal choices across invocations") {
    val state  = parse(FenParser.InitialPosition).withDicePool(List(1, 2, 3))
    val search = bot()
    val first  = search.findBestMove(state, new Random(5))
    assertEquals(first, search.findBestMove(state, new Random(5)))
    assert(search.findBestMove(state).isDefined)
  }

  test("chosen paths retain maximal dice use, castling, promotion and en-passant legality") {
    val states = List(
      parse(FenParser.InitialPosition).withDicePool(List(1, 1, 2)),
      parse("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1 KR"),
      parse("k7/4P3/8/8/8/8/8/4K3 w - - 0 1 P"),
      parse("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1 PP")
    )
    for (state, index) <- states.zipWithIndex do
      val result = bot().findBestMove(state, new Random(index)).get
      assert(TurnGenerator.generateAllLegalTurnPaths(state).contains(result.moves))
  }

  test("king threat coverage agrees with KCP for either attacking side on special positions") {
    val states = List(
      "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1",
      "k7/4P3/8/8/8/8/8/4K3 w - - 0 1",
      "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
      "r5r1/2pk2R1/p1nb4/1p4P1/NPP5/3PK3/P2BP3/R5N1 w - - 3 13"
    ).map(parse)
    for state <- states; attacker <- List(Color.White, Color.Black) do
      val defenders = if attacker.isWhite then state.blackPieces else state.whitePieces
      val target    = Square.fromIndex(java.lang.Long.numberOfTrailingZeros((state.kings & defenders).value))
      val actual    = KingThreat.threatWeight(state.withActiveColor(attacker).withDicePool(Nil), target, 3)
      val expected  = math.round(KingCaptureProbability.kingCaptureProbability(state, attacker.opponent) * 216).toInt
      assertEquals(actual, expected)
  }

  test("extreme finite settings cannot produce the terminal-win sentinel") {
    val search = new TacticianSearch(TacticianConfig(1, Int.MaxValue, Int.MaxValue, Double.MaxValue))
    val state  = parse("6k1/8/8/8/8/1n6/8/K6R w - - 0 1 R")
    assert(search.findBestMove(state, new Random(6)).get.score < SearchScoring.TerminalWinScore)
  }
