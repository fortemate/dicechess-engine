package dicechess.engine.bench

import java.nio.file.Files

import dicechess.engine.domain.{FenParser, GameState, Move, Square}
import dicechess.engine.search.{ScoredSequence, SearchAlgorithm, SearchScoring}
import munit.FunSuite

class SearchEvaluationSpec extends FunSuite:
  private val passDfen = "4k3/8/8/8/8/8/8/4K3 w - - 0 1 PPP"

  private def scenario(id: String, category: String, expectation: String = "\"expectPass\": true"): String =
    s"""{
       |  "id": "$id",
       |  "category": "$category",
       |  "description": "$id description",
       |  "dfen": "$passDfen",
       |  $expectation
       |}""".stripMargin

  private def catalog(
      version: Int = 1,
      scenarios: List[String] = List(
        scenario("tactical-pass", "tactical"),
        scenario("defensive-pass", "defensive"),
        scenario("endgame-pass", "endgame"),
        scenario("forced-pass", "forced-pass")
      )
  ): String =
    s"""{
       |  "schemaVersion": $version,
       |  "id": "test-v1",
       |  "description": "test catalog",
       |  "seedSet": {"id": "test-seeds", "values": [0, 7]},
       |  "scenarios": [${scenarios.mkString(",")}]
       |}""".stripMargin

  private def state(dfen: String): GameState =
    FenParser.parse(dfen).fold(error => fail(error), identity)

  test("the bundled catalog is canonical and covers every search category"):
    val fixtures = SearchFixtureCatalog.load(None).fold(error => fail(error), identity)

    assertEquals(fixtures.id, "core-search-v1")
    assertEquals(fixtures.seedSetId, "stable-0-7-42-v1")
    assertEquals(fixtures.seeds, List(0L, 7L, 42L))
    assertEquals(fixtures.scenarios.size, 12)
    assertEquals(fixtures.scenarios.map(_.category).toSet, SearchScenarioCategory.values.toSet)
    fixtures.scenarios.foreach(scenario => assertEquals(FenParser.serialize(scenario.state), scenario.dfen))

  test("a valid external fixture file uses the same parser as the bundled catalog"):
    val path = Files.createTempFile("search-evaluation", ".json")
    try
      Files.writeString(path, catalog())
      val fixtures = SearchFixtureCatalog.load(Some(path.toString)).fold(error => fail(error), identity)
      assertEquals(fixtures.id, "test-v1")
      assertEquals(fixtures.scenarios.size, 4)
    finally Files.deleteIfExists(path)

  test("fixture parsing rejects unsupported schemas, duplicate seeds, and duplicate scenario ids"):
    val unsupported = SearchFixtureCatalog.parse(catalog(version = 2))
    assert(unsupported.left.exists(_.contains("unsupported fixture schemaVersion 2")))

    val duplicateSeeds = catalog().replace("[0, 7]", "[7, 7]")
    assert(SearchFixtureCatalog.parse(duplicateSeeds).left.exists(_.contains("must not contain duplicates")))

    val duplicateIds = List(
      scenario("same", "tactical"),
      scenario("same", "defensive"),
      scenario("endgame-pass", "endgame"),
      scenario("forced-pass", "forced-pass")
    )
    assert(SearchFixtureCatalog.parse(catalog(scenarios = duplicateIds)).left.exists(_.contains("ids must be unique")))

  test("fixture parsing accepts focused catalogs but rejects unknown categories and fields"):
    val focused = catalog(scenarios = List(scenario("only", "tactical")))
    assert(SearchFixtureCatalog.parse(focused).isRight)

    val unknownCategory = catalog(scenarios = List(scenario("only", "strategic")))
    assert(SearchFixtureCatalog.parse(unknownCategory).left.exists(_.contains("unknown category 'strategic'")))

    val unknownField =
      catalog().replace("\"description\": \"test catalog\"", "\"description\": \"test catalog\", \"typo\": 1")
    assert(SearchFixtureCatalog.parse(unknownField).left.exists(_.contains("unknown fields: typo")))

  test("fixture parsing validates canonical DFEN and expectations against legal turns"):
    val nonCanonical = catalog().replace(passDfen, "4k3/8/8/8/8/8/8/4K3 w - - 0 1 RPP")
    assert(SearchFixtureCatalog.parse(nonCanonical).left.exists(_.contains("dfen is not canonical")))

    val rookDfen    = "k7/8/8/8/8/8/8/R3K3 w - - 0 1 R"
    val illegalTurn = scenario(
      "illegal-turn",
      "tactical",
      expectation = "\"expectedTurns\": [[\"a1b2\"]]"
    ).replace(passDfen, rookDfen)
    val scenarios = List(
      illegalTurn,
      scenario("defensive-pass", "defensive"),
      scenario("endgame-pass", "endgame"),
      scenario("forced-pass", "forced-pass")
    )
    assert(SearchFixtureCatalog.parse(catalog(scenarios = scenarios)).left.exists(_.contains("contains illegal turns")))

    val falsePass = catalog().replaceFirst("\"expectPass\": true", "\"expectPass\": false")
    assert(SearchFixtureCatalog.parse(falsePass).left.exists(_.contains("expectPass must be true")))

  test("comparison classifies improvements, regressions, matches, and misses"):
    val dfen     = "k7/8/8/8/8/8/8/R3K3 w - - 0 1 R"
    val scenario = SearchScenario(
      "capture",
      SearchScenarioCategory.Tactical,
      "capture the king",
      dfen,
      state(dfen),
      SearchExpectation.Turns(List(List("a1a8")))
    )
    val fixtures = SearchFixtureSet("test", "test", "one", List(42L), List(scenario))
    val oracle   = new SearchAlgorithm:
      override def findBestMove(state: GameState): Option[ScoredSequence] =
        val _ = state
        Some(
          ScoredSequence(
            List(Move(Square('a', 1), Square('a', 8), Move.Capture)),
            SearchScoring.TerminalWinScore
          )
        )
    val passing = new SearchAlgorithm:
      override def findBestMove(state: GameState): Option[ScoredSequence] =
        val _ = state
        None

    def evaluate(baseline: SearchAlgorithm, candidate: SearchAlgorithm): SearchEvaluationReport =
      SearchEvaluation.evaluate(
        fixtures,
        SearchBotIdentity("baseline", "Baseline"),
        baseline,
        SearchBotIdentity("candidate", "Candidate"),
        candidate
      )

    val improved = evaluate(passing, oracle)
    assertEquals(improved.results.head.comparison, SearchComparison.CandidateImproved)
    assertEquals(improved.summary.candidateImprovements, 1)
    assertEquals(improved.summary.baselineIllegal, 1)

    val regressed = evaluate(oracle, passing)
    assertEquals(regressed.results.head.comparison, SearchComparison.CandidateRegressed)
    assertEquals(regressed.summary.candidateRegressions, 1)
    assertEquals(regressed.summary.candidateIllegal, 1)

    val matched = evaluate(oracle, oracle)
    assertEquals(matched.results.head.comparison, SearchComparison.BothMatched)
    assert(matched.results.head.sameDecision)

    val missed = evaluate(passing, passing)
    assertEquals(missed.results.head.comparison, SearchComparison.BothMissed)
    assert(missed.results.head.sameDecision)

  test("running the same bot on both sides is reproducible for every fixture and seed"):
    val fixtures = SearchFixtureCatalog.load(None).fold(error => fail(error), identity)
    val first    = SearchEvaluation.run(fixtures, "greedy", "greedy").fold(error => fail(error), identity)
    val second   = SearchEvaluation.run(fixtures, "greedy", "greedy").fold(error => fail(error), identity)

    assertEquals(first.results.map(_.baseline), second.results.map(_.baseline))
    assertEquals(first.summary.runs, fixtures.scenarios.size * fixtures.seeds.size)
    assertEquals(first.summary.sameDecisions, first.summary.runs)
    assertEquals(first.summary.baselineExpectationHits, first.summary.candidateExpectationHits)

  test("the report JSON preserves experiment identity and per-scenario decisions"):
    val fixtures = SearchFixtureCatalog.load(None).fold(error => fail(error), identity)
    val report   = SearchEvaluation.run(fixtures, "greedy", "greedy").fold(error => fail(error), identity)
    val parsed   = Json.parse(Json.render(SearchEvaluation.toJson(report))).fold(error => fail(error), identity)

    assertEquals(parsed.field("kind").flatMap(_.asStr), Some("search_evaluation"))
    assertEquals(parsed.field("schemaVersion").flatMap(_.asNum), Some(1.0))
    assertEquals(parsed.field("fixtureSet").flatMap(_.field("id")).flatMap(_.asStr), Some("core-search-v1"))
    assertEquals(parsed.field("fixtureSet").flatMap(_.field("schemaVersion")).flatMap(_.asNum), Some(1.0))
    assertEquals(parsed.field("seedSet").flatMap(_.field("id")).flatMap(_.asStr), Some("stable-0-7-42-v1"))
    assertEquals(parsed.field("results").flatMap(_.asArr).map(_.size), Some(report.summary.runs))

  test("the CLI prints a human report and optionally writes the machine-readable report"):
    val jsonPath = Files.createTempFile("search-evaluation-report", ".json")
    val output   = new java.io.ByteArrayOutputStream()
    try
      val result = Console.withOut(output) {
        ArenaOptions.parseAndRun(
          SearchEvaluationRunner.command,
          Array("--bot", "greedy", "--baseline", "greedy", "--json", jsonPath.toString)
        )
      }
      assertEquals(result, Right(()))
      assert(output.toString.contains("Search evaluation: greedy vs greedy"))
      assert(output.toString.contains("Expectation hits:"))
      val json = Json.parse(Files.readString(jsonPath)).fold(error => fail(error), identity)
      assertEquals(json.field("kind").flatMap(_.asStr), Some("search_evaluation"))
    finally Files.deleteIfExists(jsonPath)

  test("an unknown bot is rejected before any scenario runs"):
    val fixtures = SearchFixtureCatalog.load(None).fold(error => fail(error), identity)
    assertEquals(
      SearchEvaluation.run(fixtures, "does-not-exist", "greedy"),
      Left("Unknown baseline bot 'does-not-exist'")
    )
