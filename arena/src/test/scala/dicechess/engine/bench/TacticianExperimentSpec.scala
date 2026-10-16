// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.FenParser
import java.nio.file.Files
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.util.Using

class TacticianExperimentSpec extends FunSuite:
  test("identical pair outcomes retain uncertainty at both score boundaries") {
    val (winLower, winUpper)   = TacticianExperimentMain.scoreInterval(1.0, 2)
    val (lossLower, lossUpper) = TacticianExperimentMain.scoreInterval(0.0, 2)
    assert(winLower < 1.0 && winLower >= 0.0)
    assertEquals(winUpper, 1.0)
    assertEquals(lossLower, 0.0)
    assert(lossUpper > 0.0 && lossUpper <= 1.0)
  }

  test("the bounded-pair interval has the expected radius and contracts with more pairs") {
    val (lower, upper) = TacticianExperimentMain.scoreInterval(0.5, 100)
    assertEqualsDouble(lower, 0.36418984842593805, 1e-12)
    assertEqualsDouble(upper, 0.635810151574062, 1e-12)
    val (largerLower, largerUpper) = TacticianExperimentMain.scoreInterval(0.5, 200)
    assert(largerLower > lower && largerUpper < upper)
    assertEquals(TacticianExperimentMain.scoreInterval(1.0, 1), (0.0, 1.0))
  }

  test("invalid interval inputs are rejected") {
    intercept[IllegalArgumentException](TacticianExperimentMain.scoreInterval(0.5, 0))
    for mean <- List(-0.1, 1.1, Double.NaN, Double.PositiveInfinity) do
      intercept[IllegalArgumentException](TacticianExperimentMain.scoreInterval(mean, 2))
  }

  test("the emitted DFEN corpus contains no empty final record") {
    val output = Files.createTempDirectory("tactician-corpus-")
    try
      TacticianExperimentMain.main(Array("latency", "1", "0", "0", "0", "1", "42", "1", output.toString))
      val lines = Files.readAllLines(output.resolve("positions.dfen")).asScala.toList
      assertEquals(lines.size, 1)
      assert(lines.forall(line => FenParser.parse(line).isRight))
    finally
      Using.resource(Files.list(output))(_.iterator().asScala.foreach { path =>
        val _ = Files.deleteIfExists(path)
      })
      Files.delete(output)
  }
