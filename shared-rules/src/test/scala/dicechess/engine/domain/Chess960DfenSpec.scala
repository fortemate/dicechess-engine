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

  test("overlapping, stationary and ordinary castles reach the same final squares and spend two dice"):
    for (rank, rook) <- List("4K2R" -> "h1", "6KR" -> "h1", "4KR2" -> "f1", "5KR1" -> "g1", "3K1R2" -> "f1") do
      val before   = s"r3k2r/8/8/8/8/8/8/$rank w Kkq e6 5 13 KPR"
      val expected = "r3k2r/8/8/8/8/8/8/5RK1 w kq e6 6 13 P"
      assertEquals(Chess960Dfen.applyCastle(before, rook), Right(expected), before)

  test("black can swap on the queenside while preserving the opposing inner rook right"):
    val before = "2rk4/8/8/8/8/8/8/4KR1R b Fq - 127 8 krr"
    assertEquals(Chess960Dfen.applyCastle(before, "c8"), Right("2kr4/8/8/8/8/8/8/4KR1R b F - 127 8 r"))

  test("only designated rooks with both dice and clear paths are available"):
    val base                  = "4k3/8/8/8/8/8/8/4KR1R w F - 0 1 RK"
    def targets(dfen: String) = Chess960Dfen.parse(dfen).toOption.get.availableCastlingRooks.map(_.toNotation)
    assertEquals(targets(base), List("f1"))
    assert(Chess960Dfen.applyCastle(base, "h1").isLeft)
    for roll <- List("R", "K", "PPP") do assertEquals(targets(base.replace(" RK", s" $roll")), Nil)
    assertEquals(targets(base.stripSuffix(" RK")), Nil)
    assertEquals(targets("4k3/8/8/8/8/8/8/1K1B3R w K - 0 1 KR"), Nil)
    assertEquals(targets("4k3/8/8/8/8/8/8/1R1BK3 w Q - 0 1 KR"), Nil)
    assertEquals(targets("4k3/8/8/8/8/8/8/1RN1K3 w Q - 0 1 KR"), Nil)
    assertEquals(targets("4k3/8/8/8/8/8/8/4Kn1R w K - 0 1 KR"), Nil)
    assertEquals(targets("4kr2/8/8/8/8/8/8/4K2R w K - 0 1 KR"), List("h1"))

  test("micro-moves preserve variant rights and let an editor move without a roll"):
    val dfen = "4k3/8/8/8/8/8/8/R3KR1R w FQ - 0 1"
    assertEquals(Chess960Dfen.applyMove(dfen, "h1", "h2", None), Right("4k3/8/8/8/8/8/7R/R3KR2 w KQ - 1 1"))
    assertEquals(Chess960Dfen.applyMove(dfen, "e1", "f1", None), Right("4k3/8/8/8/8/8/8/R4RKR w - - 1 1"))
    assertEquals(
      Chess960Dfen.applyMove(dfen + " RNK", "e1", "f1", None),
      Right("4k3/8/8/8/8/8/8/R4RKR w - - 1 1 N")
    )
    assert(Chess960Dfen.applyMove(dfen + " N", "h1", "h2", None).isLeft)
    assert(Chess960Dfen.applyMove(dfen + " K", "e1", "f1", None).isLeft)

  test("micro-moves can replay a legal castle and en-passant capture before ending the turn"):
    val dfen = "r3k3/8/8/3pP3/8/8/8/5KR1 w Kq d6 7 12 PRK"
    val next = for
      castle <- Chess960Dfen.applyMove(dfen, "f1", "g1", None)
      ep     <- Chess960Dfen.applyMove(castle, "e5", "d6", None)
      result <- Chess960Dfen.endTurn(ep)
    yield result
    assertEquals(next, Right("r3k3/8/3P4/8/8/8/8/5RK1 b q - 0 12"))

  test("explicit end turn flips even on an editor board with a missing king and guards overflow"):
    assertEquals(
      Chess960Dfen.endTurn("8/8/8/8/8/8/8/4K3 w - - 0 1 P"),
      Right("8/8/8/8/8/8/8/4K3 b - - 0 1")
    )
    assert(Chess960Dfen.endTurn(s"4k3/8/8/8/8/8/8/4K3 b - - 0 ${Int.MaxValue}").isLeft)

  test("a micro-move promotion capture removes only the captured rook's right"):
    val dfen = "4k2r/8/8/8/8/8/4p3/R3KR1R b FQk - 7 12 pn"
    assertEquals(
      Chess960Dfen.applyMove(dfen, "e2", "f1", Some("r")),
      Right("4k2r/8/8/8/8/8/8/R3Kr1R b Qk - 0 12 n")
    )
    assert(Chess960Dfen.applyMove(dfen, "e2", "f1", Some("k")).isLeft)
    assert(Chess960Dfen.applyMove(dfen, "E2", "f1", Some("r")).isLeft)
