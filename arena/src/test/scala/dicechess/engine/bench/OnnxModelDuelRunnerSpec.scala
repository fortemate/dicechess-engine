package dicechess.engine.bench

import scala.concurrent.duration.*

import munit.FunSuite

/** Argument handling and end-to-end wiring for [[OnnxModelDuelRunner]], against the throwaway synthetic model shared
  * from rootJVM's test resources (no chess signal — the real models are private artifacts). Both sides play the same
  * file on purpose: this proves the duel runs, not which model is better, and strength is measured in a real arena.
  *
  * The rejection cases matter more than they look. Each one is an argument that would otherwise be discovered *after*
  * two onnxruntime sessions had been opened, or worse, silently accepted: a mistyped `--baseline-features` that fell
  * through to the wrong extractor would feed a model the wrong-shaped vector and quietly measure nonsense.
  */
class OnnxModelDuelRunnerSpec extends FunSuite:

  /** munit's 30s default is sized for unit tests; the duel below plays real games through move generation and two
    * onnxruntime sessions. Uninstrumented that is ~2s, but the coverage build instruments the generator's hot path and
    * CI took 32s — just past the default, which failed the build for being slow rather than wrong. Bounded rather than
    * removed: a genuine hang should still fail instead of holding the suite forever.
    */
  override def munitTimeout: Duration = 3.minutes

  private val model = getClass.getResource("/synthetic_test_model.onnx").getPath

  private def run(args: String*) =
    ArenaOptions.parseAndRun(OnnxModelDuelRunner.command, args.toArray)

  test("requires a model on each side"):
    assert(run().isLeft)
    assert(run(model).isLeft)

  test("rejects an unknown feature set on either side"):
    assert(run(model, model, "--features", "bogus").isLeft)
    assert(run(model, model, "--baseline-features", "bogus").isLeft)

  test("rejects a non-positive candidate limit or game count"):
    assert(run(model, model, "--candidate-limit", "0").isLeft)
    assert(run(model, model, "--games", "0").isLeft)

  test("rejects an invalid time control before loading either model"):
    assert(run(model, model, "--presets", "0+2").isLeft)
    assert(run(model, model, "--presets", "").isLeft)

  test("rejects an unknown search"):
    assert(run(model, model, "--search", "bogus").isLeft)

  test("rejects --candidate-limit at one ply instead of ignoring it"):
    // The point of the rejection: a silently dropped width would let "one-ply at K=48" be archived and later read as
    // though the width had been a variable. The message has to say why, so assert on it rather than on isLeft alone.
    val result = run(model, model, "--search", "oneply", "--candidate-limit", "48")
    assert(result.isLeft, result)
    assert(result.left.exists(_.contains("no meaning with --search oneply")), result)

  test("the JSON report records what the run varied, not just the two fixed side ids"):
    // Both sides register under constant ids, so without this the chunk files of two unrelated experiments are
    // identical in every identifying field and tell apart only by the directory someone filed them in. The time
    // policies matter most: in a policy duel they are the entire variable.
    val out    = java.nio.file.Files.createTempFile("onnx-duel-setup", ".json")
    val result = run(
      model,
      model,
      "--features",
      "material",
      "--baseline-features",
      "material",
      "--challenger-time-policy",
      "empirical-v1",
      "--defender-time-policy",
      "legacy-linear-v1",
      "--games",
      "1",
      "--candidate-limit",
      "2",
      "--presets",
      "1+0",
      "--seed",
      "3",
      "--json",
      out.toString
    )
    assert(result.isRight, result)
    val json = java.nio.file.Files.readString(out)
    assert(json.contains("\"search\""), json)
    assert(json.contains("expectimax"), json)
    assert(json.contains("empirical-v1"), json)
    assert(json.contains("legacy-linear-v1"), json)
    java.nio.file.Files.deleteIfExists(out)

  test("plays a one-ply duel"):
    val result = run(
      model,
      model,
      "--search",
      "oneply",
      "--features",
      "material",
      "--baseline-features",
      "material",
      "--games",
      "1",
      "--presets",
      "1+0",
      "--seed",
      "11"
    )
    assert(result.isRight, result)

  test("plays a duel and writes a JSON report"):
    val out    = java.nio.file.Files.createTempFile("onnx-model-duel", ".json")
    val result = run(
      model,
      model,
      "--features",
      "material",
      "--baseline-features",
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
    assert(java.nio.file.Files.size(out) > 0L)
    java.nio.file.Files.deleteIfExists(out)

  test("registers both sides under distinct ids"):
    assertNotEquals(OnnxModelDuelRunner.ChallengerId, OnnxModelDuelRunner.DefenderId)
