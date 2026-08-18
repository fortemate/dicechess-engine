package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Position fixtures for the en-prise definition behind the arena's hang telemetry (#494). Each case pins one clause of
  * the [[PieceSafety]] Scaladoc: boolean attack/defense, king defense counting, kings never hanging.
  */
class PieceSafetySuite extends FunSuite:

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  private def notations(bb: Bitboard): Set[String] =
    (0 until 64).collect { case i if bb.contains(Square.fromIndex(i)) => Square.fromIndex(i).toNotation }.toSet

  test("a queen attacked by a pawn and undefended is hanging — on both sides of the same position"):
    // White queen d5 stands in the black c6-pawn's attack; the pawn in turn stands in the queen's. Neither is defended.
    val state = parse("4k3/8/2p5/3Q4/8/8/8/4K3 w - - 0 1")
    assertEquals(notations(PieceSafety.hangingSquares(state, Color.White)), Set("d5"))
    assertEquals(notations(PieceSafety.hangingSquares(state, Color.Black)), Set("c6"))
    assertEquals(PieceSafety.hangingMaterial(state, Color.White), 900)
    assertEquals(PieceSafety.hangingMaterial(state, Color.Black), 100)

  test("a defended piece is not hanging, however cheap the attacker"):
    // Same attack as above, but the d1 rook now defends the queen along the open d-file (boolean defense: the pawn
    // still "wins" the exchange, and the simplified definition deliberately does not care).
    val state = parse("4k3/8/2p5/3Q4/8/8/8/3RK3 w - - 0 1")
    assert(PieceSafety.hangingSquares(state, Color.White).isEmpty)

  test("defense by the own king counts, per the documented simplification"):
    // The d2 rook attacks the white queen on d1; the e1 king "defends" it. The rook itself is en prise to the queen.
    val state = parse("4k3/8/8/8/8/8/3r4/3QK3 w - - 0 1")
    assert(PieceSafety.hangingSquares(state, Color.White).isEmpty)
    assertEquals(notations(PieceSafety.hangingSquares(state, Color.Black)), Set("d2"))
    assertEquals(PieceSafety.hangingMaterial(state, Color.Black), 500)

  test("an attacked king is check, never a hanging piece"):
    // The h4 bishop attacks the bare e1 king along the diagonal; the king must not appear as hanging material.
    val state = parse("4k3/8/8/8/7b/8/8/4K3 w - - 0 1")
    assert(PieceSafety.hangingSquares(state, Color.White).isEmpty)
    assertEquals(PieceSafety.hangingMaterial(state, Color.White), 0)

  test("the starting position has nothing hanging for either side"):
    val state = parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    assert(PieceSafety.hangingSquares(state, Color.White).isEmpty)
    assert(PieceSafety.hangingSquares(state, Color.Black).isEmpty)

  test("an attack by a pinned piece still counts — Dice Chess has no pins"):
    // The e2 bishop is "pinned" against its king by the e8 rook in classical-chess terms — but the game is won by
    // capturing the king, so moving a pinned piece is perfectly legal here and its attack on d3 is a real threat.
    // Geometry-based isSquareAttacked is therefore the *correct* semantics, not a simplification: the undefended
    // d3 pawn hangs to the pinned bishop. The bishop itself is attacked (rook) but king-defended, so White has nothing
    // hanging.
    val state = parse("4r1k1/8/8/8/8/3p4/4B3/4K3 w - - 0 1")
    assertEquals(notations(PieceSafety.hangingSquares(state, Color.Black)), Set("d3"))
    assertEquals(PieceSafety.hangingMaterial(state, Color.Black), 100)
    assert(PieceSafety.hangingSquares(state, Color.White).isEmpty)

  test("bare kings: nothing hangs and no material is at risk on an otherwise-empty board"):
    val state = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    assert(PieceSafety.hangingSquares(state, Color.White).isEmpty)
    assert(PieceSafety.hangingSquares(state, Color.Black).isEmpty)
    assertEquals(PieceSafety.hangingMaterial(state, Color.White), 0)
    assertEquals(PieceSafety.hangingMaterial(state, Color.Black), 0)

  test("materialOn prices exactly the given squares on the Evaluator's centipawn scale"):
    val state = parse("4k3/8/2p5/3Q4/8/8/8/4K3 w - - 0 1")
    val both  = PieceSafety.hangingSquares(state, Color.White) | PieceSafety.hangingSquares(state, Color.Black)
    assertEquals(PieceSafety.materialOn(state, both), 1000)
    assertEquals(PieceSafety.materialOn(state, Bitboard.empty), 0)
