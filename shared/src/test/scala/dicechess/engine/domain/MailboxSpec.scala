package dicechess.engine.domain

import munit.FunSuite

/** Unit tests for the [[Mailbox]] opaque type and its extension methods.
  *
  * Verifies O(1) read/write semantics, emptiness behaviour, `get` option wrapping, and `toArray` cloning.
  */
class MailboxSpec extends FunSuite:

  private val whitePawn   = Piece(Color.White, PieceType.Pawn)
  private val blackKnight = Piece(Color.Black, PieceType.Knight)
  private val e4          = Square('e', 4)
  private val d5          = Square('d', 5)
  private val a1          = Square('a', 1)
  private val h8          = Square('h', 8)

  test("Mailbox.empty should have 64 squares all set to Piece.Empty") {
    val mb = Mailbox.empty
    for idx <- 0 until 64 do assertEquals(mb(Square.fromIndex(idx)), Piece.Empty)
  }

  test("Mailbox.fromBuilder should store and retrieve pieces correctly") {
    val builder = Array.fill(64)(Piece.Empty)
    builder(e4.index) = whitePawn
    builder(d5.index) = blackKnight

    val mb = Mailbox.fromBuilder(builder)
    assertEquals(mb(e4), whitePawn)
    assertEquals(mb(d5), blackKnight)
    assertEquals(mb(a1), Piece.Empty)
  }

  test("Mailbox.apply should return Piece.Empty for unoccupied squares") {
    val mb = Mailbox.empty
    assertEquals(mb(h8), Piece.Empty)
    assert(mb(h8).isEmpty)
  }

  test("Mailbox.get should return Some(piece) for occupied square") {
    val builder = Array.fill(64)(Piece.Empty)
    builder(a1.index) = whitePawn
    val mb = Mailbox.fromBuilder(builder)
    assertEquals(mb.get(a1), Some(whitePawn))
  }

  test("Mailbox.get should return None for unoccupied square") {
    val mb = Mailbox.empty
    assertEquals(mb.get(h8), None)
  }

  test("Mailbox.get should return None for Piece.Empty") {
    val mb = Mailbox.empty
    assert(mb.get(Square('c', 3)).isEmpty)
  }

  test("Mailbox.toArray should return a defensive clone, not the internal array") {
    val builder = Array.fill(64)(Piece.Empty)
    builder(e4.index) = whitePawn
    val mb = Mailbox.fromBuilder(builder)

    val arr = mb.toArray
    // Mutate the clone — the mailbox must not be affected
    arr(e4.index) = Piece.Empty
    assertEquals(mb(e4), whitePawn, "Mailbox must remain immutable after toArray clone is mutated")
  }

  test("Mailbox.toArray should contain the same pieces as the original") {
    val builder = Array.fill(64)(Piece.Empty)
    builder(a1.index) = whitePawn
    builder(h8.index) = blackKnight
    val mb = Mailbox.fromBuilder(builder)

    val arr = mb.toArray
    assertEquals(arr(a1.index), whitePawn)
    assertEquals(arr(h8.index), blackKnight)
    assertEquals(arr(e4.index), Piece.Empty)
  }

  test("Mailbox should correctly store all piece types for both colors") {
    val builder = Array.fill(64)(Piece.Empty)
    val pieces  = for
      color <- List(Color.White, Color.Black)
      pt    <- PieceType.all
    yield Piece(color, pt)

    pieces.zipWithIndex.foreach { case (piece, idx) =>
      builder(idx) = piece
    }

    val mb = Mailbox.fromBuilder(builder)
    pieces.zipWithIndex.foreach { case (expected, idx) =>
      assertEquals(mb(Square.fromIndex(idx)), expected)
    }
  }
