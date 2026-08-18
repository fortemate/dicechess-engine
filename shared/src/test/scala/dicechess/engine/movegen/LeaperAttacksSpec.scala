package dicechess.engine.movegen

import munit.FunSuite
import dicechess.engine.domain.Square

class LeaperAttacksSpec extends FunSuite:

  test("Knight on e4 should attack 8 squares") {
    val attacks = LeaperAttacks.knightAttacksFor(Square('e', 4))
    assertEquals(attacks.count, 8)
    assert(attacks.contains(Square('d', 6)))
    assert(attacks.contains(Square('f', 6)))
    assert(attacks.contains(Square('c', 5)))
    assert(attacks.contains(Square('g', 5)))
    assert(attacks.contains(Square('c', 3)))
    assert(attacks.contains(Square('g', 3)))
    assert(attacks.contains(Square('d', 2)))
    assert(attacks.contains(Square('f', 2)))
  }

  test("Knight on a1 (corner) should attack exactly 2 squares without wrapping") {
    val attacks = LeaperAttacks.knightAttacksFor(Square('a', 1))
    assertEquals(attacks.count, 2)
    assert(attacks.contains(Square('b', 3)))
    assert(attacks.contains(Square('c', 2)))
  }

  test("Knight on h8 (corner) should attack exactly 2 squares without wrapping") {
    val attacks = LeaperAttacks.knightAttacksFor(Square('h', 8))
    assertEquals(attacks.count, 2)
    assert(attacks.contains(Square('g', 6)))
    assert(attacks.contains(Square('f', 7)))
  }

  test("King on e4 should attack 8 squares") {
    val attacks = LeaperAttacks.kingAttacksFor(Square('e', 4))
    assertEquals(attacks.count, 8)
    assert(attacks.contains(Square('d', 5)))
    assert(attacks.contains(Square('e', 5)))
    assert(attacks.contains(Square('f', 5)))
    assert(attacks.contains(Square('d', 4)))
    assert(attacks.contains(Square('f', 4)))
    assert(attacks.contains(Square('d', 3)))
    assert(attacks.contains(Square('e', 3)))
    assert(attacks.contains(Square('f', 3)))
  }

  test("King on a1 (corner) should attack exactly 3 squares without wrapping") {
    val attacks = LeaperAttacks.kingAttacksFor(Square('a', 1))
    assertEquals(attacks.count, 3)
    assert(attacks.contains(Square('a', 2)))
    assert(attacks.contains(Square('b', 2)))
    assert(attacks.contains(Square('b', 1)))
  }

  test("King on h8 (corner) should attack exactly 3 squares without wrapping") {
    val attacks = LeaperAttacks.kingAttacksFor(Square('h', 8))
    assertEquals(attacks.count, 3)
    assert(attacks.contains(Square('g', 8)))
    assert(attacks.contains(Square('g', 7)))
    assert(attacks.contains(Square('h', 7)))
  }
