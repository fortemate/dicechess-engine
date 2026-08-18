package dicechess.engine.jvmapi

import munit.FunSuite

import java.util as ju
import scala.jdk.CollectionConverters.*

/** Semantics of the facade's autonomous-loop surface (#616), on the positions a self-play game does not reliably reach:
  * a roll with no legal turn, a board without kings, and the perspective symmetry of the evaluator.
  *
  * The Java-callability half lives in [[JvmApiSelfPlayCheck]] — a Scala suite cannot see a signature that stopped being
  * reachable from Java, and a Java suite is a clumsy place to pin edge-case semantics. The two are complementary, not
  * duplicates.
  */
class JvmApiSpec extends FunSuite:

  private val StartDfen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 -"

  private def dice(values: Int*): ju.List[Integer] = values.map(Integer.valueOf).asJava

  private def pool(state: dicechess.engine.domain.GameState): List[Int] =
    JvmApi.dicePool(state).asScala.toList.map(_.intValue)

  test("a roll that moves nothing yields no turn rather than an error") {
    // A lone king cannot use pawn dice, so the side to move must pass — which is not a loss in Dice Chess.
    val state = JvmApi.withDice(JvmApi.parseDfen("4k3/8/8/8/8/8/8/4K3 w - - 0 1 -"), dice(1, 1, 1))

    assert(JvmApi.legalTurns(state).isEmpty)
    assert(JvmApi.bestTurn(state, "greedy").isEmpty)
    assert(!JvmApi.isGameOver(state), "a forced pass is not a finished game")
    assertEquals(JvmApi.activeColor(JvmApi.endTurn(state)), dicechess.engine.domain.Color.Black)
  }

  test("a board without kings resolves to no winner rather than to either side") {
    val state = JvmApi.parseDfen("8/8/8/3q4/8/8/8/8 w - - 0 1")

    assert(JvmApi.isGameOver(state))
    assertEquals(JvmApi.winner(state), JvmApi.NoWinner)
  }

  test("the winner is the side whose opponent lost its king") {
    val whiteWon = JvmApi.parseDfen("8/8/8/8/8/8/8/4K3 b - - 0 1")
    val blackWon = JvmApi.parseDfen("4k3/8/8/8/8/8/8/8 w - - 0 1")

    assert(JvmApi.isGameOver(whiteWon) && JvmApi.isGameOver(blackWon))
    assertEquals(JvmApi.winner(whiteWon), 0)
    assertEquals(JvmApi.winner(blackWon), 1)
    assertEquals(JvmApi.winner(JvmApi.parseDfen(StartDfen)), JvmApi.NoWinner)
  }

  test("evaluate reads the same position from either side") {
    val state = JvmApi.parseDfen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPP1/RNBQKBNR w KQkq - 0 1 -")

    assertEquals(JvmApi.evaluate(state, 0), -100, "White is a pawn down")
    assertEquals(JvmApi.evaluate(state, 1), 100, "and Black is the same pawn up")
    intercept[IllegalArgumentException](JvmApi.evaluate(state, 2))
  }

  test("withDice validates the pool instead of packing it silently") {
    val state = JvmApi.parseDfen(StartDfen)

    assertEquals(pool(JvmApi.withDice(state, dice(6, 2, 4))), List(6, 2, 4))
    assertEquals(pool(JvmApi.withDice(state, dice())), Nil)
    intercept[IllegalArgumentException](JvmApi.withDice(state, dice(1, 2, 3, 4)))
    intercept[IllegalArgumentException](JvmApi.withDice(state, dice(7)))
    intercept[IllegalArgumentException](JvmApi.withDice(state, dice(0)))
  }

  test("bestTurn resolves ids through the registry, case-insensitively") {
    val state = JvmApi.withDice(JvmApi.parseDfen(StartDfen), dice(1, 1, 1))

    val ids = JvmApi.algorithms().asScala.toList
    assert(ids.contains("greedy"), s"expected the built-in bots to be registered, got $ids")

    val chosen = JvmApi.bestTurn(state, "GREEDY")
    assert(chosen.isPresent)
    assertEquals(chosen.get.uci.size, 3, "three pawn dice buy three micro-moves")
    assertEquals(
      JvmApi.activeColor(chosen.get.finalState),
      JvmApi.activeColor(state),
      "finalState is pre-endTurn, like Turn.finalState"
    )

    intercept[IllegalArgumentException](JvmApi.bestTurn(state, "no-such-bot"))
  }

  test("the seeded self-play game is reproducible") {
    // Both sides choose through legalTurns from one seeded source, so the same seed must land on the same
    // final position. It would not if the game were driven by a built-in bot: those break ties with an
    // unseeded Random, which is exactly why bestTurn is checked against the enumeration instead of played.
    assertEquals(JvmApiSelfPlayCheck.playSeededGame(), JvmApiSelfPlayCheck.playSeededGame())
  }

  test("a time budget binds the bots that spend time and is ignored by the ones that do not") {
    val midgame = JvmApi.parseDfen("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 1")
    val rolled  = JvmApi.withDice(midgame, dice(5, 4, 2))

    // monte-carlo is a TimeBudgetedSearch: without a deadline it scores every legal turn of the roll, which
    // on this position runs for minutes. Under a budget it holds to the anytime contract instead.
    val started   = System.nanoTime()
    val budgeted  = JvmApi.bestTurn(rolled, "monte-carlo", 200L)
    val elapsedMs = (System.nanoTime() - started) / 1000000L
    assert(budgeted.isPresent)
    assert(elapsedMs < 30000L, s"a 200ms budget took ${elapsedMs}ms — the search is unbounded again")

    // A fixed-cost bot ignores the budget rather than being cut short by it.
    assertEquals(
      JvmApi.bestTurn(rolled, "greedy", 1L).isPresent,
      JvmApi.bestTurn(rolled, "greedy").isPresent
    )

    intercept[IllegalArgumentException](JvmApi.bestTurn(rolled, "greedy", 0L))
    intercept[IllegalArgumentException](JvmApi.bestTurn(rolled, "greedy", -5L))
  }

  test("halfMoveClock and toDfen report what the DFEN carried") {
    val state = JvmApi.parseDfen("4k3/8/8/8/8/8/8/4K3 w - - 17 42 -")

    assertEquals(JvmApi.halfMoveClock(state), 17)
    assertEquals(JvmApi.toDfen(state), "4k3/8/8/8/8/8/8/4K3 w - - 17 42")
  }
