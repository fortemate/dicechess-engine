// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Fixtures for the exact attacking piece-type mask and its single-roll availability (#395). Each case pins one clause
  * of the [[AttackerTypes]] Scaladoc: one bit per type, the queen-slider intersection rule, both colours, defended and
  * undefended targets, and the `216 - (6 - k)^3` availability boundaries.
  */
class AttackerTypesSuite extends FunSuite:

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get
  private def sq(notation: String): Square  = Square.fromNotation(notation).get

  private val typesInDiceOrder = PieceType.all

  test("every piece type attacking alone sets exactly its own bit, for both colours"):
    // Each fixture: a lone white attacker of one type bearing on d5 (black pawn), mirrored for Black against d4.
    val whiteAttackers = List(
      PieceType.Pawn   -> "4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1",
      PieceType.Knight -> "4k3/8/8/3p4/8/4N3/8/4K3 w - - 0 1",
      PieceType.Bishop -> "4k3/8/8/3p4/8/5B2/8/4K3 w - - 0 1",
      PieceType.Rook   -> "4k3/8/8/3p4/8/8/8/3RK3 w - - 0 1",
      PieceType.Queen  -> "4k3/8/8/3p4/8/8/8/3QK3 w - - 0 1",
      PieceType.King   -> "4k3/8/8/3p4/3K4/8/8/8 w - - 0 1"
    )
    val blackAttackers = List(
      PieceType.Pawn   -> "4k3/8/8/4p3/3P4/8/8/4K3 b - - 0 1",
      PieceType.Knight -> "4k3/8/4n3/8/3P4/8/8/4K3 b - - 0 1",
      PieceType.Bishop -> "4k3/8/5b2/8/3P4/8/8/4K3 b - - 0 1",
      PieceType.Rook   -> "3rk3/8/8/8/3P4/8/8/4K3 b - - 0 1",
      PieceType.Queen  -> "3qk3/8/8/8/3P4/8/8/4K3 b - - 0 1",
      PieceType.King   -> "8/8/8/3k4/3P4/8/8/4K3 b - - 0 1"
    )
    for (expected, fen) <- whiteAttackers do
      val mask = AttackerTypes.mask(parse(fen), sq("d5"), Color.White)
      assertEquals(mask, AttackerTypes.bit(expected), s"white $fen")
      assertEquals(AttackerTypes.count(mask), 1)
      for pt <- typesInDiceOrder do assertEquals(AttackerTypes.contains(mask, pt), pt == expected)
    for (expected, fen) <- blackAttackers do
      val mask = AttackerTypes.mask(parse(fen), sq("d4"), Color.Black)
      assertEquals(mask, AttackerTypes.bit(expected), s"black $fen")
      assertEquals(AttackerTypes.count(mask), 1)

  test("a diagonal queen sets the Queen bit only, never the Bishop bit"):
    val state = parse("4k3/8/8/8/8/5Q2/8/4K3 w - - 0 1") // f3 queen bears on d5 along the diagonal
    val mask  = AttackerTypes.mask(state, sq("d5"), Color.White)
    assertEquals(mask, AttackerTypes.bit(PieceType.Queen))
    assert(!AttackerTypes.contains(mask, PieceType.Bishop))

  test("an orthogonal queen sets the Queen bit only, never the Rook bit"):
    val state = parse("4k3/8/8/8/8/8/8/3QK3 w - - 0 1") // d1 queen bears on d5 along the file
    val mask  = AttackerTypes.mask(state, sq("d5"), Color.White)
    assertEquals(mask, AttackerTypes.bit(PieceType.Queen))
    assert(!AttackerTypes.contains(mask, PieceType.Rook))

  test("a queen and a real bishop on the same diagonal set both bits exactly once each"):
    // The bishop on b3 and the queen on c4 share the a2–g8 diagonal; the bishop's line to d5 passes through the queen,
    // so only the queen attacks d5 directly. The second fixture swaps them, so the bishop is in front.
    val queenInFront  = parse("4k3/8/8/3p4/2Q5/1B6/8/4K3 w - - 0 1")
    val bishopInFront = parse("4k3/8/8/3p4/2B5/1Q6/8/4K3 w - - 0 1")
    assertEquals(AttackerTypes.mask(queenInFront, sq("d5"), Color.White), AttackerTypes.bit(PieceType.Queen))
    assertEquals(AttackerTypes.mask(bishopInFront, sq("d5"), Color.White), AttackerTypes.bit(PieceType.Bishop))

  test("mixed attackers accumulate one bit per distinct type regardless of multiplicity"):
    // Two white knights (b4, f4), a rook (d1) and a pawn (c4) all bear on d5.
    val state    = parse("4k3/8/8/3p4/1NP2N2/8/8/3RK3 w - - 0 1")
    val mask     = AttackerTypes.mask(state, sq("d5"), Color.White)
    val expected =
      AttackerTypes.bit(PieceType.Pawn) | AttackerTypes.bit(PieceType.Knight) | AttackerTypes.bit(PieceType.Rook)
    assertEquals(mask, expected)
    assertEquals(AttackerTypes.count(mask), 3)
    assertEquals(AttackerTypes.availableRolls(mask), 216 - 27)

  test("an unattacked square yields the empty mask, zero count and zero availability"):
    val state = parse("4k3/8/8/3p4/8/8/8/4K3 w - - 0 1")
    val mask  = AttackerTypes.mask(state, sq("d5"), Color.White)
    assertEquals(mask, AttackerTypes.Empty)
    assertEquals(AttackerTypes.count(mask), 0)
    assertEquals(AttackerTypes.availableRolls(mask), 0)
    assertEquals(AttackerTypes.availabilityProbability(mask), 0.0)

  test("whether the target is defended does not change the attacking side's mask"):
    // The white knight on e3 attacks d5; in the second fixture a black pawn on e6 defends it.
    val undefended = parse("4k3/8/8/3p4/8/4N3/8/4K3 w - - 0 1")
    val defended   = parse("4k3/8/4p3/3p4/8/4N3/8/4K3 w - - 0 1")
    val expected   = AttackerTypes.bit(PieceType.Knight)
    assertEquals(AttackerTypes.mask(undefended, sq("d5"), Color.White), expected)
    assertEquals(AttackerTypes.mask(defended, sq("d5"), Color.White), expected)
    // The defender term is the same call with the colours swapped.
    assertEquals(AttackerTypes.mask(undefended, sq("d5"), Color.Black), AttackerTypes.Empty)
    assertEquals(AttackerTypes.mask(defended, sq("d5"), Color.Black), AttackerTypes.bit(PieceType.Pawn))

  test("the mask ignores whose turn it is and which dice are in the pool"):
    val base  = parse("4k3/8/8/3p4/8/4N3/8/4K3 w - - 0 1")
    val black = parse("4k3/8/8/3p4/8/4N3/8/4K3 b - - 0 1")
    val diced = base.withDicePool(List(1, 2, 3))
    val mask  = AttackerTypes.mask(base, sq("d5"), Color.White)
    assertEquals(AttackerTypes.mask(black, sq("d5"), Color.White), mask)
    assertEquals(AttackerTypes.mask(diced, sq("d5"), Color.White), mask)

  test("the square-set overload prices whatever stands on the squares, attackers or not"):
    val state = parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    assertEquals(AttackerTypes.mask(state, Bitboard.empty), AttackerTypes.Empty)
    assertEquals(AttackerTypes.mask(state, state.whitePieces), AttackerTypes.All)
    assertEquals(AttackerTypes.mask(state, state.whitePieces | state.blackPieces), AttackerTypes.All)
    assertEquals(AttackerTypes.mask(state, state.queens), AttackerTypes.bit(PieceType.Queen))
    assertEquals(AttackerTypes.mask(state, Bitboard.fromSquare(sq("e4"))), AttackerTypes.Empty)

  test("bits beyond the six piece types are ignored by count and availability"):
    val stray = AttackerTypes.All | (1 << 7) | (1 << 30)
    assertEquals(AttackerTypes.count(stray), 6)
    assertEquals(AttackerTypes.availableRolls(stray), 216)
    assertEquals(AttackerTypes.availabilityProbability(stray), 1.0)

  test("availability counts follow 216 - (6 - k)^3 for every k in 0..6"):
    val expected = List(0, 91, 152, 189, 208, 215, 216)
    for k <- 0 to 6 do
      assertEquals(DiceRolls.rollsShowingAnyOf(k), expected(k), s"k = $k")
      assertEquals(DiceRolls.probabilityOfAnyOf(k), expected(k).toDouble / 216, s"k = $k")

  test("availability rejects a type count outside 0..6"):
    intercept[IllegalArgumentException](DiceRolls.rollsShowingAnyOf(-1))
    intercept[IllegalArgumentException](DiceRolls.rollsShowingAnyOf(7))

  test("the bit of a type is the die face that moves it"):
    for pt <- typesInDiceOrder do assertEquals(AttackerTypes.bit(pt), 1 << (pt.diceValue - 1))
    assertEquals(typesInDiceOrder.map(pt => AttackerTypes.bit(pt)).reduce(_ | _), AttackerTypes.All)
