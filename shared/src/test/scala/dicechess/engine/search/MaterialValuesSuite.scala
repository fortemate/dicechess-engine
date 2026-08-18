package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** The shared centipawn scale (#510). The point of these cases is not to restate the constants but to pin the two
  * properties the shared object exists to guarantee: that [[Evaluator]] and [[PieceSafety]] price the same pieces
  * identically, and that the king outweighs an entire army.
  */
class MaterialValuesSuite extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).getOrElse(sys.error(s"Failed to parse FEN: $fen"))

  test("PieceSafety prices a set of squares exactly as Evaluator prices the same material"):
    // A lone white queen and a lone black pawn: evaluating from White's side gives queen - pawn, while pricing each
    // side's pieces through PieceSafety.materialOn must produce those same two numbers.
    val state = parse("4k3/8/2p5/3Q4/8/8/8/4K3 w - - 0 1")
    val white = state.whitePieces & ~state.kings
    val black = state.blackPieces & ~state.kings

    assertEquals(PieceSafety.materialOn(state, white), MaterialValues.Queen)
    assertEquals(PieceSafety.materialOn(state, black), MaterialValues.Pawn)
    // Evaluator's material balance is the difference of the same two figures (kings cancel out, being one each).
    assertEquals(
      Evaluator.evaluateMaterial(state, Color.White),
      PieceSafety.materialOn(state, white) - PieceSafety.materialOn(state, black)
    )

  test("the king outweighs a whole starting army, so no capture can price its loss as acceptable"):
    val fullArmy = 8 * MaterialValues.Pawn + 2 * MaterialValues.Knight + 2 * MaterialValues.Bishop +
      2 * MaterialValues.Rook + MaterialValues.Queen
    assert(
      MaterialValues.King > fullArmy,
      s"king ${MaterialValues.King} must exceed a full army $fullArmy"
    )

  test("the scale is ordered by strength, with the two minor pieces deliberately equal"):
    assert(MaterialValues.Pawn < MaterialValues.Knight)
    assertEquals(MaterialValues.Knight, MaterialValues.Bishop, "no bishop-pair compensation lives in the raw scale")
    assert(MaterialValues.Bishop < MaterialValues.Rook)
    assert(MaterialValues.Rook < MaterialValues.Queen)

  test("the material balance of the initial position is level"):
    val state = parse(FenParser.InitialPosition)
    assertEquals(Evaluator.evaluateMaterial(state, Color.White), 0)
    assertEquals(Evaluator.evaluateMaterial(state, Color.Black), 0)
