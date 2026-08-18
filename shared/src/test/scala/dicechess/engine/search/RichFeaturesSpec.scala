package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Pins [[RichFeatures]]: the vector must match [[columnNames]] in length and layout, keep the material block identical
  * to [[OnnxFeatures]], and give the positional columns the right sign from the mover's perspective.
  */
class RichFeaturesSpec extends FunSuite:

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  private val Mobility   = RichFeatures.columnNames.indexOf("mobility_diff")
  private val KingSafety = RichFeatures.columnNames.indexOf("king_safety_diff")

  test("the vector length matches columnNames"):
    val features = RichFeatures.extract(parse(FenParser.InitialPosition), Color.White)
    assertEquals(features.length, RichFeatures.columnNames.length)
    assertEquals(features.length, 9)

  test("columnNames pins the exact header strings (the train/serve CSV contract)"):
    // A silent rename of any column would still pass the length/indexOf tests but corrupt the CSV
    // headers the enrichment step writes, desyncing training from serving. Pin the whole layout.
    assertEquals(
      RichFeatures.columnNames,
      List(
        "p_diff",
        "n_diff",
        "b_diff",
        "r_diff",
        "q_diff",
        "material_diff",
        "total_material",
        "mobility_diff",
        "king_safety_diff"
      )
    )

  test("the material block is identical to OnnxFeatures (reuse, not a re-implementation)"):
    val state = parse("r1bqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1") // Black missing a knight
    for color <- List(Color.White, Color.Black) do
      val rich = RichFeatures.extract(state, color).take(7).toList
      val mat  = OnnxFeatures.extract(state, color).toList
      assertEquals(rich, mat, s"material prefix diverged for $color")

  test("the start position is positionally symmetric for either side"):
    val start = parse(FenParser.InitialPosition)
    for color <- List(Color.White, Color.Black) do
      val f = RichFeatures.extract(start, color)
      assertEquals(f(Mobility), 0f, s"mobility should be balanced at the start for $color")
      assertEquals(f(KingSafety), 0f, s"king safety should be balanced at the start for $color")

  test("mobility_diff is positive for the side with the far more active army"):
    // White has a centralised queen; Black has only its king — White's pseudo-legal move count dominates.
    val state = parse("4k3/8/8/8/3Q4/8/8/4K3 w - - 0 1")
    assert(RichFeatures.extract(state, Color.White)(Mobility) > 0f)
    assert(RichFeatures.extract(state, Color.Black)(Mobility) < 0f) // mirror: from Black's view it is negative

  test("king_safety_diff is negative when our own king is the one under attack"):
    // Black rook on e2 attacks the White king on e1; the Black king (e8) is attacked by nothing.
    val state = parse("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1")
    assert(RichFeatures.extract(state, Color.White)(KingSafety) < 0f)
    assert(RichFeatures.extract(state, Color.Black)(KingSafety) > 0f) // mirror from Black's perspective
