package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class KingCaptureProbabilitySuite extends FunSuite:

  private val SingleAttackerProb = 91.0 / 216.0 // P(at least one specific die in 3d6)

  test("kingCaptureProbability returns 0 when no attackers exist") {
    val fen   = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
    val state = FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)
    assertEquals(KingCaptureProbability.kingCaptureProbability(state, Color.White), 0.0)
    assertEquals(KingCaptureProbability.kingCaptureProbability(state, Color.Black), 0.0)
  }

  test("kingCaptureProbability returns 0 when attacker has no piece on board") {
    // White king on e1, no black pieces. But Black can still roll dice — just nothing to move.
    val fen   = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
    val state = FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)
    assertEquals(KingCaptureProbability.kingCaptureProbability(state, Color.White), 0.0)
  }

  test("kingCaptureProbability returns correct value for single knight attacker") {
    // White king on e5, Black knight on f7 attacks e5.
    // Black captures king when at least one die shows Knight (2).
    val fen   = "8/5n2/8/4K3/8/8/8/8 b - - 0 1"
    val state = FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)
    val prob  = KingCaptureProbability.kingCaptureProbability(state, Color.White)
    assertEqualsDouble(prob, SingleAttackerProb, 0.0001)
  }

  test("kingCaptureProbability returns correct value for single bishop attacker") {
    // White king on e5, Black bishop on h2 attacks e5 (clear diagonal).
    // Black captures king when at least one die shows Bishop (3).
    val fen   = "8/8/8/4K3/8/8/7b/8 b - - 0 1"
    val state = FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)
    val prob  = KingCaptureProbability.kingCaptureProbability(state, Color.White)
    assertEqualsDouble(prob, SingleAttackerProb, 0.0001)
  }

  test("kingCaptureProbability is > 0 when a blocked rook can be freed") {
    // Black rook on e8 blocked by a Black pawn on e7 — Black can move the pawn (needs Pawn die)
    // then capture the king with the rook (needs Rook die). P > 0.
    val fen   = "4r3/4p3/8/4K3/8/8/8/8 b - - 0 1"
    val state = FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)
    val prob  = KingCaptureProbability.kingCaptureProbability(state, Color.White)
    assert(prob > 0.0, "Rook can be freed by moving the pawn, then capturing the king")
  }

  test("kingCaptureProbability returns 0 when kings are far apart") {
    // White king on a1, Black king on h8. Distance is 7 squares — too far for 3 king moves.
    val fen   = "7k/8/8/8/8/8/8/K7 b - - 0 1"
    val state = FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)
    assertEquals(KingCaptureProbability.kingCaptureProbability(state, Color.White), 0.0)
  }

  test("queenCaptureProbability returns 0 when no queens exist") {
    val fen   = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
    val state = FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)
    assertEquals(KingCaptureProbability.queenCaptureProbability(state, Color.White), 0.0)
  }

  test("queenCaptureProbability returns correct value for single queen attacker") {
    // White queen on e5, Black knight on f7 attacks e5.
    // Black captures queen when at least one die shows Knight (2).
    val fen   = "8/5n2/8/4Q3/8/8/8/8 b - - 0 1"
    val state = FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)
    val prob  = KingCaptureProbability.queenCaptureProbability(state, Color.White)
    assertEqualsDouble(prob, SingleAttackerProb, 0.0001)
  }

  test("kingCaptureProbability is higher for an exposed king than for a protected king") {
    // Exposed: White king on e1, Black rook on e8 (direct line).
    val stateExposed = FenParser
      .parse("4r3/8/8/8/8/8/8/4K3 b - - 0 1")
      .fold(err => fail(s"Failed to parse FEN: $err"), identity)
      .withDicePool(Nil)
      .endTurn()

    // Safe: White king on e1, White pawn on e2 blocks the rook's file, Black rook on e8.
    val stateSafe = FenParser
      .parse("4r3/8/8/8/8/8/4P3/4K3 b - - 0 1")
      .fold(err => fail(s"Failed to parse FEN: $err"), identity)
      .withDicePool(Nil)
      .endTurn()

    val exposedProb = KingCaptureProbability.kingCaptureProbability(stateExposed, Color.White)
    val safeProb    = KingCaptureProbability.kingCaptureProbability(stateSafe, Color.White)

    assert(exposedProb > safeProb, s"Exposed P=$exposedProb should be > Safe P=$safeProb")
    assert(exposedProb > 0.4, s"Exposed king should have high capture probability: $exposedProb")
    assert(safeProb > 0.0, s"A blocked rook can still be freed with pawn+rook dice: $safeProb")
  }

  test("captureDFS fails fast instead of recursing forever on castling rights that contradict piece placement (#549)") {
    // Rotated-scrape artifact: kings on d1/d8, White's pieces on ranks 7-8, yet castling still reads KQkq.
    // When this regression was caught, MoveGenerator.tryCastle checked only the rights flag and that f8/g8/h8
    // are empty, so it emitted a castling move from a square holding a White queen toward a corner holding
    // nothing; without the depth bound the resulting mailbox/bitboard desync recursed forever instead of
    // terminating. tryCastle now also validates king/rook placement (#594) — an independent defense; the depth
    // bound this test pins must hold regardless.
    val fen   = "RNBKQ3/PPPPPnB1/8/8/4N3/4Q1Q1/pppppp1R/rnbkqb1r b KQkq -"
    val state = FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)
    // Termination is the point of the regression: pre-fix, this call recurses until StackOverflowError.
    val prob = KingCaptureProbability.kingCaptureProbability(state, Color.White)
    assert(prob >= 0.0 && prob <= 1.0, s"Expected a valid probability, got $prob")
  }

  test("capture probabilities are invariant to castling rights that contradict piece placement (#594)") {
    // 180°-rotated scrape rows: the kings are on the d-file, so the surviving 'kq' rights cannot be
    // exercised and stripping them must not change any capture probability. Pre-fix, tryCastle emits
    // a phantom e8→g8 castle that makeMove applies with the mover color read from the mailbox (a
    // White piece), desyncing mailbox and bitboards and inflating the queen-capture probability.
    val rows = List(
      (
        "RNB1K2R/PPPPP2P/8/1Q6/5RN1/2n1B3/p1pppp1p/r1bkqbnQ b kq - 0 1",
        "RNB1K2R/PPPPP2P/8/1Q6/5RN1/2n1B3/p1pppp1p/r1bkqbnQ b - - 0 1"
      ),
      (
        "RNB1K2R/PPPPP2P/8/1n6/5RN1/4B3/p1pppp1p/r1bkqbnQ w kq - 0 1",
        "RNB1K2R/PPPPP2P/8/1n6/5RN1/4B3/p1pppp1p/r1bkqbnQ w - - 0 1"
      )
    )
    rows.foreach { (fenWithRights, fenStripped) =>
      val asIs     = FenParser.parse(fenWithRights).fold(err => fail(s"Failed to parse FEN: $err"), identity)
      val stripped = FenParser.parse(fenStripped).fold(err => fail(s"Failed to parse FEN: $err"), identity)
      List(Color.White, Color.Black).foreach { defender =>
        val defenderName = if defender.isWhite then "white" else "black"
        assertEqualsDouble(
          KingCaptureProbability.kingCaptureProbability(asIs, defender),
          KingCaptureProbability.kingCaptureProbability(stripped, defender),
          1e-12,
          s"king capture, defender=$defenderName, fen=$fenWithRights"
        )
        assertEqualsDouble(
          KingCaptureProbability.queenCaptureProbability(asIs, defender),
          KingCaptureProbability.queenCaptureProbability(stripped, defender),
          1e-12,
          s"queen capture, defender=$defenderName, fen=$fenWithRights"
        )
      }
    }
  }

  // Differential corpus: king/queen capture probabilities captured from the pre-int-encoding (List-based)
  // captureDFS. The int-slot rewrite must reproduce them bit-for-bit — the dice multiset seen by move generation
  // is unchanged; a removed die just leaves a harmless empty slot the dicePool getter skips.
  test("captureDFS int-slot rewrite preserves the capture probabilities (differential corpus)") {
    val cases = List(
      // (fen, kingWhite, queenWhite, kingBlack, queenBlack)
      ("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 0.0, 0.0, 0.0, 0.0),
      (
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        0.041666666666666664,
        0.16203703703703703,
        0.018518518518518517,
        0.24074074074074073
      ),
      ("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 0.07407407407407407, 0.0, 0.14814814814814814, 0.0)
    )
    cases.foreach { (fen, kW, qW, kB, qB) =>
      val st = FenParser.parse(fen).fold(e => fail(s"bad FEN: $e"), identity)
      assertEqualsDouble(KingCaptureProbability.kingCaptureProbability(st, Color.White), kW, 1e-12)
      assertEqualsDouble(KingCaptureProbability.queenCaptureProbability(st, Color.White), qW, 1e-12)
      assertEqualsDouble(KingCaptureProbability.kingCaptureProbability(st, Color.Black), kB, 1e-12)
      assertEqualsDouble(KingCaptureProbability.queenCaptureProbability(st, Color.Black), qB, 1e-12)
    }
  }
