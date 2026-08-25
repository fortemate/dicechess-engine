package dicechess.engine.bench

import scala.concurrent.duration.*

import dicechess.engine.domain.Color
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

  test("checkpoint requires one control and a path distinct from the final report"):
    val path = java.nio.file.Files.createTempFile("depth-duel-options", ".json")
    try
      assert(run(model, "--checkpoint", path.toString).isLeft)
      assert(
        run(
          model,
          "--presets",
          "1+0",
          "--checkpoint",
          path.toString,
          "--json",
          path.toString
        ).isLeft
      )
    finally java.nio.file.Files.deleteIfExists(path)

  test("wires challenger and defender to fixed search depths"):
    val challenger = OnnxDepthDuelRunner.configForDepth(5, OnnxDepthDuelRunner.ChallengerDepth)
    val defender   = OnnxDepthDuelRunner.configForDepth(5, OnnxDepthDuelRunner.DefenderDepth)
    assertEquals(challenger.candidateLimit, 5)
    assertEquals(challenger.searchDepth, 3)
    assertEquals(defender.candidateLimit, 5)
    assertEquals(defender.searchDepth, 2)

  test("runs a depth duel and records setup metadata in JSON report"):
    val out        = java.nio.file.Files.createTempFile("onnx-depth-duel", ".json")
    val checkpoint = java.nio.file.Files.createTempFile("onnx-depth-duel", ".checkpoint.json")
    java.nio.file.Files.delete(checkpoint)
    try
      val args = Seq(
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
        out.toString,
        "--checkpoint",
        checkpoint.toString
      )
      val result = run(args*)
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
      assertEquals(setup.field("modelSha256").flatMap(_.asStr).map(_.length), Some(64))
      assertEquals(setup.field("timePolicy").flatMap(_.asStr), Some("empirical-v1"))
      assertEquals(policies.field("bot").flatMap(_.asStr), Some("empirical-v1"))
      assertEquals(policies.field("baseline").flatMap(_.asStr), Some("empirical-v1"))
      val saved = Json.parse(java.nio.file.Files.readString(checkpoint)).fold(error => fail(error), identity)
      assertEquals(saved.field("kind").flatMap(_.asStr), Some("depth_duel_checkpoint"))
      assertEquals(saved.field("completedPairs").flatMap(_.asNum), Some(1.0))

      // The cap is already complete: a second invocation restores the result without replaying pair zero.
      val resumed = run(args*)
      assert(resumed.isRight, resumed)
      val restoredCheckpoint =
        Json.parse(java.nio.file.Files.readString(checkpoint)).fold(error => fail(error), identity)
      val restoredReport      = Json.parse(java.nio.file.Files.readString(out)).fold(error => fail(error), identity)
      val restoredTimedResult = restoredReport
        .field("results")
        .flatMap(_.asArr)
        .flatMap(_.headOption)
        .getOrElse(fail("missing restored timed result"))
      assertEquals(restoredCheckpoint.field("completedPairs").flatMap(_.asNum), Some(1.0))
      for field <- List("totalGames", "wins", "losses", "draws", "durationMs") do
        assertEquals(restoredTimedResult.field(field), timedResult.field(field))
      assertEquals(restoredTimedResult.field("latencyMs"), timedResult.field("latencyMs"))
    finally
      java.nio.file.Files.deleteIfExists(out)
      java.nio.file.Files.deleteIfExists(checkpoint)

  test("checkpoint codec round-trips raw observations and rejects setup or cap mismatches"):
    val path               = java.nio.file.Files.createTempFile("depth-duel-codec", ".json")
    val checkpointIdentity = DepthDuelCheckpointIdentity(
      java.nio.file.Path.of(model).toAbsolutePath.normalize.toString,
      DepthDuelCheckpoint.sha256(model).fold(error => fail(error), value => value),
      "material",
      2,
      OnnxDepthDuelRunner.ChallengerDepth,
      OnnxDepthDuelRunner.DefenderDepth,
      7,
      "empirical-v1",
      TimeControl.ofSeconds(60, 0),
      Some(SprtConfig(0, 20, 0.05, 0.05))
    )
    val white = TimedGameResult(
      GameOutcome.Win(Color.White),
      None,
      List(Color.White -> 11L, Color.Black -> 7L)
    )
    val black = TimedGameResult(
      GameOutcome.Win(Color.White),
      None,
      List(Color.White -> 5L, Color.Black -> 13L)
    )
    val observation = PairObservation(0, 2, 1.0, 0.0, white, black)
    val progress    = TimedMatchResume(Vector(observation), 1234L)
    try
      assertEquals(DepthDuelCheckpoint.save(path.toString, checkpointIdentity, 10, progress), Right(()))
      assertEquals(DepthDuelCheckpoint.load(path.toString, checkpointIdentity, 10), Right(progress))
      assert(DepthDuelCheckpoint.load(path.toString, checkpointIdentity.copy(candidateLimit = 3), 10).isLeft)
      assert(DepthDuelCheckpoint.load(path.toString, checkpointIdentity, 0).isLeft)
    finally java.nio.file.Files.deleteIfExists(path)

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
