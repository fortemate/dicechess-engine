package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class DrawOfferLogicSuite extends FunSuite:

  // A minimal SearchAlgorithm that mixes in DrawOfferLogic for isolated unit testing
  object TestBot extends SearchAlgorithm with DrawOfferLogic:
    override def findBestMove(state: GameState): Option[ScoredSequence] = None

  private def parseState(fen: String): GameState =
    FenParser.parse(fen).getOrElse(fail(s"Failed to parse FEN: $fen"))

  // ──────────────────────────────────────────────
  // shouldOfferDraw — true  (dead-draw positions)
  // ──────────────────────────────────────────────

  test("offers draw for K vs K") {
    val state = parseState("k7/8/8/8/8/8/8/K7 w - - 0 1")
    assert(TestBot.shouldOfferDraw(state))
  }

  test("offers draw for K+B vs K when the active king is safe from the enemy bishop") {
    // Black B on b8 (dark), Black K on a8 (light), White K on h1 (light).
    // White king (light) vs Black bishop (dark): different colours → safe.
    val state = parseState("kb6/8/8/8/8/8/8/7K w - - 0 1")
    assert(TestBot.shouldOfferDraw(state))
  }

  test("offers draw for K+B vs K+B when both kings are safe") {
    // White B g1 (dark), White K h1 (light); Black B b8 (dark), Black K a8 (light).
    // Every bishop attacks only the opposite colour → neither king is threatened.
    val state = parseState("kb6/8/8/8/8/8/8/6BK w - - 0 1")
    assert(TestBot.shouldOfferDraw(state))
  }

  test("offers draw symmetrically regardless of active colour (Black to move)") {
    // Same position as above, active colour flipped.
    val state = parseState("kb6/8/8/8/8/8/8/6BK b - - 0 1")
    assert(TestBot.shouldOfferDraw(state))
  }

  // ──────────────────────────────────────────────
  // shouldOfferDraw — false (not a dead draw)
  // ──────────────────────────────────────────────

  test("does not offer draw when kings are adjacent") {
    val state = parseState("k7/1K6/8/8/8/8/8/8 w - - 0 1")
    assert(!TestBot.shouldOfferDraw(state))
  }

  test("does not offer draw when a pawn is present") {
    val state = parseState("k7/8/8/8/8/8/1P6/K7 w - - 0 1")
    assert(!TestBot.shouldOfferDraw(state))
  }

  test("does not offer draw when a knight is present") {
    val state = parseState("k7/8/8/8/8/8/1N6/K7 w - - 0 1")
    assert(!TestBot.shouldOfferDraw(state))
  }

  test("does not offer draw when a rook is present") {
    val state = parseState("k7/8/8/8/8/8/1R6/K7 w - - 0 1")
    assert(!TestBot.shouldOfferDraw(state))
  }

  test("does not offer draw when a queen is present") {
    val state = parseState("k7/8/8/8/8/8/1Q6/K7 w - - 0 1")
    assert(!TestBot.shouldOfferDraw(state))
  }

  test("does not offer draw when one side has two bishops") {
    val state = parseState("k7/8/8/8/8/8/8/BBK5 w - - 0 1")
    assert(!TestBot.shouldOfferDraw(state))
  }

  test("does not offer draw when the active king is on the same colour as the enemy bishop") {
    // Black B c1 (dark), White K a1 (dark) → bishop threatens king → not a dead draw.
    val state = parseState("k7/8/8/8/8/8/8/K1b5 w - - 0 1")
    assert(!TestBot.shouldOfferDraw(state))
  }

  test("does not offer draw when the opponent king is on the same colour as our bishop") {
    // White B a1 (dark), Black K h8 (dark) → our bishop threatens opponent → not a dead draw.
    val state = parseState("7k/8/8/8/8/8/8/B6K w - - 0 1")
    assert(!TestBot.shouldOfferDraw(state))
  }

  // ──────────────────────────────────────────────
  // shouldAcceptDraw — true
  // ──────────────────────────────────────────────

  test("accepts draw when losing by more than 200 centipawns") {
    // White: K a1, Black: K a8 + R b7 → material diff = −500cp, king safe.
    val state = parseState("k7/1r6/8/8/8/8/8/K7 w - - 0 1")
    assert(TestBot.shouldAcceptDraw(state))
  }

  // ──────────────────────────────────────────────
  // shouldAcceptDraw — false
  // ──────────────────────────────────────────────

  test("rejects draw in an equal position") {
    val state = parseState("k7/8/8/8/8/8/8/K7 w - - 0 1")
    assert(!TestBot.shouldAcceptDraw(state))
  }

  test("rejects draw when ahead materially") {
    // White: K a1 + R g8, Black: K a7 → material diff = +500cp.
    val state = parseState("6R1/k7/8/8/8/8/8/K7 w - - 0 1")
    assert(!TestBot.shouldAcceptDraw(state))
  }

  // ──────────────────────────────────────────────
  // Integration: MonteCarloSearch inherits the trait
  // ──────────────────────────────────────────────

  test("MonteCarloSearch inherits DrawOfferLogic draw behaviour") {
    val deadState = parseState("k7/8/8/8/8/8/8/K7 w - - 0 1")
    assert(MonteCarloSearch.shouldOfferDraw(deadState))

    val losingState = parseState("k7/1r6/8/8/8/8/8/K7 w - - 0 1")
    assert(MonteCarloSearch.shouldAcceptDraw(losingState))

    val winningState = parseState("6R1/k7/8/8/8/8/8/K7 w - - 0 1")
    assert(!MonteCarloSearch.shouldAcceptDraw(winningState))
  }
