// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtSession}
import dicechess.engine.domain.{Color, FenParser, GameState}
import dicechess.engine.json.Json
import dicechess.engine.search.{KcpFeatures, OnnxEvalSearch}
import munit.FunSuite

import java.nio.file.Files
import scala.util.Using

/** Python/JVM parity over the shared `kcp-13` serving contract (#78).
  *
  * Python-side agreement is not a formality here: the two halves of this project run the same artifact through
  * different bindings of ONNX Runtime, and the only way a silent disagreement shows up is as a bot whose measured
  * strength does not survive deployment. The fixture carries the probe vectors, the model, and the outputs Python
  * produced; this suite reproduces all three from the engine side.
  *
  * Both halves are asserted, because either alone can pass while the pair is broken:
  *   - the engine still extracts the exact vectors the train/serve golden corpus recorded (`train == serve`);
  *   - ONNX Runtime on the JVM scores those vectors exactly as Python did, through the same session the search uses.
  *
  * Regenerate with `jvm/src/test/resources/generate_model_contract_fixtures.py` when the feature contract changes on
  * purpose — never to make this suite green.
  */
class Kcp13ParitySpec extends FunSuite:

  /** Values are `float32` on both sides and the runtimes agree bit-for-bit in practice; the tolerance is here so a
    * last-bit difference reports as a number rather than as an opaque failure.
    */
  private val Tolerance = 1e-6

  final private case class Probe(id: String, fen: String, side: String, features: List[Float], output: Double):
    def color: Color     = if side == "w" then Color.White else Color.Black
    def state: GameState = FenParser.parse(fen).getOrElse(fail(s"probe $id: unparseable FEN '$fen'"))

  private lazy val probes: List[Probe] =
    val text    = Files.readString(ContractFixtures.parityVectors)
    val payload = Json.parse(text).getOrElse(fail("parity fixture must be valid JSON"))
    val entries = payload.field("probes").flatMap(_.asArr).getOrElse(fail("parity fixture must carry probes"))
    entries.map: entry =>
      def string(name: String) = entry.field(name).flatMap(_.asStr).getOrElse(fail(s"probe field '$name'"))
      val features             = entry.field("features").flatMap(_.asArr).getOrElse(fail("probe field 'features'"))
      Probe(
        id = string("id"),
        fen = string("fen"),
        side = string("side"),
        features = features.map(_.asNum.getOrElse(fail("feature must be a number")).toFloat),
        output = entry.field("output").flatMap(_.asNum).getOrElse(fail("probe field 'output'"))
      )

  test("the fixture carries the corpus it claims to"):
    assertEquals(probes.length, 9)
    assert(probes.forall(_.features.length == 13), "every probe is a kcp-13 row")
    assert(probes.exists(_.id.endsWith("-twin")), "a colour-swapped twin proves the perspective convention")

  test("the engine still extracts the vectors the train/serve golden corpus recorded"):
    probes.foreach: probe =>
      assertEquals(
        KcpFeatures.extract(probe.state, probe.color).toList,
        probe.features,
        s"probe ${probe.id}: kcp-13 features drifted from the golden corpus"
      )

  test("a mover-canonical vector is identical for a position and its colour-swapped twin"):
    val twins = probes.filter(_.id.endsWith("-twin"))
    assert(twins.nonEmpty)
    twins.foreach: twin =>
      val original = probes.find(_.id == twin.id.stripSuffix("-twin")).getOrElse(fail(s"no original for ${twin.id}"))
      assertEquals(twin.features, original.features, s"${twin.id} must encode exactly as ${original.id}")

  test("ONNX Runtime on the JVM reproduces Python's outputs for the whole batch"):
    val rows     = probes.map(_.features.toArray).toArray
    val scores   = runSession(rows)
    val expected = probes.map(_.output)
    scores.zip(expected).zip(probes).foreach { case ((actual, target), probe) =>
      assertEqualsDouble(actual, target, Tolerance, s"probe ${probe.id}")
    }

  test("the search's own inference path agrees with Python on the engine's Int score axis"):
    val manifest = ModelManifest.load(ContractFixtures.valueManifest).getOrElse(fail("fixture must parse"))
    assertEquals(manifest.featureSchema, FeatureSchema.Kcp13.id)
    val bot = new OnnxEvalSearch(ContractFixtures.valueModel.toString, FeatureSchema.Kcp13.extract)
    try
      probes.foreach: probe =>
        // OnnxEvalSearch scales the model's probability onto the search's Int axis; ±1 is the truncation step.
        val scaled = bot.onnxEval(probe.state, probe.color)
        val target = (probe.output * 10000.0).toInt
        assert(math.abs(scaled - target) <= 1, s"probe ${probe.id}: engine $scaled vs Python $target")
    finally bot.close()

  test("a graph is fed by the name it declares, not by the literal 'input'"):
    // Same weights, tensors named `features` / `win_probability`, and a manifest that declares them. The contract
    // accepts such a graph, so the engine has to be able to serve it: before this was wired, OnnxEvalSearch passed the
    // literal "input" and the run failed inside onnxruntime with an unknown-input error.
    val manifest = ModelManifest.load(ContractFixtures.renamedTensorsManifest).getOrElse(fail("fixture must parse"))
    assertEquals(manifest.inputName, "features")
    assertEquals(manifest.outputName, "win_probability")
    assertEquals(
      ModelPackage
        .loadFiles(
          ContractFixtures.renamedTensorsModel,
          ContractFixtures.renamedTensorsManifest,
          ContractFixtures.EngineVersion
        )
        .map(_.schema.id),
      Right(FeatureSchema.Kcp13.id)
    )

    val renamed = new OnnxEvalSearch(ContractFixtures.renamedTensorsModel.toString, FeatureSchema.Kcp13.extract)
    val default = new OnnxEvalSearch(ContractFixtures.valueModel.toString, FeatureSchema.Kcp13.extract)
    try
      probes.foreach: probe =>
        assertEquals(
          renamed.onnxEval(probe.state, probe.color),
          default.onnxEval(probe.state, probe.color),
          s"probe ${probe.id}: renaming the tensors must not change the score"
        )
    finally
      renamed.close()
      default.close()

  /** One batched run of the fixture model, decoded the way the contract states its output: `[batch, 1]` FLOAT. */
  private def runSession(rows: Array[Array[Float]]): List[Double] =
    val env = OrtEnvironment.getEnvironment
    Using.resource(env.createSession(ContractFixtures.valueModel.toString, new OrtSession.SessionOptions())): session =>
      Using.resource(OnnxTensor.createTensor(env, rows)): tensor =>
        Using.resource(session.run(java.util.Collections.singletonMap("input", tensor))): result =>
          result.get(0).getValue.asInstanceOf[Array[Array[Float]]].map(_(0).toDouble).toList
