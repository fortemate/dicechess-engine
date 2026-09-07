package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class RichPdiOnnxSpec extends FunSuite:
  private val modelPath = getClass.getResource("/synthetic_pdi_test_model.onnx").getPath

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
    val bot = new OnnxEvalSearch(modelPath, RichFeatures.extract)
    try intercept[Exception](bot.onnxEval(FenParser.parse(FenParser.InitialPosition).toOption.get, Color.White))
    finally bot.close()
