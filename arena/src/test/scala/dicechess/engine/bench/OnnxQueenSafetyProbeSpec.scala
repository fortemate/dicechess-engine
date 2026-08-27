package dicechess.engine.bench

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

import scala.concurrent.duration.*
import scala.util.Using

import dicechess.engine.domain.*
import munit.FunSuite

class OnnxQueenSafetyProbeSpec extends FunSuite:

  override def munitTimeout: Duration = 2.minutes

  private val model = Option(getClass.getResource("/synthetic_test_model.onnx"))
    .fold(fail("missing /synthetic_test_model.onnx test resource"))(_.getPath)

  private def state(fen: String): GameState = FenParser.parse(fen).fold(fail(_), identity)

  private def writeCorpus(rows: String*): java.nio.file.Path =
    val path = Files.createTempFile("queen-safety-probe", ".csv.gz")
    Using.resource(new GZIPOutputStream(Files.newOutputStream(path))) { output =>
      output.write(("fen,side\n" + rows.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
    }
    path

  test("builds a material-identical pair when the left shift removes the queen hang"):
    val original = state("4r1k1/8/8/8/4Q3/8/8/6K1 b - - 0 1")
    val pairs    = QueenSafetyProbe.pairsFor(original, Color.White)
    assertEquals(pairs.size, 1)
    val pair = pairs.head
    assertEquals(pair.hanging.mailbox(Square('e', 4)).pieceType, PieceType.Queen)
    assert(pair.safe.mailbox(Square('e', 4)).isEmpty)
    assertEquals(pair.safe.mailbox(Square('d', 4)).pieceType, PieceType.Queen)
    assertEquals(pair.safe.queens.count, pair.hanging.queens.count)

  test("rejects a counterfactual when the destination is occupied"):
    val original = state("4r1k1/8/8/8/3NQ3/8/8/6K1 b - - 0 1")
    assertEquals(QueenSafetyProbe.pairsFor(original, Color.White), Nil)

  test("summarizes safe, hanging, and tied preferences"):
    val summary = QueenSafetyProbe.summarize(Array(10, -5, 0, 20))
    assertEquals(summary.pairs, 4)
    assertEquals(summary.safePreferred, 2)
    assertEquals(summary.hangingPreferred, 1)
    assertEquals(summary.ties, 1)
    assertEquals(summary.meanDelta, 6.25)
    assertEquals(summary.deltaP50, 0)

  test("rejects mismatched ONNX batch output lengths"):
    val deltas = scala.collection.mutable.ArrayBuffer.empty[Int]
    val error  = intercept[RuntimeException] {
      QueenSafetyProbe.appendDeltas(Array(1), Array(2, 3), expected = 2, deltas)
    }
    assert(error.getMessage.contains("ONNX batch size mismatch"))
    assertEquals(deltas.toVector, Vector.empty)

  test("requires both models and a positive pair count"):
    assert(ArenaOptions.parseAndRun(OnnxQueenSafetyProbeMain.command, Array.empty).isLeft)
    assert(
      ArenaOptions
        .parseAndRun(OnnxQueenSafetyProbeMain.command, Array(model, model, "corpus.csv.gz", "--pairs", "0"))
        .isLeft
    )

  test("runs a bounded probe twice and emits aggregate-only JSON"):
    val corpus = writeCorpus("4r1k1/8/8/8/4Q3/8/8/6K1 b - -,w")
    val json   = Files.createTempFile("queen-safety-probe", ".json")
    try
      def run() = ArenaOptions.parseAndRun(
        OnnxQueenSafetyProbeMain.command,
        Array(
          model,
          model,
          corpus.toString,
          "--pairs",
          "1",
          "--features",
          "material",
          "--seed",
          "7",
          "--json",
          json.toString
        )
      )

      val first = run()
      assert(first.isRight, first)
      val second = run()
      assert(second.isRight, second)

      val report = Json.parse(Files.readString(json)).fold(error => fail(error), identity)
      assertEquals(report.field("kind").flatMap(_.asStr), Some("onnx_queen_safety_probe"))
      assertEquals(report.field("schemaVersion").flatMap(_.asNum), Some(1.0))
      val sample = report.field("sample").getOrElse(fail("missing sample"))
      assertEquals(sample.field("retainedPairs").flatMap(_.asNum), Some(1.0))
      assertEquals(sample.field("rawPositionsEmitted").flatMap(_.asBool), Some(false))
      assertEquals(sample.field("corpusSha256").flatMap(_.asStr).map(_.length), Some(64))
      val challenger = report.field("challenger").getOrElse(fail("missing challenger"))
      assertEquals(challenger.field("ties").flatMap(_.asNum), Some(1.0))
      val defender = report.field("defender").getOrElse(fail("missing defender"))
      assertEquals(defender.field("ties").flatMap(_.asNum), Some(1.0))
    finally
      Files.deleteIfExists(json)
      Files.deleteIfExists(corpus)
