package dicechess.engine.movegen

import munit.FunSuite
import dicechess.engine.domain.{Square, Bitboard, Color}

class PawnGenerationSpec extends FunSuite:

  test("White single and double pushes from starting rank") {
    val pawns        = Bitboard.fromSquare(Square('e', 2)) | Bitboard.fromSquare(Square('a', 2))
    val emptySquares = ~Bitboard.empty // All squares empty

    val single = PawnGeneration.singlePushes(pawns, emptySquares, Color.White)
    assertEquals(single.count, 2)
    assert(single.contains(Square('e', 3)))
    assert(single.contains(Square('a', 3)))

    val double = PawnGeneration.doublePushes(single, emptySquares, Color.White)
    assertEquals(double.count, 2)
    assert(double.contains(Square('e', 4)))
    assert(double.contains(Square('a', 4)))
  }

  test("Black single and double pushes from starting rank") {
    val pawns        = Bitboard.fromSquare(Square('d', 7)) | Bitboard.fromSquare(Square('h', 7))
    val emptySquares = ~Bitboard.empty

    val single = PawnGeneration.singlePushes(pawns, emptySquares, Color.Black)
    assertEquals(single.count, 2)
    assert(single.contains(Square('d', 6)))
    assert(single.contains(Square('h', 6)))

    val double = PawnGeneration.doublePushes(single, emptySquares, Color.Black)
    assertEquals(double.count, 2)
    assert(double.contains(Square('d', 5)))
    assert(double.contains(Square('h', 5)))
  }

  test("Pushes should be blocked by non-empty squares") {
    val pawns = Bitboard.fromSquare(Square('e', 2))
    // e3 is occupied, so emptySquares does NOT contain e3
    val emptySquares = ~Bitboard.fromSquare(Square('e', 3))

    val single = PawnGeneration.singlePushes(pawns, emptySquares, Color.White)
    assert(single.isEmpty) // Blocked!

    val double = PawnGeneration.doublePushes(single, emptySquares, Color.White)
    assert(double.isEmpty) // Double push also fails if single push is blocked
  }

  test("Double pushes should only work from starting ranks") {
    val pawns        = Bitboard.fromSquare(Square('e', 3))
    val emptySquares = ~Bitboard.empty

    val single = PawnGeneration.singlePushes(pawns, emptySquares, Color.White)
    assert(single.contains(Square('e', 4)))

    val double = PawnGeneration.doublePushes(single, emptySquares, Color.White)
    assert(double.isEmpty) // e3 is not starting rank, cannot double push to e5
  }

  test("White pawn captures and wrap-around prevention") {
    // Pawn on H-file should NOT wrap around to A-file when attacking East
    val pawns   = Bitboard.fromSquare(Square('h', 2))
    val enemies = ~Bitboard.empty

    val east = PawnGeneration.eastCaptures(pawns, enemies, Color.White)
    assert(east.isEmpty) // Blocked by NotHFile mask

    val west = PawnGeneration.westCaptures(pawns, enemies, Color.White)
    assert(west.contains(Square('g', 3)))
  }

  test("Black pawn captures and wrap-around prevention") {
    // Pawn on A-file should NOT wrap around to H-file when attacking West
    val pawns   = Bitboard.fromSquare(Square('a', 7))
    val enemies = ~Bitboard.empty

    val west = PawnGeneration.westCaptures(pawns, enemies, Color.Black)
    assert(west.isEmpty) // Blocked by NotAFile mask

    val east = PawnGeneration.eastCaptures(pawns, enemies, Color.Black)
    assert(east.contains(Square('b', 6)))
  }

  // ---------------------------------------------------------------------------
  // Promotion helpers
  // ---------------------------------------------------------------------------

  test("promotionSquares: white pawn single push landing on rank 8 is a promotion") {
    val pawns        = Bitboard.fromSquare(Square('e', 7))
    val emptySquares = ~Bitboard.empty

    val targets = PawnGeneration.singlePushes(pawns, emptySquares, Color.White)
    assert(targets.contains(Square('e', 8)))

    val promoSquares = PawnGeneration.promotionSquares(targets, Color.White)
    assertEquals(promoSquares.count, 1)
    assert(promoSquares.contains(Square('e', 8)))

    val stdSquares = PawnGeneration.nonPromotionSquares(targets, Color.White)
    assert(stdSquares.isEmpty)
  }

  test("promotionSquares: black pawn single push landing on rank 1 is a promotion") {
    val pawns        = Bitboard.fromSquare(Square('d', 2))
    val emptySquares = ~Bitboard.empty

    val targets = PawnGeneration.singlePushes(pawns, emptySquares, Color.Black)
    assert(targets.contains(Square('d', 1)))

    val promoSquares = PawnGeneration.promotionSquares(targets, Color.Black)
    assertEquals(promoSquares.count, 1)
    assert(promoSquares.contains(Square('d', 1)))

    val stdSquares = PawnGeneration.nonPromotionSquares(targets, Color.Black)
    assert(stdSquares.isEmpty)
  }

  test("nonPromotionSquares: ordinary rank 3 push is NOT a promotion for white") {
    val pawns        = Bitboard.fromSquare(Square('e', 2))
    val emptySquares = ~Bitboard.empty

    val targets = PawnGeneration.singlePushes(pawns, emptySquares, Color.White)
    assert(targets.contains(Square('e', 3)))

    val promoSquares = PawnGeneration.promotionSquares(targets, Color.White)
    assert(promoSquares.isEmpty)

    val stdSquares = PawnGeneration.nonPromotionSquares(targets, Color.White)
    assertEquals(stdSquares.count, 1)
    assert(stdSquares.contains(Square('e', 3)))
  }

  test("promotionSquares: white capture onto rank 8 is a promotion capture") {
    // White pawn on g7 captures diagonally onto h8
    val pawns   = Bitboard.fromSquare(Square('g', 7))
    val enemies = Bitboard.fromSquare(Square('h', 8))

    val captureTargets = PawnGeneration.eastCaptures(pawns, enemies, Color.White)
    assert(captureTargets.contains(Square('h', 8)))

    val promoCaptures = PawnGeneration.promotionSquares(captureTargets, Color.White)
    assertEquals(promoCaptures.count, 1)
    assert(promoCaptures.contains(Square('h', 8)))

    val stdCaptures = PawnGeneration.nonPromotionSquares(captureTargets, Color.White)
    assert(stdCaptures.isEmpty)
  }

  test("promotionSquares: black capture onto rank 1 is a promotion capture") {
    // Black pawn on b2 captures diagonally onto a1
    val pawns   = Bitboard.fromSquare(Square('b', 2))
    val enemies = Bitboard.fromSquare(Square('a', 1))

    val captureTargets = PawnGeneration.westCaptures(pawns, enemies, Color.Black)
    assert(captureTargets.contains(Square('a', 1)))

    val promoCaptures = PawnGeneration.promotionSquares(captureTargets, Color.Black)
    assertEquals(promoCaptures.count, 1)
    assert(promoCaptures.contains(Square('a', 1)))
  }
