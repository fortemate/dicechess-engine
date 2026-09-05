package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Runs the documented king-capture catalog on JVM, JavaScript and Wasm. */
class KingCaptureGoldenSpec extends FunSuite:

  test("the golden catalog carries all 14 original scenarios") {
    assertEquals(KingCaptureFixtures.cases.size, 14)
    assertEquals(KingCaptureFixtures.cases.map(_.name).distinct.size, 14)
    for tc <- KingCaptureFixtures.cases do
      assert(tc.name.nonEmpty && tc.description.nonEmpty && tc.rationale.nonEmpty, s"Incomplete fixture: ${tc.name}")
      assert(tc.winningRolls >= 0 && tc.winningRolls <= 216, s"Invalid winning roll count: ${tc.name}")
  }

  for tc <- KingCaptureFixtures.cases do
    test(s"${tc.name}: ${tc.description}") {
      val state          = FenParser.parse(tc.fen).fold(err => fail(s"Failed to parse FEN '${tc.fen}': $err"), identity)
      val defenderPieces = if tc.defenderColor.isWhite then state.whitePieces else state.blackPieces
      assert(!(state.kings & defenderPieces).isEmpty, s"Fixture has no defended king: '${tc.fen}'")

      assertEquals(
        KingCaptureProbability.kingCaptureProbability(state, tc.defenderColor),
        tc.expectedKingProbability,
        s"${tc.name}: expected ${tc.winningRolls}/216. ${tc.rationale} FEN: ${tc.fen}"
      )
    }
