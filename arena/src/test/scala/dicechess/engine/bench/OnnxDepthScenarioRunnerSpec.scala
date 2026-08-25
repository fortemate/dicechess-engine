package dicechess.engine.bench

import java.nio.file.Files
import scala.concurrent.duration.*

import dicechess.engine.search.BotRegistry
import munit.FunSuite

class OnnxDepthScenarioRunnerSpec extends FunSuite:

  override def munitTimeout: Duration = 3.minutes

  private val model           = getClass.getResource("/synthetic_test_model.onnx").getPath
  private val focusedFixtures =
    """{
      |  "schemaVersion": 1,
      |  "id": "depth-runner-test-v1",
      |  "description": "single forced-pass fixture for depth runner wiring",
      |  "seedSet": {"id": "single-seed", "values": [0]},
      |  "scenarios": [{
      |    "id": "forced-pass",
      |    "category": "forced-pass",
      |    "description": "neither depth has a legal turn",
      |    "dfen": "4k3/8/8/8/8/8/8/4K3 w - - 0 1 PPP",
      |    "expectPass": true
      |  }]
      |}""".stripMargin

  private def run(args: String*) =
    ArenaOptions.parseAndRun(OnnxDepthScenarioRunner.command, args.toArray)

  test("requires a model parameter"):
    assert(run().isLeft)

  test("rejects an unknown feature set"):
    assert(run(model, "--features", "bogus").isLeft)

  test("rejects a non-positive candidate limit"):
    assert(run(model, "--candidate-limit", "0").isLeft)

  test("wires candidate and baseline to distinct ids and fixed depths"):
    assertNotEquals(OnnxDepthScenarioRunner.CandidateId, OnnxDepthScenarioRunner.BaselineId)
    val candidate = OnnxDepthScenarioRunner.configForDepth(5, 3)
    val baseline  = OnnxDepthScenarioRunner.configForDepth(5, 2)
    assertEquals(candidate.candidateLimit, 5)
    assertEquals(candidate.searchDepth, 3)
    assertEquals(baseline.candidateLimit, 5)
    assertEquals(baseline.searchDepth, 2)

  test("rejects a non-existent fixture path"):
    val result = run(model, "--fixtures", "/path/does/not/exist.json")
    assert(result.isLeft)
    assert(result.left.exists(_.contains("failed to read fixture file")))

  test("evaluates categories twice without leaving stale registrations"):
    val jsonOut     = Files.createTempFile("onnx-depth-scenario", ".json")
    val fixturesOut = Files.createTempFile("onnx-depth-fixtures", ".json")
    val output      = new java.io.ByteArrayOutputStream()
    try
      Files.writeString(fixturesOut, focusedFixtures)

      def evaluate() = Console.withOut(output) {
        run(
          model,
          "--features",
          "material",
          "--candidate-limit",
          "2",
          "--fixtures",
          fixturesOut.toString,
          "--json",
          jsonOut.toString
        )
      }

      val first = evaluate()
      assert(first.isRight, first)
      assertEquals(BotRegistry.getAlgorithm(OnnxDepthScenarioRunner.CandidateId), None)
      assertEquals(BotRegistry.getAlgorithm(OnnxDepthScenarioRunner.BaselineId), None)

      val second = evaluate()
      assert(second.isRight, second)
      assertEquals(BotRegistry.getAlgorithm(OnnxDepthScenarioRunner.CandidateId), None)
      assertEquals(BotRegistry.getAlgorithm(OnnxDepthScenarioRunner.BaselineId), None)

      val stdout = output.toString
      assert(stdout.contains("Depth scenario evaluation:"), stdout)
      assert(stdout.contains("Expectation hits:"), stdout)
      assert(stdout.contains("Candidate improvements:"), stdout)
      assert(stdout.contains("Category summary:"), stdout)
      assert(stdout.contains("forced-pass"), stdout)

      val jsonStr = Files.readString(jsonOut)
      val json    = Json.parse(jsonStr).fold(error => fail(error), identity)
      assertEquals(json.field("kind").flatMap(_.asStr), Some("search_evaluation"))
      assertEquals(json.field("schemaVersion").flatMap(_.asNum), Some(1.0))
      assertEquals(json.field("modelPath").flatMap(_.asStr), Some(model))
      assertEquals(json.field("featureSet").flatMap(_.asStr), Some("material"))
      assertEquals(json.field("candidateLimit").flatMap(_.asNum), Some(2.0))
      assertEquals(json.field("candidateDepth").flatMap(_.asNum), Some(3.0))
      assertEquals(json.field("baselineDepth").flatMap(_.asNum), Some(2.0))
      val categories = json.field("categorySummaries").flatMap(_.asArr).getOrElse(fail("missing category summaries"))
      assertEquals(categories.size, 1)
      assertEquals(categories.head.field("category").flatMap(_.asStr), Some("forced-pass"))
      assert(json.field("results").flatMap(_.asArr).isDefined)
    finally
      Files.deleteIfExists(fixturesOut)
      Files.deleteIfExists(jsonOut)
