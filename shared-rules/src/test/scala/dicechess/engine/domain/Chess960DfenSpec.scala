// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

import munit.FunSuite

class Chess960DfenSpec extends FunSuite:
  private def position(rights: String): String =
    s"1r2kr1r/8/8/8/8/8/8/R2RK2R w $rights - 12 31 RK"

  test("rights identify individual rooks when both colors have extra rooks on a back rank"):
    val parsed = FenParser.parseChess960(position("bDHf")).toOption.get
    assertEquals(parsed.serialize, position("KDfq"))
    val squares = (0 until 64).map(Square.fromIndex).filter(parsed.castlingRooks.contains).map(_.toNotation)
    assertEquals(squares.toList, List("d1", "h1", "b8", "f8"))
    assertEquals(FenParser.parseChess960(parsed.serialize).toOption.get.castlingRooks, parsed.castlingRooks)

  test("every starting array resolves shorthand and explicit file rights to the same rooks"):
    val starts = "RNBQKBNR".permutations.filter: rank =>
      val king = rank.indexOf('K')
      rank.indexOf('R') < king && king < rank.lastIndexOf('R') &&
      (rank.indexOf('B') % 2 != rank.lastIndexOf('B') % 2)
    var count = 0
    starts.foreach: rank =>
      val left     = ('A' + rank.indexOf('R')).toChar
      val right    = ('A' + rank.lastIndexOf('R')).toChar
      val letters  = s"$right$left"
      val board    = s"${rank.toLowerCase}/pppppppp/8/8/8/8/PPPPPPPP/$rank"
      val expected = s"$board w KQkq - 0 1"
      val explicit = s"$board w $letters${letters.toLowerCase} - 0 1"
      val first    = FenParser.parseChess960(explicit).toOption.get
      val second   = FenParser.parseChess960(expected).toOption.get
      assertEquals(first.serialize, expected)
      assertEquals(first.castlingRooks, second.castlingRooks)
      count += 1
    assertEquals(count, 960)

  test("invalid rights are rejected and classical parsing keeps its original alphabet"):
    for field <- List("Z", "E", "C", "KH", "AD", "fKk", "-K", "K") do
      assert(FenParser.parseChess960(position(field)).isLeft, field)
    assert(FenParser.parseChess960("8/8/8/8/8/8/4K3/R6R w K -").isLeft)
    assert(FenParser.parse(position("D")).isLeft)
    assertEquals(FenParser.parseChess960(position("-")).toOption.get.serialize, position("-"))

  test("Scharnagl IDs generate distinct legal starts and preserve the standard numbering"):
    val starts = (0 until 960).map(number => Chess960Dfen.startingPosition(number).toOption.get)
    assertEquals(starts.distinct.size, 960)
    for fen <- starts do
      val rank = fen.take(8)
      assertEquals(rank.sorted, "bbknnqrr")
      assert(rank.indexOf('r') < rank.indexOf('k') && rank.indexOf('k') < rank.lastIndexOf('r'))
      assert(rank.indexOf('b') % 2 != rank.lastIndexOf('b') % 2)
      assertEquals(FenParser.parseChess960(fen).toOption.get.serialize, fen)
    assertEquals(starts(0).take(8), "bbqnnrkr")
    assertEquals(starts(518), FenParser.InitialPosition)
    assertEquals(starts(959).take(8), "rkrnnqbb")
    assert(Chess960Dfen.startingPosition(-1).isLeft)
    assert(Chess960Dfen.startingPosition(960).isLeft)
