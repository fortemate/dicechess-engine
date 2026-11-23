// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Whole-turn legality for Chess960 (#19). */
class Chess960TurnGeneratorSpec extends FunSuite:
  private def paths(dfen: String): List[List[String]] =
    Chess960TurnGenerator.legalPaths(Chess960Dfen.parse(dfen).toOption.get)

  test("both orders of a castle and a pawn move spend three dice, while a king capture ends immediately"):
    val result = paths("6k1/8/8/8/8/8/P7/5KR1 w K - 0 1 KRP")
    assert(result.contains(List("f1g1", "a2a4")))
    assert(result.contains(List("a2a4", "f1g1")))
    assert(!result.contains(List("f1g1")))
    assert(result.contains(List("g1g8")))
    assert(!result.exists(p => p.head == "g1g8" && p.length > 1))
    assertEquals(result.distinct, result)

  test("a knight can clear the king's path during the roll"):
    val result = paths("k7/8/8/8/8/8/8/4KN1R w K - 0 1 NKR")
    assert(result.contains(List("f1d2", "e1h1")))
    assert(!result.exists(_.head == "e1h1"))

  test("moving a rook forfeits only its own right; moving the king forfeits both"):
    val dfen   = "4k3/8/8/8/8/8/8/R3KR1R w FQ - 0 1 "
    val result = paths(dfen + "RRK")
    assert(result.contains(List("h1h2", "e1f1")))
    assert(result.contains(List("f1f2", "e1a1")))
    assert(!result.contains(List("f1f2", "e1h1")))
    assert(!paths(dfen + "KKR").contains(List("e1d1", "d1a1")))

  test("an en-passant capture can precede or follow castling"):
    val result = paths("k7/8/8/3pP3/8/8/8/5KR1 w K d6 0 1 PRK")
    assert(result.contains(List("e5d6", "f1g1")))
    assert(result.contains(List("f1g1", "e5d6")))

  test("classical castling layouts agree with the classical generator for each distinct three-die roll"):
    val faces = "PNBRQK"
    for
      i <- faces.indices
      j <- i until faces.length
      k <- j until faces.length
    do
      val roll     = s"${faces(i)}${faces(j)}${faces(k)}"
      val dfen     = s"r3k2r/8/8/8/8/8/P7/R3K2R w KQkq - 0 1 $roll"
      val classic  = FenParser.parse(dfen).toOption.get
      val expected = TurnGenerator
        .generateAllLegalTurnPaths(classic)
        .map(_.map: move =>
          move.flags match
            case Move.KingCastle  => "e1h1"
            case Move.QueenCastle => "e1a1"
            case _                => move.toUci)
      assertEquals(paths(dfen).toSet, expected.toSet, roll)

  test("an unrolled position and an unusable roll have no paths"):
    val board = "4k3/8/8/8/8/8/8/4K2R w K - 0 1"
    assertEquals(paths(board), Nil)
    assertEquals(paths(board + " PPP"), Nil)
