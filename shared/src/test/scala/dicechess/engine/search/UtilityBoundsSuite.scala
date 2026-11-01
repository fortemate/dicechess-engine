// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** [[UtilityBounds]] contract (#393): strict terminal dominance, one declared perspective and the scale the expectimax
  * tree actually reasons on.
  */
class UtilityBoundsSuite extends FunSuite:

  test("the constructor rejects every violation of terminalLoss < minNonTerminal <= maxNonTerminal < terminalWin"):
    intercept[IllegalArgumentException](UtilityBounds(Color.White, 0.0, 0.0, 1.0, 2.0))  // loss == min
    intercept[IllegalArgumentException](UtilityBounds(Color.White, -1.0, 2.0, 1.0, 3.0)) // min > max
    intercept[IllegalArgumentException](UtilityBounds(Color.White, -1.0, 0.0, 1.0, 1.0)) // max == win
    val ok = UtilityBounds(Color.White, -1.0, 0.0, 0.0, 1.0) // min == max is allowed
    assertEquals(ok.minNonTerminal, ok.maxNonTerminal)

  test("the perspective decides the role: MAX for the perspective's own turns, MIN for the opponent's"):
    val white = UtilityBounds(Color.White, -10.0, -1.0, 1.0, 10.0)
    assert(white.isMaximizing(Color.White))
    assert(!white.isMaximizing(Color.Black))
    assertEquals(white.terminalFor(Color.White), 10.0)
    assertEquals(white.terminalFor(Color.Black), -10.0)
    val black = white.flipped
    assertEquals(black.perspective, Color.Black)
    assert(black.isMaximizing(Color.Black))
    assertEquals(black.terminalFor(Color.White), -10.0)

  test("clamps and terminal tests follow the declared edges"):
    val bounds = UtilityBounds(Color.White, -10.0, -1.0, 1.0, 10.0)
    assertEquals(bounds.clampNonTerminal(5.0), 1.0)
    assertEquals(bounds.clampNonTerminal(-5.0), -1.0)
    assertEquals(bounds.clampNonTerminal(0.5), 0.5)
    assertEquals(bounds.clamp(50.0), 10.0)
    assertEquals(bounds.clamp(-50.0), -10.0)
    assert(bounds.isTerminal(10.0) && bounds.isTerminal(-10.0) && bounds.isTerminal(11.0))
    assert(!bounds.isTerminal(1.0) && !bounds.isTerminal(-1.0))

  test("ExpectimaxSearch declares a dominated scale and still short-circuits root captures with TerminalWinScore"):
    val bounds = ExpectimaxSearch.utilityBounds(Color.White)
    assert(bounds.terminalLoss < bounds.minNonTerminal)
    assert(bounds.minNonTerminal <= bounds.maxNonTerminal)
    assert(bounds.maxNonTerminal < bounds.terminalWin)
    // Material scores live inside the declared non-terminal range on the standard board.
    val start = FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").toOption.get
    val score = Evaluator.evaluateMaterial(start, Color.White).toDouble
    assertEquals(bounds.clampNonTerminal(score), score)
    // The root never uses the scale: an immediate king capture is reported as Int.MaxValue, above terminalWin.
    assert(SearchScoring.TerminalWinScore.toDouble > bounds.terminalWin)
