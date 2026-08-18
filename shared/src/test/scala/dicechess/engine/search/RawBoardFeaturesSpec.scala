package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Pins [[RawBoardFeatures]] against the layout the Python trainer encodes (`board.py::encode_fen`). These are the same
  * fixtures its unit tests use, so a drift on either side fails here: the net is a pure function of this layout, and a
  * silently transposed plane would serve a model garbage while every other test still passed.
  */
class RawBoardFeaturesSpec extends FunSuite:

  // Surfaces the parser's own message: `.toOption.get` (the pattern sibling specs use) would turn a
  // typo in a fixture into a bare NoSuchElementException instead of naming what is wrong with the FEN.
  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(error => fail(s"invalid fixture FEN '$fen': $error"), identity)

  /** Every cell must be exactly 0.0f or 1.0f — these are indicator planes, not counts. */
  private def assertBinary(features: Array[Float]): Unit =
    val offending = features.filterNot(v => v == 0.0f || v == 1.0f)
    assert(offending.isEmpty, s"non-binary values present: ${offending.take(5).mkString(", ")}")

  /** The 64 squares of one plane, by its position in the vector. */
  private def plane(features: Array[Float], index: Int): Array[Float] =
    features.slice(index * 64, (index + 1) * 64)

  private def occupied(squares: Array[Float]): List[Int] =
    squares.zipWithIndex.collect { case (v, i) if v != 0.0f => i }.toList

  private val OwnPawn  = 0
  private val OwnKing  = 5
  private val OppQueen = 10
  private val OppKing  = 11

  test("width and columnNames agree"):
    val features = RawBoardFeatures.extract(parse(FenParser.InitialPosition), Color.White)
    assertBinary(features)
    assertEquals(features.length, RawBoardFeatures.Width)
    assertEquals(features.length, 768)
    assertEquals(RawBoardFeatures.columnNames.length, 768)

  test("columnNames pins the plane order (own pawn..king, then opponent)"):
    assertEquals(RawBoardFeatures.columnNames.head, "own_p_0")
    assertEquals(RawBoardFeatures.columnNames(64 * 5), "own_k_0")
    assertEquals(RawBoardFeatures.columnNames(64 * 6), "opp_p_0")
    assertEquals(RawBoardFeatures.columnNames.last, "opp_k_63")

  test("the start position from White: own pawns on rank 2, kings on e1 and e8"):
    val features = RawBoardFeatures.extract(parse(FenParser.InitialPosition), Color.White)
    assertEquals(occupied(plane(features, OwnPawn)), (8 to 15).toList)
    assertEquals(occupied(plane(features, OwnKing)), List(4))  // e1
    assertEquals(occupied(plane(features, OppKing)), List(60)) // e8
    assertEquals(features.sum, 32.0f)

  test("the start position encodes identically for both movers"):
    // It maps onto itself under mirror-and-swap, so a mover-canonical encoding cannot tell the two apart.
    val white = RawBoardFeatures.extract(parse(FenParser.InitialPosition), Color.White)
    val black = RawBoardFeatures.extract(parse(FenParser.InitialPosition), Color.Black)
    assert(white.sameElements(black))

  test("a Black mover sees mirrored ranks and swapped colors"):
    val state = parse("4k3/8/8/8/8/8/8/Q3K3 w - - 0 1")
    val white = RawBoardFeatures.extract(state, Color.White)
    assertEquals(occupied(plane(white, 4)), List(0)) // own queen on a1
    val black = RawBoardFeatures.extract(state, Color.Black)
    // Same board from Black: the white queen is the OPPONENT's, and a1 mirrors onto index 56 —
    // ranks flip, files do not.
    assertEquals(occupied(plane(black, OppQueen)), List(56))
    assertEquals(plane(black, 4).sum, 0.0f)

  test("files are not mirrored, only ranks"):
    // A rook on h1 must land on index 7 for White and 63 for Black — an a1/h1 swap would put it on 56.
    val state = parse("4k3/8/8/8/8/8/8/4K2R w - - 0 1")
    assertEquals(occupied(plane(RawBoardFeatures.extract(state, Color.White), 3)), List(7))
    assertEquals(occupied(plane(RawBoardFeatures.extract(state, Color.Black), 9)), List(63))

  test("every piece type gets its own plane"):
    val state    = parse("4k3/8/8/8/8/8/8/RNBQK3 w - - 0 1")
    val features = RawBoardFeatures.extract(state, Color.White)
    assertEquals(occupied(plane(features, 3)), List(0)) // rook a1
    assertEquals(occupied(plane(features, 1)), List(1)) // knight b1
    assertEquals(occupied(plane(features, 2)), List(2)) // bishop c1
    assertEquals(occupied(plane(features, 4)), List(3)) // queen d1
    assertEquals(occupied(plane(features, OwnKing)), List(4))
    assertEquals(features.sum, 6.0f)

  test("the encoding is dice-free"):
    val withPawnDice = parse("4k3/8/8/8/8/8/8/4K2R w - - 0 1")
    val withKingDice = parse("4k3/8/8/8/8/8/8/4K2R w - - 0 1").withDicePool(List(6, 6, 6))
    assert(
      RawBoardFeatures
        .extract(withPawnDice, Color.White)
        .sameElements(RawBoardFeatures.extract(withKingDice, Color.White))
    )

  test("an empty board encodes to all zeros"):
    val features = RawBoardFeatures.extract(parse("8/8/8/8/8/8/8/8 w - - 0 1"), Color.White)
    assertBinary(features)
    assertEquals(features.sum, 0.0f)
    assertEquals(features.length, RawBoardFeatures.Width)

  test("a fully occupied board sets exactly 64 cells, one per square"):
    // Every square filled: the planes partition the board, so no square may land in two of them.
    val fen      = "rrrrkrrr/pppppppp/pppppppp/pppppppp/PPPPPPPP/PPPPPPPP/PPPPPPPP/RRRRKRRR w - - 0 1"
    val features = RawBoardFeatures.extract(parse(fen), Color.White)
    assertBinary(features)
    assertEquals(features.sum, 64.0f)
    // Summing the 12 planes cell-wise must give 1 everywhere — a double-counted square would give 2.
    val perSquare = (0 until 64).map(sq => (0 until 12).map(pl => features(pl * 64 + sq)).sum)
    assertEquals(perSquare.toSet, Set(1.0f))
