// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import java.net.URL
import java.nio.file.{Files, Paths}

import ai.onnxruntime.OrtException
import dicechess.engine.domain.*
import munit.FunSuite

class RichPdiOnnxSpec extends FunSuite:
  private def modelFilePath(resource: URL): String = Paths.get(resource.toURI).toString

  private val modelPath = modelFilePath(
    Option(getClass.getResource("/synthetic_pdi_test_model.onnx"))
      .getOrElse(fail("Missing test resource: /synthetic_pdi_test_model.onnx"))
  )

  test("ONNX fixture loads from a URL containing percent-encoded spaces"):
    val directory = Files.createTempDirectory("rich pdi ")
    val copy      = directory.resolve("synthetic model.onnx")
    try
      val _   = Files.copy(Paths.get(modelPath), copy)
      val bot = new OnnxEvalSearch(modelFilePath(copy.toUri.toURL), RichPdiFeatures.extract)
      try
        val state = FenParser.parse(FenParser.InitialPosition).toOption.get
        assert(math.abs(bot.onnxEval(state, Color.White) - 9000) <= 1)
      finally bot.close()
    finally
      val _ = Files.deleteIfExists(copy)
      val _ = Files.deleteIfExists(directory)

  test("eleven-column ONNX wiring preserves normalized perspective in single and batched inference"):
    val states = Array(
      FenParser.parse(FenParser.InitialPosition).toOption.get,
      FenParser.parse("4k3/pp6/8/8/3Q4/8/PP6/R3K3 b - - 0 1").toOption.get,
      FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get
    )
    val bot = new OnnxEvalSearch(modelPath, RichPdiFeatures.extract)
    try
      for (color, expected) <- List(Color.White -> List(9000, 5000, 1000), Color.Black -> List(9000, 3400, 1000)) do
        val single = states.map(bot.onnxEval(_, color)).toList
        val batch  = bot.onnxEvalBatch(states, color).toList
        assertEquals(batch, single)
        single.zip(expected).foreach((actual, target) => assert(math.abs(actual - target) <= 1))
    finally bot.close()

  test("legacy rich-9 cannot be silently passed to an eleven-input model"):
    val bot   = new OnnxEvalSearch(modelPath, RichFeatures.extract)
    val state = FenParser.parse(FenParser.InitialPosition).toOption.get
    try
      val error   = intercept[OrtException](bot.onnxEval(state, Color.White))
      val message = Option(error.getMessage).getOrElse(fail("ONNX shape error has no message"))
      assert(message.contains("input"), message)
      assert(message.contains("Got: 9 Expected: 11"), message)
    finally bot.close()
