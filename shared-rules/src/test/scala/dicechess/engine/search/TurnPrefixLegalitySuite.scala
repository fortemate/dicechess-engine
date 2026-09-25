// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.{LegalMovesFilter, MoveGenerator}
import munit.FunSuite

/** Step-by-step legality against full-turn legality (#279).
  *
  * [[dicechess.engine.movegen.LegalMovesFilter]] judges one position: which first micro-moves start a legal turn from
  * here. A client that follows a turn by asking it again after every micro-move re-roots the Maximum Micro-moves Rule
  * at each step and forgets how many dice the whole turn could have used. In the position below `c2c4` is a legal first
  * action only because `Nb3xc5` takes the king next; asked again after `c2c4`, the filter admits every knight move,
  * although a quiet one ends a two-dice turn while `c2c3, c3c4, Nb3xc5` uses all three. [[TurnGenerator]] measures
  * maximality over the whole turn and keeps only the capture.
  */
class TurnPrefixLegalitySuite extends FunSuite:

  /** White: Ka1, Nb3, Pc2. Black: Kc5. Dice: knight, pawn, pawn. */
  private val start: GameState =
    FenParser.parse("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP").fold(err => fail(s"Failed to parse FEN: $err"), identity)

  private val turns: List[List[String]] = TurnGenerator.generateAllLegalTurnPaths(start).map(_.map(_.toUci))

  private val quietKnightMoves = Set("b3c1", "b3d2", "b3d4", "b3a5")

  test("the only legal turn that starts with c2c4 is c2c4 then Nb3xc5") {
    assertEquals(turns.filter(_.headOption.contains("c2c4")), List(List("c2c4", "b3c5")))
  }

  test("no legal turn follows c2c4 with a quiet knight move") {
    val offenders = turns.filter(t => t.headOption.contains("c2c4") && t.lift(1).exists(quietKnightMoves.contains))
    assertEquals(offenders, Nil)
  }

  test("every legal turn either takes the king or spends all three dice") {
    val (captures, quiet) = turns.partition(_.lastOption.contains("b3c5"))
    assert(captures.contains(List("c2c3", "c3c4", "b3c5")), "the three-dice capture must be a legal turn")
    assert(quiet.nonEmpty, "a three-dice turn that does not take the king exists")
    assert(quiet.forall(_.size == 3), s"a turn that does not take the king must spend all three dice: $quiet")
  }

  test("the legal turns start with exactly the moves LegalMovesFilter admits") {
    val firstActions = turns.flatMap(_.headOption).distinct.sorted
    assertEquals(firstActions, LegalMovesFilter.filterMaximalMoves(start).map(_.toUci).sorted)
    assert(firstActions.contains("c2c4"))
  }

  test("asked again after c2c4, LegalMovesFilter admits every knight move") {
    // This is what a client sees when it calls getLegalUciMoves on the DFEN after c2c4 with the knight and pawn dice
    // left: the question is re-rooted at the new position, so the two-dice ceiling there looks maximal.
    val c2c4  = MoveGenerator.generateMoves(start).find(_.toUci == "c2c4").getOrElse(fail("c2c4 must be generated"))
    val after = start.makeMove(c2c4).withDiceSlotsOf(start.diceAfter(c2c4))
    assertEquals(FenParser.serialize(after), "8/8/8/2k5/2P5/1N6/8/K7 w - c3 0 1 PN")
    assertEquals(
      LegalMovesFilter.filterMaximalMoves(after).map(_.toUci).sorted,
      (quietKnightMoves + "b3c5").toList.sorted
    )
  }
