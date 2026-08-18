package dicechess.engine.domain

import munit.FunSuite

class MoveSpec extends FunSuite:

  test("Move should correctly encode and decode fromSquare and toSquare for a quiet move") {
    val from = Square('e', 2)
    val to   = Square('e', 4)
    val move = Move(from, to)

    assertEquals(move.fromSquare, from)
    assertEquals(move.toSquare, to)
    assertEquals(move.flags, Move.QuietMove)
    assert(!move.isCapture)
    assert(!move.isPromotion)
  }

  test("Move should correctly encode and decode standard captures") {
    val from = Square('d', 4)
    val to   = Square('e', 5)
    val move = Move(from, to, Move.Capture)

    assertEquals(move.fromSquare, from)
    assertEquals(move.toSquare, to)
    assertEquals(move.flags, Move.Capture)
    assert(move.isCapture)
    assert(!move.isPromotion)
  }

  test("Move should correctly identify en passant captures") {
    val move = Move(Square('e', 5), Square('d', 6), Move.EnPassantCapture)
    assert(move.isCapture)
    assert(move.isEnPassant)
  }

  test("Move should correctly identify promotions and promotion captures") {
    val promo = Move(Square('a', 7), Square('a', 8), Move.QueenPromotion)
    assert(promo.isPromotion)
    assert(!promo.isCapture)

    val promoCapture = Move(Square('b', 7), Square('c', 8), Move.KnightPromoCapture)
    assert(promoCapture.isPromotion)
    assert(promoCapture.isCapture)
  }

  test("Move should correctly identify castling") {
    val kingCastle  = Move(Square('e', 1), Square('g', 1), Move.KingCastle)
    val queenCastle = Move(Square('e', 8), Square('c', 8), Move.QueenCastle)

    assert(kingCastle.isCastling)
    assert(queenCastle.isCastling)
    assert(!kingCastle.isCapture)
    assert(!queenCastle.isCapture)
  }

  test("Move memory layout should fit within 16 bits") {
    val move    = Move(Square('h', 8), Square('a', 1), Move.QueenPromoCapture)
    val encoded = move.toInt

    // Max possible value is 15 (flags) | 63 (from) | 63 (to)
    // Which is 1111 111111 111111 in binary (65535, or 0xFFFF)
    assert(encoded <= 0xffff)
  }
