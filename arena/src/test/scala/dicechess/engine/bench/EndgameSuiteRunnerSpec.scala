// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import munit.FunSuite
import dicechess.engine.json.Json

class EndgameSuiteRunnerSpec extends FunSuite:

  test("computePositionSummary accurately assigns attacker and defender roles"):
    val suite = EndgameBenchmarkCatalog.load(None).toOption.get
    val pos   = suite.positions.find(_.id == "kq-vs-k-centralized").get

    // Suppose opponentAlgo (candidate) won 2 as White, lost 0 as White (gamesAsWhite = 2)
    // and won 0 as Black, lost 2 as Black (gamesAsBlack = 2)
    val dummyResult = MatchResult(
      totalGames = 4,
      winsAsWhite = 2,
      winsAsBlack = 0,
      lossesAsWhite = 0,
      lossesAsBlack = 2,
      drawsAsWhite = 0,
      drawsAsBlack = 0,
      durationMs = 100L
    )

    val summary = EndgameSuiteRunner.computePositionSummary(pos, dummyResult)
    assertEquals(summary.candidateWins, 2)
    assertEquals(summary.baselineWins, 2)
    assertEquals(summary.candWinsAsAttacker, 2)
    assertEquals(summary.candLossesAsAttacker, 0)
    assertEquals(summary.candAttackerWinRate, 100.0)
    assertEquals(summary.candWinsAsDefender, 0)
    assertEquals(summary.candLossesAsDefender, 2)
    assertEquals(summary.candDefenderWinRate, 0.0)

  test("computeCategorySummaries correctly aggregates stats"):
    val suite = EndgameBenchmarkCatalog.load(None).toOption.get
    val pos1  = suite.positions.find(_.id == "kq-vs-k-centralized").get
    val pos2  = suite.positions.find(_.id == "kq-vs-k-edge").get

    val r1 = MatchResult(2, 1, 0, 0, 1, 0, 0, 50L)
    val r2 = MatchResult(2, 1, 0, 0, 1, 0, 0, 50L)

    val s1 = EndgameSuiteRunner.computePositionSummary(pos1, r1)
    val s2 = EndgameSuiteRunner.computePositionSummary(pos2, r2)

    val categories = EndgameSuiteRunner.computeCategorySummaries(List(s1, s2))
    assertEquals(categories.size, 1)
    assertEquals(categories.head.category, "lone-king-vs-kq")
    assertEquals(categories.head.positionCount, 2)
    assertEquals(categories.head.totalGames, 4)
    assertEquals(categories.head.candidateWins, 2)
    assertEquals(categories.head.baselineWins, 2)
    assertEquals(categories.head.candAttackerWinRate, 100.0)
    assertEquals(categories.head.candDefenderWinRate, 0.0)

  test("buildJsonReport generates valid schema"):
    val suite = EndgameBenchmarkCatalog.load(None).toOption.get
    val pos   = suite.positions.head
    val res   = MatchResult(2, 1, 0, 0, 1, 0, 0, 50L)
    val s     = EndgameSuiteRunner.computePositionSummary(pos, res)
    val cats  = EndgameSuiteRunner.computeCategorySummaries(List(s))
    val json  = EndgameSuiteRunner.buildJsonReport(suite, "cand", "base", 1, 42L, List(s), cats)

    assertEquals(json.field("suiteId").flatMap(_.asStr), Some("endgames-v1"))
    assertEquals(json.field("candidateId").flatMap(_.asStr), Some("cand"))
    assertEquals(json.field("baselineId").flatMap(_.asStr), Some("base"))
    val rendered = Json.render(json)
    assert(rendered.contains("\"suiteId\":\"endgames-v1\""))

  test("runSuite executes end-to-end for a filtered category"):
    val summaries = EndgameSuiteRunner.runSuite(
      candidateBotId = "aggressive",
      baselineBotId = "greedy",
      gamesPerColor = 1,
      seed = 42L,
      suitePath = None,
      categoryFilter = Some("lone-king-vs-kq"),
      jsonPath = None
    )
    assertEquals(summaries.size, 4)
    assert(summaries.forall(_.totalGames == 2))
