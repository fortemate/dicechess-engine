// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import java.net.URL
import java.nio.file.Paths

import ai.onnxruntime.OrtException
import dicechess.engine.domain.*
import munit.FunSuite

class KcpMobilityOnnxSpec extends FunSuite:

  private def modelFilePath(resource: URL): String = Paths.get(resource.toURI).toString

  private val model27Path = modelFilePath(
    Option(getClass.getResource("/synthetic_kcp_mobility_27_test_model.onnx"))
      .getOrElse(fail("Missing test resource: /synthetic_kcp_mobility_27_test_model.onnx"))
  )

  private val model31Path = modelFilePath(
    Option(getClass.getResource("/synthetic_kcp_mobility_pawns_31_test_model.onnx"))
      .getOrElse(fail("Missing test resource: /synthetic_kcp_mobility_pawns_31_test_model.onnx"))
  )

  test("27-column ONNX model preserves perspective in single and batched inference"):
    val states = Array(
      FenParser.parse(FenParser.InitialPosition).toOption.get,
      FenParser.parse("4k3/pp6/8/8/3Q4/8/PP6/R3K3 b - - 0 1").toOption.get,
      FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get
    )
    val bot = new OnnxEvalSearch(model27Path, KcpMobilityFeatures.extract)
    try
      // Model formula: 10000 * (0.1 + 0.6 * own_pdi + 0.2 * opp_pdi)
      // Initial: own_pdi = 1.0, opp_pdi = 1.0 -> 0.1 + 0.6 + 0.2 = 0.9 -> 9000
      // Fen 2 White: own_pdi = 0.6, opp_pdi = 0.2 -> 0.1 + 0.36 + 0.04 = 0.5 -> 5000
      // Fen 2 Black: own_pdi = 0.2, opp_pdi = 0.6 -> 0.1 + 0.12 + 0.12 = 0.34 -> 3400
      // Kings: own_pdi = 0.0, opp_pdi = 0.0 -> 0.1 -> 1000
      for (color, expected) <- List(Color.White -> List(9000, 5000, 1000), Color.Black -> List(9000, 3400, 1000)) do
        val single = states.map(bot.onnxEval(_, color)).toList
        val batch  = bot.onnxEvalBatch(states, color).toList
        assertEquals(batch, single)
        single.zip(expected).foreach((actual, target) => assert(math.abs(actual - target) <= 1))
    finally bot.close()

  test("31-column ONNX model preserves perspective in single and batched inference"):
    val states = Array(
      FenParser.parse(FenParser.InitialPosition).toOption.get,
      FenParser.parse("k7/4P3/8/8/8/8/8/4K3 w - - 0 1").toOption.get,
      FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get
    )
    val bot = new OnnxEvalSearch(model31Path, KcpMobilityPawnsFeatures.extract)
    try
      // Model formula: 10000 * (0.1 + 0.6 * own_pdi + 0.2 * opp_pdi + 0.5 * own_passed_pawns)
      // Initial: own_pdi = 1.0, opp_pdi = 1.0, own_passed = 0 -> 9000
      // Promo White: own_pdi = 0.2, opp_pdi = 0.0, own_passed = 1 -> 10000 * (0.1 + 0.12 + 0.0 + 0.5) = 7200
      // Promo Black: own_pdi = 0.0, opp_pdi = 0.2, own_passed = 0 -> 10000 * (0.1 + 0.0 + 0.04 + 0.0) = 1400
      // Kings: own_pdi = 0.0, opp_pdi = 0.0, own_passed = 0 -> 1000
      for (color, expected) <- List(Color.White -> List(9000, 7200, 1000), Color.Black -> List(9000, 1400, 1000)) do
        val single = states.map(bot.onnxEval(_, color)).toList
        val batch  = bot.onnxEvalBatch(states, color).toList
        assertEquals(batch, single)
        single.zip(expected).foreach((actual, target) => assert(math.abs(actual - target) <= 1))
    finally bot.close()

  test("13-column extractor cannot be silently passed to a 27-input model"):
    val bot   = new OnnxEvalSearch(model27Path, KcpFeatures.extract)
    val state = FenParser.parse(FenParser.InitialPosition).toOption.get
    try
      val error   = intercept[OrtException](bot.onnxEval(state, Color.White))
      val message = Option(error.getMessage).getOrElse(fail("ONNX shape error has no message"))
      assert(message.contains("input"), message)
      assert(message.contains("Got: 13 Expected: 27"), message)
    finally bot.close()

  test("27-column extractor cannot be silently passed to a 31-input model"):
    val bot   = new OnnxEvalSearch(model31Path, KcpMobilityFeatures.extract)
    val state = FenParser.parse(FenParser.InitialPosition).toOption.get
    try
      val error   = intercept[OrtException](bot.onnxEval(state, Color.White))
      val message = Option(error.getMessage).getOrElse(fail("ONNX shape error has no message"))
      assert(message.contains("input"), message)
      assert(message.contains("Got: 27 Expected: 31"), message)
    finally bot.close()

  test("13-column extractor cannot be silently passed to a 31-input model"):
    val bot   = new OnnxEvalSearch(model31Path, KcpFeatures.extract)
    val state = FenParser.parse(FenParser.InitialPosition).toOption.get
    try
      val error   = intercept[OrtException](bot.onnxEval(state, Color.White))
      val message = Option(error.getMessage).getOrElse(fail("ONNX shape error has no message"))
      assert(message.contains("input"), message)
      assert(message.contains("Got: 13 Expected: 31"), message)
    finally bot.close()
