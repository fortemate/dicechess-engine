// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.util.Random

/** [[PlayableDice.of]] against an independent count, over the positions of seeded random games (#293).
  *
  * The positions are the ones turns actually meet: every roll of a few games played from the start, each turn a legal
  * turn picked at random. The count replays every legal turn move by move, reads the pool before and after each move,
  * and takes, for each face, the most dice that one turn continuing the prefix spends. It shares nothing with
  * [[PlayableDice]] but the engine's `makeMove` and `diceAfter`.
  */
class PlayableDiceGamesSuite extends FunSuite:

  private def parse(dfen: String): GameState =
    FenParser.parse(dfen).fold(err => fail(s"Failed to parse DFEN: $err"), identity)

  /** `start` after the moves of `turn`, each keeping the dice it leaves. */
  private def played(start: GameState, turn: List[Move]): GameState =
    turn.foldLeft(start)((s, m) => s.makeMove(m).withDiceSlotsOf(s.diceAfter(m)))

  /** Every rolled position of 8 seeded random games of at most 40 turns each, with its legal turns. */
  private lazy val rolls: List[(GameState, List[List[Move]])] =
    val rng   = new Random(293)
    val rolls = List.newBuilder[(GameState, List[List[Move]])]
    for _ <- 0 until 8 do
      var state = parse(FenParser.InitialPosition)
      var turn  = 0
      var over  = false
      while turn < 40 && !over do
        val rolled = state.withDicePool(List.fill(3)(rng.nextInt(6) + 1))
        val turns  = TurnGenerator.generateAllLegalTurnPaths(rolled)
        rolls += rolled -> turns
        val after = if turns.isEmpty then rolled else played(rolled, turns(rng.nextInt(turns.size)))
        over =
          (after.kings.value & after.whitePieces.value) == 0L || (after.kings.value & after.blackPieces.value) == 0L
        state = after.endTurn()
        turn += 1
    rolls.result()

  /** The die faces each move of `turn` spends, read off the pool before and after it. */
  private def spentByMove(start: GameState, turn: List[Move]): List[List[Int]] =
    turn
      .foldLeft((start, List.empty[List[Int]])) { case ((s, spent), m) =>
        val left = s.diceAfter(m)
        (s.makeMove(m).withDiceSlotsOf(left), spent :+ s.dicePool.diff(left.dicePool))
      }
      ._2

  /** At most `n` elements of `xs`, spread evenly across it, so that a large turn tree costs a bounded number of calls.
    */
  private def spread[A](xs: List[A], n: Int): List[A] =
    if xs.size <= n then xs else (0 until n).map(i => xs(i * xs.size / n)).toList

  test(
    "the games reach positions of every kind: a roll with no legal move, and turns that spend fewer dice than rolled"
  ) {
    val turns = rolls.map(_._2)
    assert(rolls.size > 200, s"only ${rolls.size} rolls")
    assert(turns.exists(_.isEmpty), "no roll without a legal move")
    assert(turns.exists(ts => ts.nonEmpty && ts.forall(_.size < 3)), "no roll whose turns all leave a die unspent")
    assert(turns.exists(_.size > 2000), "no roll with more than 2,000 legal turns")
  }

  test("at the roll and after prefixes of legal turns, the dice are the most of each face a continuing turn spends") {
    // The count replays every legal turn, so the largest trees are left to the other checks: the monotonicity test
    // below and the tree walk in JsApiSpec.
    for (start, legal) <- rolls if legal.size <= 2000 do
      val turns    = legal.map(turn => turn -> spentByMove(start, turn))
      val prefixes = Nil :: List(1, 2).flatMap { n =>
        spread(turns.collect { case (turn, _) if turn.size >= n => turn.take(n) }.distinctBy(_.map(_.toInt)), 4)
      }
      for prefix <- prefixes do
        val continuing = turns.collect {
          case (turn, spent) if turn.take(prefix.size).map(_.toInt) == prefix.map(_.toInt) =>
            spent.drop(prefix.size).flatten
        }
        val expected = (1 to 6).toList.flatMap { face =>
          List.fill(continuing.map(_.count(_ == face)).maxOption.getOrElse(0))(face)
        }
        assertEquals(
          PlayableDice.of(start, prefix),
          Some(expected),
          s"${FenParser.serialize(start)} ${prefix.map(_.toUci)}"
        )
  }

  test("a die that is not playable never becomes playable later in the turn") {
    for
      (start, legal) <- rolls
      turn           <- spread(legal, if legal.size <= 2000 then 4 else 1)
    do
      val playable = (0 to turn.size).map { n =>
        PlayableDice.of(start, turn.take(n)).getOrElse(fail(s"${turn.take(n).map(_.toUci)} begins no legal turn"))
      }
      val spent = spentByMove(start, turn)
      (0 until turn.size).foreach { n =>
        // What was playable before an action, less the dice the action spent, bounds what is playable after it.
        val bound = playable(n).diff(spent(n))
        assert(playable(n + 1).diff(bound).isEmpty, s"after ${turn.take(n + 1).map(_.toUci)}: ${playable(n + 1)}")
      }
  }
