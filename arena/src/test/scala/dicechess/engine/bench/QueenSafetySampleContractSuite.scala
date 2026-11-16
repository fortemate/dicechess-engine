// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPOutputStream

import scala.concurrent.duration.*
import scala.util.Using

import dicechess.engine.domain.*
import munit.FunSuite

class QueenSafetySampleContractSuite extends FunSuite:
  override def munitTimeout: Duration = 2.minutes

  private val model = Option(getClass.getResource("/synthetic_test_model.onnx"))
    .fold(fail("missing /synthetic_test_model.onnx test resource"))(_.getPath)

  private val whiteFen = "4r1k1/8/8/8/4Q3/8/8/6K1 b - - 0 1"
  private val blackFen = "6k1/8/8/4q3/8/8/8/4R1K1 w - - 0 1"
  private val quietFen = "6k1/8/8/8/8/8/8/7K w - - 0 1"
  private val multiFen = "k3r1r1/8/8/6Q1/4Q3/8/8/7K b - - 0 1"

  private def state(fen: String): GameState = FenParser.parse(fen).fold(fail(_), identity)

  private def withCorpus[A](contents: String)(run: Path => A): A =
    val path = Files.createTempFile("queen-safety-sample-contract", ".csv.gz")
    try
      Using.resource(new GZIPOutputStream(Files.newOutputStream(path))) { output =>
        output.write(contents.getBytes(StandardCharsets.UTF_8))
      }
      run(path)
    finally Files.deleteIfExists(path)

  private def digest(bytes: Array[Byte]): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def expectedStats(
      path: Path,
      scanned: Long,
      parseable: Long,
      hanging: Long,
      eligible: Long,
      retained: Int
  ): QueenSafetySampleStats =
    val bytes = Files.readAllBytes(path)
    QueenSafetySampleStats(bytes.length.toLong, digest(bytes), scanned, parseable, hanging, eligible, retained)

  private def assertFailure(path: String, expected: String): Unit =
    val error = intercept[RuntimeException](QueenSafetyProbe.sample(path, wanted = 1, seed = 7L))
    assertEquals(error.getMessage, expected)

  private def withFullMove(fen: String, fullMove: Int): String =
    val fields = fen.split(" ")
    fields(fields.length - 1) = fullMove.toString
    fields.mkString(" ")

  test("reports missing, empty, and invalid-header corpora precisely") {
    val missing = Files.createTempFile("queen-safety-missing", ".csv.gz")
    Files.deleteIfExists(missing)
    assertFailure(missing.toString, s"corpus not found: $missing")

    withCorpus("")(path => assertFailure(path.toString, "corpus is empty"))
    withCorpus("fen,other\n") { path =>
      assertFailure(path.toString, "corpus must have 'fen' and 'side' columns, got: Vector(fen, other)")
    }
    withCorpus("other,side\n") { path =>
      assertFailure(path.toString, "corpus must have 'fen' and 'side' columns, got: Vector(other, side)")
    }
  }

  test("a header-only corpus returns empty counters and hashes the compressed bytes") {
    withCorpus("fen,side\n") { path =>
      val sample = QueenSafetyProbe.sample(path.toString, wanted = 4, seed = 11L)
      assertEquals(sample.pairs, Vector.empty)
      assertEquals(sample.stats, expectedStats(path, 0L, 0L, 0L, 0L, 0))
      assertEquals(QueenSafetyProbe.sha256(path), digest(Files.readAllBytes(path)))
    }
  }

  test("reordered columns scan malformed rows while parsing CSV mover colors") {
    val contents = List(
      "side,tag,fen,tail",
      s"W,white,$whiteFen,ok",
      s"B,black,$blackFen,ok",
      s"w,quiet,$quietFen,ok",
      s"x,bad-side,$whiteFen,ok",
      "",
      s" w,space,$whiteFen,ok",
      "w,bad-fen,not-a-fen,ok",
      "w,short"
    ).mkString("\n") + "\n"

    withCorpus(contents) { path =>
      val sample = QueenSafetyProbe.sample(path.toString, wanted = 8, seed = 19L)
      assertEquals(sample.stats, expectedStats(path, 8L, 3L, 2L, 2L, 2))
      assertEquals(sample.pairs.map(_.mover), Vector(Color.White, Color.Black))
      assertEquals(sample.pairs.map(pair => FenParser.serialize(pair.hanging)), Vector(whiteFen, blackFen))
      assertEquals(
        sample.pairs.map(pair => FenParser.serialize(pair.safe)),
        Vector(
          "4r1k1/8/8/8/3Q4/8/8/6K1 b - - 0 1",
          "6k1/8/8/3q4/8/8/8/4R1K1 w - - 0 1"
        )
      )
    }
  }

  test("duplicate headers use the first fen column and missing side cells are skipped") {
    withCorpus(s"fen,side,fen\n$quietFen,w,$whiteFen\n") { path =>
      val sample = QueenSafetyProbe.sample(path.toString, wanted = 2, seed = 23L)
      assertEquals(sample.stats, expectedStats(path, 1L, 1L, 0L, 0L, 0))
      assertEquals(sample.pairs, Vector.empty)
    }

    withCorpus(s"fen,side,fen\nnot-a-fen,w,$whiteFen\n") { path =>
      val sample = QueenSafetyProbe.sample(path.toString, wanted = 2, seed = 29L)
      assertEquals(sample.stats, expectedStats(path, 1L, 0L, 0L, 0L, 0))
      assertEquals(sample.pairs, Vector.empty)
    }

    withCorpus(s"fen,side\n$whiteFen\n") { path =>
      val sample = QueenSafetyProbe.sample(path.toString, wanted = 2, seed = 30L)
      assertEquals(sample.stats, expectedStats(path, 1L, 0L, 0L, 0L, 0))
      assertEquals(sample.pairs, Vector.empty)
    }
  }

  test("multiple queen pairs retain source-square order and independent counterfactuals") {
    withCorpus(s"fen,side\n$multiFen,w\n") { path =>
      val sample = QueenSafetyProbe.sample(path.toString, wanted = 2, seed = 31L)
      assertEquals(sample.stats, expectedStats(path, 1L, 1L, 1L, 2L, 2))
      assertEquals(sample.pairs.length, 2)
      assert(sample.pairs.forall(pair => FenParser.serialize(pair.hanging) == multiFen))
      assert(sample.pairs(0).safe.queens.contains(Square('d', 4)))
      assert(sample.pairs(0).safe.queens.contains(Square('g', 5)))
      assert(!sample.pairs(0).safe.queens.contains(Square('e', 4)))
      assert(sample.pairs(1).safe.queens.contains(Square('e', 4)))
      assert(sample.pairs(1).safe.queens.contains(Square('f', 5)))
      assert(!sample.pairs(1).safe.queens.contains(Square('g', 5)))
      assertEquals(sample.pairs.map(_.safe.queens.count), Vector(2, 2))
    }
  }

  test("a reservoir larger than the corpus retains all rows in order") {
    val fens = (1 to 5).map(fullMove => withFullMove(whiteFen, fullMove)).toVector
    val body = fens.map(fen => s"$fen,w").mkString("\n")

    withCorpus(s"fen,side\n$body\n") { path =>
      val sample = QueenSafetyProbe.sample(path.toString, wanted = 10, seed = 37L)
      assertEquals(sample.stats, expectedStats(path, 5L, 5L, 5L, 5L, 5))
      assertEquals(sample.pairs.map(_.hanging.fullMoveNumber), Vector(1, 2, 3, 4, 5))
      assert(sample.pairs.forall(pair => pair.hanging.queens.count == 1 && pair.safe.queens.count == 1))
      assert(sample.pairs.forall(pair => pair.hanging.queens.contains(Square('e', 4))))
      assert(sample.pairs.forall(pair => pair.safe.queens.contains(Square('d', 4))))
      assert(
        sample.pairs.forall(pair =>
          FenParser.serialize(pair.hanging) == withFullMove(whiteFen, pair.hanging.fullMoveNumber)
        )
      )
    }
  }

  test("nonpositive direct sample limits still scan all eligible rows") {
    val contents = s"fen,side\n${List.fill(5)(s"$whiteFen,w").mkString("\n")}\n"
    withCorpus(contents) { path =>
      val zero     = QueenSafetyProbe.sample(path.toString, wanted = 0, seed = 41L)
      val negative = QueenSafetyProbe.sample(path.toString, wanted = -1, seed = 43L)
      assertEquals(zero.stats, expectedStats(path, 5L, 5L, 5L, 5L, 0))
      assertEquals(negative.stats, expectedStats(path, 5L, 5L, 5L, 5L, 0))
      assertEquals(zero.pairs, Vector.empty)
      assertEquals(negative.pairs, Vector.empty)
    }
  }

  test("seeded reservoir selections preserve the original ordered samples") {
    val rows = (1 to 12).map(fullMove => s"${withFullMove(whiteFen, fullMove)},w").mkString("\n")
    withCorpus(s"fen,side\n$rows\n") { path =>
      val seed7Wanted3 = QueenSafetyProbe.sample(path.toString, wanted = 3, seed = 7L)
      assertEquals(seed7Wanted3.stats, expectedStats(path, 12L, 12L, 12L, 12L, 3))
      assertEquals(seed7Wanted3.pairs.map(_.hanging.fullMoveNumber), Vector(1, 9, 8))

      val seed42Wanted3 = QueenSafetyProbe.sample(path.toString, wanted = 3, seed = 42L)
      assertEquals(seed42Wanted3.stats, expectedStats(path, 12L, 12L, 12L, 12L, 3))
      assertEquals(seed42Wanted3.pairs.map(_.hanging.fullMoveNumber), Vector(6, 2, 11))

      val seed7Wanted1 = QueenSafetyProbe.sample(path.toString, wanted = 1, seed = 7L)
      assertEquals(seed7Wanted1.stats, expectedStats(path, 12L, 12L, 12L, 12L, 1))
      assertEquals(seed7Wanted1.pairs.map(_.hanging.fullMoveNumber), Vector(12))

      val seed42Wanted1 = QueenSafetyProbe.sample(path.toString, wanted = 1, seed = 42L)
      assertEquals(seed42Wanted1.stats, expectedStats(path, 12L, 12L, 12L, 12L, 1))
      assertEquals(seed42Wanted1.pairs.map(_.hanging.fullMoveNumber), Vector(6))

      val repeated = QueenSafetyProbe.sample(path.toString, wanted = 3, seed = 7L)
      assertEquals(repeated.pairs.map(_.hanging.fullMoveNumber), Vector(1, 9, 8))
    }
  }

  test("queen shifts reject blocked or still-attacked destinations and preserve inputs") {
    val edgeCases = Vector(
      (state("r5k1/8/8/8/Q7/8/8/7K b - - 0 1"), Color.White, Square('a', 4)),
      (state("4r1k1/8/8/8/3NQ3/8/8/6K1 b - - 0 1"), Color.White, Square('e', 4)),
      (state("3rr1k1/8/8/8/4Q3/8/8/7K b - - 0 1"), Color.White, Square('e', 4))
    )
    edgeCases.foreach { case (original, mover, from) =>
      val snapshot = FenParser.serialize(original)
      assertEquals(QueenSafetyProbe.shiftTowardsA(original, mover, from), None)
      assertEquals(QueenSafetyProbe.pairsFor(original, mover), Nil)
      assertEquals(FenParser.serialize(original), snapshot)
    }

    val white         = state(whiteFen)
    val whiteSnapshot = FenParser.serialize(white)
    val whitePairs    = QueenSafetyProbe.pairsFor(white, Color.White)
    assertEquals(whitePairs.length, 1)
    assert(whitePairs.head.safe.queens.contains(Square('d', 4)))
    assert(!whitePairs.head.safe.queens.contains(Square('e', 4)))
    assertEquals(whitePairs.head.safe.queens.count, white.queens.count)
    assertEquals(whitePairs.head.safe.zobristKey, 0L)
    assertEquals(FenParser.serialize(white), whiteSnapshot)

    val black         = state(blackFen)
    val blackSnapshot = FenParser.serialize(black)
    val blackPairs    = QueenSafetyProbe.pairsFor(black, Color.Black)
    assertEquals(blackPairs.length, 1)
    assert(blackPairs.head.safe.queens.contains(Square('d', 5)))
    assert(!blackPairs.head.safe.queens.contains(Square('e', 5)))
    assertEquals(blackPairs.head.safe.queens.count, black.queens.count)
    assertEquals(blackPairs.head.safe.zobristKey, 0L)
    assertEquals(FenParser.serialize(black), blackSnapshot)
  }

  test("material evaluation ties both colors and delta appends are atomic on mismatches") {
    withCorpus(s"fen,side\n$whiteFen,w\n$blackFen,b\n") { path =>
      val sample  = QueenSafetyProbe.sample(path.toString, wanted = 2, seed = 47L)
      val summary = QueenSafetyProbe.evaluate(model, ArenaOptions.extractFeatures("material"), sample.pairs)
      assertEquals(summary.pairs, 2)
      assertEquals(summary.ties, 2)
      assertEquals(summary.meanDelta, 0.0)
      assertEquals(summary.safePreferred, 0)
      assertEquals(summary.hangingPreferred, 0)
    }

    val deltas = scala.collection.mutable.ArrayBuffer(7)
    QueenSafetyProbe.appendDeltas(Array(5, 6), Array(3, 6), expected = 2, deltas)
    assertEquals(deltas.toVector, Vector(7, 2, 0))
    val error = intercept[RuntimeException] {
      QueenSafetyProbe.appendDeltas(Array(1, 2), Array(1), expected = 2, deltas)
    }
    assertEquals(error.getMessage, "ONNX batch size mismatch: expected 2, safe=2, hanging=1")
    assertEquals(deltas.toVector, Vector(7, 2, 0))

    val emptyError = intercept[IllegalArgumentException](QueenSafetyProbe.summarize(Array.emptyIntArray))
    assertEquals(emptyError.getMessage, "requirement failed: at least one score delta is required")
    QueenSafetyProbe.appendDeltas(Array.emptyIntArray, Array.emptyIntArray, expected = 0, deltas)
    assertEquals(deltas.toVector, Vector(7, 2, 0))
  }
