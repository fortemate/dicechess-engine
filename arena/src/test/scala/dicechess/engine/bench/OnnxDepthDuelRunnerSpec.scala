package dicechess.engine.bench

import scala.concurrent.duration.*

import munit.FunSuite

/** Argument handling and end-to-end wiring for [[OnnxDepthDuelRunner]], against the throwaway synthetic model shared
  * from rootJVM's test resources.
  */
class OnnxDepthDuelRunnerSpec extends FunSuite:

  override def munitTimeout: Duration = 3.minutes

  private val model = getClass.getResource("/synthetic_test_model.onnx").getPath

  private def run(args: String*) =
    ArenaOptions.parseAndRun(OnnxDepthDuelRunner.command, args.toArray)

  test("requires a model path"):
    assert(run().isLeft)

  test("rejects an unknown feature set"):
    assert(run(model, "--features", "bogus").isLeft)

  test("rejects non-positive candidate limit or game count"):
    assert(run(model, "--candidate-limit", "0").isLeft)
    assert(run(model, "--games", "0").isLeft)

  test("rejects invalid time control presets"):
    assert(run(model, "--presets", "0+2").isLeft)
    assert(run(model, "--presets", "").isLeft)

  test("wires challenger and defender to fixed search depths"):
    val challenger = OnnxDepthDuelRunner.configForDepth(5, OnnxDepthDuelRunner.ChallengerDepth)
    val defender   = OnnxDepthDuelRunner.configForDepth(5, OnnxDepthDuelRunner.DefenderDepth)
    assertEquals(challenger.candidateLimit, 5)
    assertEquals(challenger.searchDepth, 3)
    assertEquals(defender.candidateLimit, 5)
    assertEquals(defender.searchDepth, 2)

  test("runs a depth duel and records setup metadata in JSON report"):
    val out = java.nio.file.Files.createTempFile("onnx-depth-duel", ".json")
    try
      val result = run(
        model,
        "--features",
        "material",
        "--games",
        "1",
        "--candidate-limit",
        "2",
        "--presets",
        "1+0",
        "--seed",
        "7",
        "--json",
        out.toString
      )
      assert(result.isRight, result)
      val json        = Json.parse(java.nio.file.Files.readString(out)).fold(error => fail(error), identity)
      val setup       = json.field("setup").getOrElse(fail("missing setup metadata"))
      val timedResult = json
        .field("results")
        .flatMap(_.asArr)
        .flatMap(_.headOption)
        .getOrElse(fail("missing timed result"))
      val policies = timedResult.field("timePolicies").getOrElse(fail("missing time policy metadata"))
      assertEquals(json.field("kind").flatMap(_.asStr), Some("timed_arena"))
      assertEquals(setup.field("challengerDepth").flatMap(_.asStr), Some("3"))
      assertEquals(setup.field("defenderDepth").flatMap(_.asStr), Some("2"))
      assertEquals(setup.field("candidateLimit").flatMap(_.asStr), Some("2"))
      assertEquals(setup.field("features").flatMap(_.asStr), Some("material"))
      assertEquals(setup.field("timePolicy").flatMap(_.asStr), Some("empirical-v1"))
      assertEquals(policies.field("bot").flatMap(_.asStr), Some("empirical-v1"))
      assertEquals(policies.field("baseline").flatMap(_.asStr), Some("empirical-v1"))
    finally java.nio.file.Files.deleteIfExists(out)

  test("runs a depth duel with SPRT stopping configuration"):
    val out = java.nio.file.Files.createTempFile("onnx-depth-duel-sprt", ".json")
    try
      val result = run(
        model,
        "--features",
        "material",
        "--games",
        "1",
        "--candidate-limit",
        "2",
        "--presets",
        "1+0",
        "--sprt",
        "0,20,0.05,0.05",
        "--seed",
        "42",
        "--json",
        out.toString
      )
      assert(result.isRight, result)
      val json = java.nio.file.Files.readString(out)
      assert(json.contains("\"sprt\""), json)
    finally java.nio.file.Files.deleteIfExists(out)
