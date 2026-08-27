package dicechess.engine.bench

import java.nio.file.Files
import scala.concurrent.duration.*

import dicechess.engine.search.BotRegistry
import munit.FunSuite

class OnnxModelScenarioRunnerSpec extends FunSuite:

  override def munitTimeout: Duration = 3.minutes

  private val model           = getClass.getResource("/synthetic_test_model.onnx").getPath
  private val focusedFixtures =
    """{
      |  "schemaVersion": 1,
      |  "id": "model-scenario-runner-test-v1",
      |  "description": "single forced-pass fixture for model scenario runner wiring",
      |  "seedSet": {"id": "single-seed", "values": [0]},
      |  "scenarios": [{
      |    "id": "forced-pass",
      |    "category": "forced-pass",
      |    "description": "neither model has a legal turn",
      |    "dfen": "4k3/8/8/8/8/8/8/4K3 w - - 0 1 PPP",
      |    "expectPass": true
      |  }]
      |}""".stripMargin

  private def run(args: String*) =
    ArenaOptions.parseAndRun(OnnxModelScenarioRunner.command, args.toArray)

  test("requires a model on each side"):
    assert(run().isLeft)
    assert(run(model).isLeft)

  test("rejects an unknown feature set on either side"):
    assert(run(model, model, "--features", "bogus").isLeft)
    assert(run(model, model, "--baseline-features", "bogus").isLeft)

  test("rejects invalid search limits"):
    assert(run(model, model, "--candidate-limit", "0").isLeft)
    assert(run(model, model, "--tt-capacity", "1000").isLeft)

  test("rejects root-rescore tuning without a root-rescore model"):
    val weight = run(model, model, "--rescore-weight", "0.25")
    assert(weight.left.exists(_.contains("require --rescore-model")), weight)
    val features = run(model, model, "--rescore-features", "material")
    assert(features.left.exists(_.contains("require --rescore-model")), features)

  test("registers both sides under distinct ids"):
    assertNotEquals(OnnxModelScenarioRunner.ChallengerId, OnnxModelScenarioRunner.DefenderId)

  test("evaluates twice, records immutable setup, and removes both registrations"):
    val jsonOut     = Files.createTempFile("onnx-model-scenario", ".json")
    val fixturesOut = Files.createTempFile("onnx-model-scenario-fixtures", ".json")
    val output      = new java.io.ByteArrayOutputStream()
    try
      Files.writeString(fixturesOut, focusedFixtures)

      def evaluate() = Console.withOut(output) {
        run(
          model,
          model,
          "--features",
          "material",
          "--baseline-features",
          "material",
          "--candidate-limit",
          "2",
          "--rescore-model",
          model,
          "--rescore-features",
          "material",
          "--rescore-weight",
          "0.5",
          "--pre-rank-with-model",
          "--tt-capacity",
          "1024",
          "--fixtures",
          fixturesOut.toString,
          "--json",
          jsonOut.toString
        )
      }

      val first = evaluate()
      assert(first.isRight, first)
      assertEquals(BotRegistry.getAlgorithm(OnnxModelScenarioRunner.ChallengerId), None)
      assertEquals(BotRegistry.getAlgorithm(OnnxModelScenarioRunner.DefenderId), None)

      val second = evaluate()
      assert(second.isRight, second)
      assertEquals(BotRegistry.getAlgorithm(OnnxModelScenarioRunner.ChallengerId), None)
      assertEquals(BotRegistry.getAlgorithm(OnnxModelScenarioRunner.DefenderId), None)

      val stdout = output.toString
      assert(stdout.contains("Model scenario evaluation:"), stdout)
      assert(stdout.contains("Expectation hits:"), stdout)

      val json = Json.parse(Files.readString(jsonOut)).fold(error => fail(error), identity)
      assertEquals(json.field("kind").flatMap(_.asStr), Some("search_evaluation"))
      assertEquals(json.field("search").flatMap(_.asStr), Some("expectimax"))
      assertEquals(json.field("searchDepth").flatMap(_.asNum), Some(2.0))
      assertEquals(json.field("candidateLimit").flatMap(_.asNum), Some(2.0))
      assertEquals(json.field("preRankWithModel").flatMap(_.asBool), Some(true))
      assertEquals(json.field("transpositionCapacity").flatMap(_.asNum), Some(1024.0))

      val expectedSha = DepthDuelCheckpoint.sha256(model).fold(error => fail(error), identity)
      val challenger  = json.field("challengerModel").getOrElse(fail("missing challenger model"))
      val defender    = json.field("defenderModel").getOrElse(fail("missing defender model"))
      val rootRescore = json.field("rootRescore").getOrElse(fail("missing root rescore"))
      assertEquals(challenger.field("sha256").flatMap(_.asStr), Some(expectedSha))
      assertEquals(defender.field("sha256").flatMap(_.asStr), Some(expectedSha))
      assertEquals(rootRescore.field("sha256").flatMap(_.asStr), Some(expectedSha))
      assertEquals(rootRescore.field("features").flatMap(_.asStr), Some("material"))
      assertEquals(rootRescore.field("weight").flatMap(_.asNum), Some(0.5))
    finally
      Files.deleteIfExists(fixturesOut)
      Files.deleteIfExists(jsonOut)

  test("holds all scenario configuration fields"):
    val config = OnnxModelScenarioConfig(
      challengerModel = "candidate.onnx",
      defenderModel = "oracle.onnx",
      challengerFeatures = "rich",
      defenderFeatures = "rich",
      candidateLimit = 8,
      rescoreModel = Some("kcp.onnx"),
      rescoreFeatures = Some("kcp"),
      rescoreWeight = Some(0.5),
      preRankWithModel = true,
      ttCapacity = Some(262144),
      fixturePath = Some("core-v1.json"),
      jsonPath = Some("report.json")
    )
    assertEquals(config.candidateLimit, 8)
    assertEquals(config.rescoreModel, Some("kcp.onnx"))
    assertEquals(config.ttCapacity, Some(262144))
