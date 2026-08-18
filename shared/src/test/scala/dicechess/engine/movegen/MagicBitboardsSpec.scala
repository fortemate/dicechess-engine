package dicechess.engine.movegen

import munit.FunSuite
import dicechess.engine.domain.{Bitboard, Square}
import dicechess.engine.testutils.TestBoard
import dicechess.engine.testutils.TestBoard.*

class MagicBitboardsSpec extends FunSuite:

  test("Bishop attacks on empty board") {
    val sq        = Square.fromIndex(28) // e4
    val occupancy = Bitboard.empty
    val attacks   = MagicBitboards.bishopAttacks(sq, occupancy)
    val expected  = MagicBitboards.bishopAttacksClassic(sq, occupancy)
    assertEquals(attacks, expected)
  }

  test("Bishop attacks with blockers using TestBoard Varargs DSL") {
    val sq        = "e4".sq
    val occupancy = TestBoard("d3", "f5")
    val attacks   = MagicBitboards.bishopAttacks(sq, occupancy)
    val expected  = MagicBitboards.bishopAttacksClassic(sq, occupancy)
    assertEquals(attacks, expected)
  }

  test("Bishop attacks with blockers - explicit expected squares") {
    val sq = "e4".sq

    // B - Bishop on e4
    // P - Blockers on d3 (SW) and f5 (NE)
    val occupancy = TestBoard("d3", "f5")
    val attacks   = MagicBitboards.bishopAttacks(sq, occupancy)

    // NE: blocked at f5 (included), SW: blocked at d3 (included)
    // NW: d5, c6, b7, a8 (open), SE: f3, g2, h1 (open)
    val expected = TestBoard("f5", "d3", "d5", "c6", "b7", "a8", "f3", "g2", "h1")

    assertEquals(attacks, expected)
  }

  test("Rook attacks on empty board") {
    val sq        = Square.fromIndex(28) // e4
    val occupancy = Bitboard.empty
    val attacks   = MagicBitboards.rookAttacks(sq, occupancy)
    val expected  = MagicBitboards.rookAttacksClassic(sq, occupancy)
    assertEquals(attacks, expected)
  }

  test("Rook attacks with blockers using TestBoard ASCII DSL") {
    val sq = "e4".sq

    // R - Rook on e4
    // P - Blockers on e2, e6, c4, g4
    val pos = TestBoard.fromAscii("""
      - - - - - - - -
      - - - - - - - -
      - - - - P - - -
      - - - - - - - -
      - - P - R - P -
      - - - - - - - -
      - - - - P - - -
      - - - - - - - -
    """)

    val occupancy = pos.occupied
    val attacks   = MagicBitboards.rookAttacks(sq, occupancy)

    // Using varargs builder for expected attacks
    val expected = TestBoard(
      "e5",
      "e6", // North up to blocker
      "e3",
      "e2", // South down to blocker
      "d4",
      "c4", // West up to blocker
      "f4",
      "g4" // East up to blocker
    )

    assertEquals(attacks, expected)
  }

  test("Queen attacks") {
    val sq        = Square.fromIndex(28) // e4
    val occupancy = Bitboard.empty
    val attacks   = MagicBitboards.queenAttacks(sq, occupancy)
    val expected = MagicBitboards.bishopAttacksClassic(sq, occupancy) | MagicBitboards.rookAttacksClassic(sq, occupancy)
    assertEquals(attacks, expected)
  }

  test("Corner cases (A1)") {
    val sq        = Square.fromIndex(0) // a1
    val occupancy = Bitboard.empty
    assertEquals(MagicBitboards.bishopAttacks(sq, occupancy), MagicBitboards.bishopAttacksClassic(sq, occupancy))
    assertEquals(MagicBitboards.rookAttacks(sq, occupancy), MagicBitboards.rookAttacksClassic(sq, occupancy))
  }

  test("Corner cases (H8)") {
    val sq        = Square.fromIndex(63) // h8
    val occupancy = Bitboard.empty
    assertEquals(MagicBitboards.bishopAttacks(sq, occupancy), MagicBitboards.bishopAttacksClassic(sq, occupancy))
    assertEquals(MagicBitboards.rookAttacks(sq, occupancy), MagicBitboards.rookAttacksClassic(sq, occupancy))
  }
