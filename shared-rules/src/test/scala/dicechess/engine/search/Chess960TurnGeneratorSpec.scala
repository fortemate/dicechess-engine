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

  test("applying a castle and a double pawn push preserves the new en-passant square in either order"):
    val dfen = "r3k2r/8/8/8/8/8/P7/5KR1 w Kkq - 7 12 PRK"
    assertEquals(
      Chess960TurnGenerator.applyTurn(dfen, List("f1g1", "a2a4")),
      Right("r3k2r/8/8/8/P7/8/8/5RK1 b kq a3 0 12")
    )
    assertEquals(
      Chess960TurnGenerator.applyTurn(dfen, List("a2a4", "f1g1")),
      Right("r3k2r/8/8/8/P7/8/8/5RK1 b kq a3 1 12")
    )

  test("an inner castling rook keeps its right when the outer rook leaves or joins its back rank"):
    assertEquals(
      Chess960TurnGenerator.applyTurn("4k3/8/8/8/8/8/8/R3KR1R w FQ - 0 1 R", List("h1h2")),
      Right("4k3/8/8/8/8/8/7R/R3KR2 b KQ - 1 1")
    )
    assertEquals(
      Chess960TurnGenerator.applyTurn("4k3/8/8/8/8/8/7R/R3KR2 w KQ - 0 1 R", List("h2h1")),
      Right("4k3/8/8/8/8/8/8/R3KR1R b FQ - 1 1")
    )

  test("moving a castling rook never transfers its right to another rook"):
    assertEquals(
      Chess960TurnGenerator.applyTurn("4k3/8/8/8/8/8/8/R3KR1R w FQ - 0 1 R", List("f1f2")),
      Right("4k3/8/8/8/8/8/5R2/R3K2R b Q - 1 1")
    )

  test("a black promotion capture removes only the captured rook's right"):
    assertEquals(
      Chess960TurnGenerator.applyTurn("4k2r/8/8/8/8/8/4p3/R3KR1R b FQk - 7 12 p", List("e2f1r")),
      Right("4k2r/8/8/8/8/8/8/R3Kr1R w Qk - 0 13")
    )

  test("a rolled pass expires the incoming side's en-passant target and advances Black's move number"):
    val dfen = "4k3/8/8/3p4/P7/8/8/4K2R b K a3d6 7 12"
    assertEquals(
      Chess960TurnGenerator.applyTurn(dfen + " nnn", Nil),
      Right("4k3/8/8/3p4/P7/8/8/4K2R w K d6 7 13")
    )
    assert(Chess960TurnGenerator.applyTurn(dfen, Nil).isLeft)
    assert(Chess960TurnGenerator.applyTurn(dfen + " k", Nil).isLeft)
    val lastNumber = s"4k3/8/8/8/8/8/8/4K3 b - - 0 ${Int.MaxValue} n"
    assert(Chess960TurnGenerator.applyTurn(lastNumber, Nil).isLeft)

  test("turn application rejects malformed moves, incomplete paths, and moves beyond the roll"):
    val dfen = "k7/8/8/8/8/8/P7/5KR1 w K - 0 1 PRK"
    assert(Chess960TurnGenerator.applyTurn(dfen, List("invalid")).isLeft)
    assert(Chess960TurnGenerator.applyTurn(dfen, List("f1g1")).isLeft)
    assert(Chess960TurnGenerator.applyTurn(dfen, List("f1g1", "a2a4", "a4a5")).isLeft)

  test("a king capture clears the dice and opposing rights while keeping the capturing side active"):
    val dfen = "r5kr/8/8/8/8/8/P7/R4KR1 w KQkq - 7 12 PRK"
    assertEquals(
      Chess960TurnGenerator.applyTurn(dfen, List("g1g8")),
      Right("r5Rr/8/8/8/8/8/P7/R4K2 w Q - 0 12")
    )
    assertEquals(paths("r5Rr/8/8/8/8/8/P7/R4K2 w Q - 0 12 P"), Nil)
    assert(Chess960TurnGenerator.applyTurn(dfen, List("g1g8", "a2a4")).isLeft)
    assert(Chess960TurnGenerator.applyTurn("r5Rr/8/8/8/8/8/P7/R4K2 w Q - 0 12 P", List("a2a4")).isLeft)

  test("en-passant capture removes the pawn before or after castling"):
    val dfen = "k7/8/8/3pP3/8/8/8/5KR1 w K d6 7 12 PRK"
    assertEquals(
      Chess960TurnGenerator.applyTurn(dfen, List("e5d6", "f1g1")),
      Right("k7/8/3P4/8/8/8/8/5RK1 b - - 1 12")
    )
    assertEquals(
      Chess960TurnGenerator.applyTurn(dfen, List("f1g1", "e5d6")),
      Right("k7/8/3P4/8/8/8/8/5RK1 b - - 0 12")
    )

  test("playable dice derive from all legal continuations, including castles and early wins"):
    val snapshot                  = Chess960Dfen.parse("6k1/8/8/8/8/8/P7/5KR1 w K - 0 1 KRP").toOption.get
    def playable(played: String*) = Chess960TurnGenerator.playableDice(snapshot, played.toList)
    assertEquals(playable(), Some(List(1, 4, 6)))
    assertEquals(playable("f1g1"), Some(List(1)))
    assertEquals(playable("a2a4"), Some(List(4, 6)))
    assertEquals(playable("g1g8"), Some(Nil))
    assertEquals(playable("g1g8", "a2a4"), None)
    assertEquals(playable("f1g1", "a2a4"), Some(Nil))

  test("playable dice preserve repeated faces and distinguish passes from invalid prefixes"):
    val rolled = Chess960Dfen.parse("4k3/8/8/8/8/8/P7/4K3 w - - 0 1 PPP").toOption.get
    val passed = Chess960Dfen.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1 PPP").toOption.get
    assertEquals(Chess960TurnGenerator.playableDice(rolled, Nil), Some(List(1, 1, 1)))
    assertEquals(Chess960TurnGenerator.playableDice(rolled, List("a2a4")), Some(List(1, 1)))
    assertEquals(Chess960TurnGenerator.playableDice(passed, Nil), Some(Nil))
    assertEquals(Chess960TurnGenerator.playableDice(passed, List("a2a4")), None)
