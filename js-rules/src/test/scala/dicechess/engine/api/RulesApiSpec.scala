// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.api

import scala.scalajs.js
import munit.FunSuite

/** Contract of the rules-only export root behind the npm subpath `./rules` (#222).
  *
  * Every function must answer exactly what its `DiceChess` counterpart answers: the subpath is a smaller door onto the
  * same rules, never a second behaviour. The tests therefore assert the value AND the agreement with
  * [[dicechess.engine.api.JsApi]], so a future change to one root cannot drift from the other unnoticed.
  *
  * What no Scala test can assert is the other half of the contract — that the `rules` module does not reach
  * `dicechess.engine.search`. That is a property of the linked bundle, checked over the emitted modules by
  * `.mise/lib/check-npm-package-entries.mjs` when `package:prepare` assembles `dist/`.
  */
class RulesApiSpec extends FunSuite:

  private val initialDfen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val promoDfen   = "k7/4P3/8/8/8/8/8/4K3 w - - 0 1 P"

  test("getLegalUciMoves: maximal legal moves for the dice pool, and agrees with DiceChess") {
    val moves = RulesApi.getLegalUciMoves(promoDfen).toList
    assertEquals(moves.sorted, List("e7e8b", "e7e8n", "e7e8q", "e7e8r"))
    assertEquals(moves, JsApi.getLegalUciMoves(promoDfen).toList)
  }

  test("getLegalUciMoves: empty array for a null, malformed or dice-less position") {
    assertEquals(RulesApi.getLegalUciMoves(null.asInstanceOf[String]).length, 0) // scalafix:ok(DisableSyntax.null)
    assertEquals(RulesApi.getLegalUciMoves("invalid-fen").length, 0)
    assertEquals(RulesApi.getLegalUciMoves("k7/4P3/8/8/8/8/8/4K3 w - - 0 1").length, 0)
  }

  test("generateMoves: pseudo-legal moves, and agrees with DiceChess") {
    val moves = RulesApi.generateMoves(initialDfen).toList
    assertEquals(moves.length, 20)
    assert(moves.contains("e2e4"))
    assertEquals(moves, JsApi.generateMoves(initialDfen).toList)
    assertEquals(RulesApi.generateMoves(promoDfen).toList.length, 4)
    assertEquals(RulesApi.generateMoves("invalid-fen").length, 0)
  }

  test("applyMove: applies the move and agrees with DiceChess, including the rejections") {
    val expected = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e3 0 1"
    assertEquals(RulesApi.applyMove(initialDfen, "e2", "e4", js.undefined).toOption, Some(expected))
    assertEquals(
      RulesApi.applyMove(initialDfen, "e2", "e4", js.undefined).toOption,
      JsApi.applyMove(initialDfen, "e2", "e4", js.undefined).toOption
    )
    assertEquals(RulesApi.applyMove(promoDfen, "e7", "e8", "q").toOption, Some("k3Q3/8/8/8/8/8/8/4K3 w - - 0 1"))
    assertEquals(RulesApi.applyMove(initialDfen, "e2", "e5", js.undefined).toOption, None)
    assertEquals(RulesApi.applyMove("invalid-fen", "e2", "e4", js.undefined).toOption, None)
    assertEquals(
      RulesApi.applyMove(null.asInstanceOf[String], "e2", "e4", js.undefined).toOption,
      None
    ) // scalafix:ok(DisableSyntax.null)
  }

  test(
    "applyMove: keeps the unspent dice, castling spends two, a move no die allows is refused, as in DiceChess (#279)"
  ) {
    val pawnPush = s"$initialDfen PPN"
    assertEquals(
      RulesApi.applyMove(pawnPush, "e2", "e4", js.undefined).toOption,
      Some("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e3 0 1 PN")
    )
    val castling = "4k3/8/8/8/8/8/4P3/4K2R w K - 0 1 PRK"
    assertEquals(
      RulesApi.applyMove(castling, "e1", "g1", js.undefined).toOption,
      Some("4k3/8/8/8/8/8/4P3/5RK1 w - - 1 1 P")
    )
    assertEquals(RulesApi.applyMove(s"$initialDfen NNN", "e2", "e4", js.undefined).toOption, None)
    for (dfen, from, to) <- List((pawnPush, "e2", "e4"), (castling, "e1", "g1"), (s"$initialDfen NNN", "e2", "e4")) do
      assertEquals(
        RulesApi.applyMove(dfen, from, to, js.undefined).toOption,
        JsApi.applyMove(dfen, from, to, js.undefined).toOption
      )
  }

  test("endTurn: clears the dice pool and stale en-passant, and agrees with DiceChess") {
    val before   = "rnbqkbnr/p1pppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6e3 0 1 PN"
    val expected = "rnbqkbnr/p1pppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    assertEquals(RulesApi.endTurn(before).toOption, Some(expected))
    assertEquals(RulesApi.endTurn(before).toOption, JsApi.endTurn(before).toOption)
    assertEquals(RulesApi.endTurn("invalid-fen").toOption, None)
    assertEquals(RulesApi.endTurn(null.asInstanceOf[String]).toOption, None) // scalafix:ok(DisableSyntax.null)
  }

  test("perft: leaf counts, and agrees with DiceChess") {
    assertEquals(RulesApi.perft(initialDfen, 0), 1.0)
    assertEquals(RulesApi.perft(initialDfen, 1), 20.0)
    assertEquals(RulesApi.perft(initialDfen, 2), 400.0)
    assertEquals(RulesApi.perft(promoDfen, 1), JsApi.perft(promoDfen, 1))
    assertEquals(RulesApi.perft("invalid-fen", 1), 0.0)
    assertEquals(RulesApi.perft(initialDfen, -1), 0.0)
  }

  test("getPieceFromDice: the dice-to-piece mapping, and agrees with DiceChess") {
    assertEquals((1 to 6).toList.map(RulesApi.getPieceFromDice), List("p", "n", "b", "r", "q", "k"))
    assertEquals(RulesApi.getPieceFromDice(3), JsApi.getPieceFromDice(3))
    assertEquals(RulesApi.getPieceFromDice(0), null) // scalafix:ok(DisableSyntax.null)
    assertEquals(RulesApi.getPieceFromDice(7), null) // scalafix:ok(DisableSyntax.null)
  }

  test("canonicalKey: idempotent canonical DFEN, and agrees with DiceChess") {
    val key = RulesApi.canonicalKey(initialDfen).toOption.getOrElse(fail("expected a canonical key"))
    assert(key.nonEmpty)
    assertEquals(RulesApi.canonicalKey(key).toOption, Some(key))
    assertEquals(RulesApi.canonicalKey(initialDfen).toOption, JsApi.canonicalKey(initialDfen).toOption)
    assertEquals(RulesApi.canonicalKey("not-a-fen").toOption, None)
  }
