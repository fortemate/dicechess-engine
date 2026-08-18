package dicechess.engine.movegen

import dicechess.engine.domain.*
import munit.FunSuite

/** Position fixtures for [[MoveGenerator.allAttackers]] (#509), the complete-set counterpart to
  * [[MoveGenerator.isSquareAttacked]]. The anchor case is "several types attack one square": that is exactly where the
  * early-exit variant under-reports, and the reason this function exists.
  */
class AllAttackersSpec extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).getOrElse(sys.error(s"Failed to parse FEN: $fen"))

  private def notations(bb: Bitboard): Set[String] =
    (0 until 64).collect { case i if bb.contains(Square.fromIndex(i)) => Square.fromIndex(i).toNotation }.toSet

  private def attackersOf(fen: String, square: String, color: Color): Set[String] =
    notations(MoveGenerator.allAttackers(parse(fen), Square.fromNotation(square).get, color))

  test("a single attacker of each type is found on its own"):
    // Every fixture places exactly one white piece that bears on the target square, and nothing else that could.
    assertEquals(attackersOf("4k3/8/8/8/3P4/8/8/4K3 w - - 0 1", "e5", Color.White), Set("d4"), "pawn")
    assertEquals(attackersOf("4k3/8/8/8/3N4/8/8/4K3 w - - 0 1", "e6", Color.White), Set("d4"), "knight")
    assertEquals(attackersOf("4k3/8/8/8/3B4/8/8/4K3 w - - 0 1", "g7", Color.White), Set("d4"), "bishop")
    assertEquals(attackersOf("4k3/8/8/8/3R4/8/8/4K3 w - - 0 1", "d7", Color.White), Set("d4"), "rook")
    assertEquals(attackersOf("4k3/8/8/8/3Q4/8/8/4K3 w - - 0 1", "g7", Color.White), Set("d4"), "queen, diagonally")
    assertEquals(attackersOf("4k3/8/8/8/3Q4/8/8/4K3 w - - 0 1", "d7", Color.White), Set("d4"), "queen, along the file")
    assertEquals(attackersOf("4k3/8/8/8/8/8/8/4K3 w - - 0 1", "e2", Color.White), Set("e1"), "king")

  test("several types attacking one square are ALL returned — where isSquareAttacked stops at the first"):
    // d5 is attacked by the c4 pawn, the b4 knight and the d1 rook (d2/d3/d4 are empty, so the file is open).
    val fen   = "4k3/8/8/8/1NP5/8/8/3RK3 w - - 0 1"
    val state = parse(fen)
    val d5    = Square.fromNotation("d5").get

    assertEquals(attackersOf(fen, "d5", Color.White), Set("c4", "b4", "d1"))
    assertEquals(MoveGenerator.allAttackers(state, d5, Color.White).count, 3)

    // The contract this function exists for: the early-exit variant reports only the pawn, since pawns are checked
    // first, and its `.count` is therefore 1 — a number that looks like an attacker tally but is not one.
    assertEquals(notations(MoveGenerator.isSquareAttacked(state, d5, Color.White)), Set("c4"))
    assertEquals(MoveGenerator.isSquareAttacked(state, d5, Color.White).count, 1)

  test("two queens on different lines are two attackers — the union never merges distinct pieces"):
    // d1 bears on d4 along the file, a1 along the a1-h8 diagonal; b2/c3 and d2/d3 are empty.
    val fen = "4k3/8/8/8/8/8/8/Q2QK3 w - - 0 1"
    assertEquals(attackersOf(fen, "d4", Color.White), Set("a1", "d1"))
    assertEquals(MoveGenerator.allAttackers(parse(fen), Square.fromNotation("d4").get, Color.White).count, 2)

  test("a queen counts once, not twice, despite sitting in both the diagonal and the orthogonal mask"):
    // Queens are members of `bishops | queens` AND `rooks | queens`; unioning the per-type sets (rather than adding
    // their counts) is what keeps this at one bit per piece.
    val fen = "4k3/8/8/8/8/8/8/3QK3 w - - 0 1"
    assertEquals(MoveGenerator.allAttackers(parse(fen), Square.fromNotation("d4").get, Color.White).count, 1)

  test("a full board: d2's four defenders in the initial position, across four piece types"):
    // The densest position there is, where every slider mask is blocked almost immediately. White's d2 pawn is
    // defended by the b1 knight, the c1 bishop, the d1 queen and the e1 king — four attackers of four different
    // types. The c2/e2 pawns are NOT among them: pawns defend diagonally forward, so they cover d3, not d2.
    val fen   = FenParser.InitialPosition
    val state = parse(fen)
    val d2    = Square.fromNotation("d2").get

    assertEquals(attackersOf(fen, "d2", Color.White), Set("b1", "c1", "d1", "e1"))
    assertEquals(MoveGenerator.allAttackers(state, d2, Color.White).count, 4)

    // The same contrast as the sparse case, on a realistic board: knights are probed before sliders and the king, so
    // the early-exit variant reports the knight alone.
    assertEquals(notations(MoveGenerator.isSquareAttacked(state, d2, Color.White)), Set("b1"))

    // Nothing black reaches across the untouched pawn walls.
    assert(MoveGenerator.allAttackers(state, d2, Color.Black).isEmpty)

  test("a slider blocked by an occupying piece does not attack through it"):
    // The d1 rook's path to d5 is blocked by the white d3 pawn — and that pawn attacks c4/e4, never d5, so nothing
    // white attacks d5 at all.
    assertEquals(attackersOf("4k3/8/8/8/8/3P4/8/3RK3 w - - 0 1", "d5", Color.White), Set.empty)

  test("an unattacked square yields the empty bitboard"):
    assert(
      MoveGenerator
        .allAttackers(parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1"), Square.fromNotation("d5").get, Color.White)
        .isEmpty
    )

  test("only the asked-for colour is reported, on a square both sides attack"):
    // White c4 pawn and black c6 pawn both bear on d5.
    val fen = "4k3/8/2p5/8/2P5/8/8/4K3 w - - 0 1"
    assertEquals(attackersOf(fen, "d5", Color.White), Set("c4"))
    assertEquals(attackersOf(fen, "d5", Color.Black), Set("c6"))

  test("attacks by a piece that classical chess would call pinned still count"):
    // The e2 bishop stands between its king and the e8 rook. Dice Chess is won by capturing the king, so moving it is
    // legal and its attack on d3 is real — the same semantics PieceSafety documents for en prise.
    assertEquals(attackersOf("4r1k1/8/8/8/8/8/4B3/4K3 w - - 0 1", "d3", Color.White), Set("e2"))
