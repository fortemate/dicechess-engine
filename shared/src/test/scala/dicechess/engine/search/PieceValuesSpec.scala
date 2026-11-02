// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** Fixtures and property oracle for the parameterized minimum-attacker-value helper (#395).
  *
  * The property compares [[PieceValues.minimum]] against the minimum of the prices of the types actually present,
  * computed by a plain `List` fold, over arbitrary (including negative and inverted) tables and all 64 type masks: a
  * helper that short-circuits in canonical price order would fail it.
  */
class PieceValuesSpec extends ScalaCheckSuite:

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get
  private def sq(notation: String): Square  = Square.fromNotation(notation).get

  private val tableGen: Gen[PieceValues] =
    val price = Gen.choose(-1000, 1000)
    for
      p <- price
      n <- price
      b <- price
      r <- price
      q <- price
      k <- price
    yield PieceValues(p, n, b, r, q, k)

  test("the canonical table reproduces MaterialValues, type by type"):
    val canonical = PieceValues.Canonical
    assertEquals(canonical.of(PieceType.Pawn), MaterialValues.Pawn)
    assertEquals(canonical.of(PieceType.Knight), MaterialValues.Knight)
    assertEquals(canonical.of(PieceType.Bishop), MaterialValues.Bishop)
    assertEquals(canonical.of(PieceType.Rook), MaterialValues.Rook)
    assertEquals(canonical.of(PieceType.Queen), MaterialValues.Queen)
    assertEquals(canonical.of(PieceType.King), MaterialValues.King)

  test("the cheapest attacker on the canonical scale: knight beats queen"):
    val state = parse("4k3/8/2p5/3Q4/1N6/8/8/4K3 w - - 0 1")
    val mask  = AttackerTypes.mask(state, sq("c6"), Color.White)
    assertEquals(PieceValues.Canonical.minimum(mask), Some(300))
    assertEquals(PieceValues.Canonical.minimumOrElse(mask, empty = -1), 300)

  test("an inverted table picks the queen over the knight — the helper assumes no ordering"):
    val state    = parse("4k3/8/2p5/3Q4/1N6/8/8/4K3 w - - 0 1")
    val mask     = AttackerTypes.mask(state, sq("c6"), Color.White)
    val inverted = PieceValues(pawn = 900, knight = 500, bishop = 300, rook = 300, queen = 100, king = 10000)
    assertEquals(inverted.minimum(mask), Some(100))

  test("a diagonal queen is priced as a queen, never as a bishop"):
    val state      = parse("4k3/8/2p5/3Q4/8/8/8/4K3 w - - 0 1")
    val mask       = AttackerTypes.mask(state, sq("c6"), Color.White)
    val cheapQueen = PieceValues(pawn = 100, knight = 300, bishop = 1, rook = 500, queen = 900, king = 10000)
    assertEquals(cheapQueen.minimum(mask), Some(900))

  test("the empty mask has no minimum and returns the caller's sentinel"):
    assertEquals(PieceValues.Canonical.minimum(AttackerTypes.Empty), None)
    assertEquals(PieceValues.Canonical.minimumOrElse(AttackerTypes.Empty, empty = 42), 42)
    val state = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    assertEquals(PieceValues.Canonical.minimumOn(state, Bitboard.empty, empty = 7), 7)
    assertEquals(PieceValues.Canonical.minimumOn(state, Bitboard.fromSquare(sq("d5")), empty = 7), 7)

  test("the sentinel is not consulted when a type is present, even when it is smaller than every price"):
    assertEquals(PieceValues.Canonical.minimumOrElse(AttackerTypes.bit(PieceType.Queen), empty = 0), 900)
    assertEquals(PieceValues.Canonical.minimumOrElse(AttackerTypes.All, empty = Int.MinValue), 100)

  test("a king priced as a sentinel never wins while cheaper attackers are present, but is the answer alone"):
    val prices = PieceValues(100, 300, 300, 500, 900, king = 10000)
    val state  = parse("4k3/8/8/3p4/3K4/8/8/8 w - - 0 1")
    val mask   = AttackerTypes.mask(state, sq("d5"), Color.White)
    assertEquals(prices.minimum(mask), Some(10000))
    assertEquals(prices.minimum(mask | AttackerTypes.bit(PieceType.Rook)), Some(500))

  test("minimumOn composes the square-set mask: the defended pawn is still priced from the attacker's side"):
    // Black's e6 pawn defends d5; the white knight on e3 attacks it. Defence does not alter the attacking price.
    val state     = parse("4k3/8/4p3/3p4/8/4N3/8/4K3 w - - 0 1")
    val attackers = MoveGenerator.allAttackers(state, sq("d5"), Color.White)
    val defenders = MoveGenerator.allAttackers(state, sq("d5"), Color.Black)
    assertEquals(PieceValues.Canonical.minimumOn(state, attackers, empty = 0), 300)
    assertEquals(PieceValues.Canonical.minimumOn(state, defenders, empty = 0), 100)

  test("equality is structural and derives CanEqual"):
    val table = PieceValues(1, 2, 3, 4, 5, 6)
    val same  = PieceValues(pawn = 1, knight = 2, bishop = 3, rook = 4, queen = 5, king = 6)
    assertEquals(table, same)
    assertNotEquals(table, same.copy(king = 7))

  property("minimum equals the fold over the prices of the present types, for arbitrary tables and all masks"):
    forAll(tableGen) { (table: PieceValues) =>
      (0 to 63).forall { mask =>
        val present  = PieceType.all.filter(pt => AttackerTypes.contains(mask, pt)).map(table.of)
        val expected = present.minOption
        table.minimum(mask) == expected &&
        table.minimumOrElse(mask, empty = Int.MaxValue) == expected.getOrElse(Int.MaxValue)
      } :| s"table=$table"
    }

  property("minimumOn over a random square set agrees with minimum over AttackerTypes.mask"):
    val domainProps = new dicechess.engine.domain.PropertySpec()
    forAll(domainProps.gameStateGen, domainProps.bitboardGen, tableGen) {
      (state: GameState, squares: Bitboard, table: PieceValues) =>
        val mask = AttackerTypes.mask(state, squares)
        table.minimumOn(state, squares, empty = Int.MinValue) == table.minimumOrElse(mask, Int.MinValue)
    }
