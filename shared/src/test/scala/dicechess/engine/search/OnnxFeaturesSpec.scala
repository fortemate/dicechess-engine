package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Pins [[OnnxFeatures.extract]] against the same FEN fixtures the private training pipeline's `test_features.py` uses,
  * so the two independently-maintained implementations can be checked against each other by inspection, not just by
  * each project's own tests.
  */
class OnnxFeaturesSpec extends FunSuite:

  private val StartFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  // Feature vector layout: p_diff, n_diff, b_diff, r_diff, q_diff, material_diff, total_material.
  private val PDiff         = 0
  private val NDiff         = 1
  private val BDiff         = 2
  private val RDiff         = 3
  private val QDiff         = 4
  private val MaterialDiff  = 5
  private val TotalMaterial = 6

  test("start position is materially balanced for either color") {
    val state    = FenParser.parse(StartFen).toOption.get
    val expected = 2 * (8 * 1 + 2 * 3 + 2 * 3 + 2 * 5 + 9) // 76, matches test_features.py

    for color <- List(Color.White, Color.Black) do
      val features = OnnxFeatures.extract(state, color)
      assertEquals(features(PDiff), 0f)
      assertEquals(features(MaterialDiff), 0f)
      assertEquals(features(TotalMaterial), expected.toFloat)
  }

  test("material_diff and q_diff flip sign with the mover, White missing a queen") {
    val fen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNB1KBNR w KQkq - 0 1"
    val state = FenParser.parse(fen).toOption.get

    val whiteView = OnnxFeatures.extract(state, Color.White)
    val blackView = OnnxFeatures.extract(state, Color.Black)

    assertEquals(whiteView(MaterialDiff), -blackView(MaterialDiff))
    assertEquals(whiteView(QDiff), -1f)
    assertEquals(blackView(QDiff), 1f)
  }

  test("per-piece diffs are independent: only a knight is missing, one side") {
    val fen   = "r1bqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" // Black missing a knight
    val state = FenParser.parse(fen).toOption.get

    val whiteView = OnnxFeatures.extract(state, Color.White)
    assertEquals(whiteView(NDiff), 1f)
    assertEquals(whiteView(PDiff), 0f)
    assertEquals(whiteView(BDiff), 0f)
    assertEquals(whiteView(RDiff), 0f)
    assertEquals(whiteView(QDiff), 0f)
    assertEquals(whiteView(MaterialDiff), 3f)
  }
