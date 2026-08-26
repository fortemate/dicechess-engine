package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class KingCaptureProbabilitySuite extends FunSuite:

  private val SingleAttackerProb  = 91.0 / 216.0  // P(at least one specific die in 3d6)
  private val TwoAttackerTypeProb = 152.0 / 216.0 // P(at least one of two specific dice in 3d6)

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

  test("kingCaptureProbability preserves an indirect path when no direct capture exists") {
    // Black rook on e8 is blocked by its pawn on e7, but three Rook dice let it route around the blocker and capture
    // the White king on e5. The depth-1 prefilter must reject the roll and leave the unchanged DFS to find this path.
    val fen   = "4r3/4p3/8/4K3/8/8/8/8 b - - 0 1"
    val state = FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)
    val prob  = KingCaptureProbability.kingCaptureProbability(state, Color.White)
    assertEquals(prob, 1.0 / 216.0)
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
    assert(safeProb > 0.0, s"A blocked rook can still route around the pawn with three Rook dice: $safeProb")
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

  // Differential corpus captured before the int-slot rewrite and the depth-1 direct-capture prefilter. Every
  // optimization must reproduce all four probabilities bit-for-bit. Besides representative positions, the corpus
  // pins the prefilter's queen-type, per-roll, pawn-direction, multi-target and promotion traps.
  test("KCP optimizations preserve capture probabilities bit-for-bit (differential corpus)") {
    val cases = List(
      // (name, fen, kingWhite, queenWhite, kingBlack, queenBlack)
      (
        "initial",
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        0.0,
        0.0,
        0.0,
        0.0
      ),
      (
        "kiwipete",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        9.0 / 216.0,
        35.0 / 216.0,
        4.0 / 216.0,
        52.0 / 216.0
      ),
      ("endgame", "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 16.0 / 216.0, 0.0, 32.0 / 216.0, 0.0),
      ("castling safety", "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1", 0.0, 0.0, 0.0, 0.0),
      ("promotion", "k7/4P3/8/8/8/8/8/4K3 w - - 0 1", 0.0, 0.0, 60.0 / 216.0, 0.0),
      ("queen on rook file", "4q3/8/8/8/4K3/8/8/8 b - - 0 1", SingleAttackerProb, 0.0, 0.0, 0.0),
      (
        "queen on bishop diagonal",
        "8/7q/8/8/4K3/8/8/8 b - - 0 1",
        SingleAttackerProb,
        0.0,
        0.0,
        1.0 / 216.0
      ),
      (
        "two queens still require only the Queen die",
        "4q3/7q/8/8/4K3/8/8/8 b - - 0 1",
        SingleAttackerProb,
        0.0,
        0.0,
        1.0 / 216.0
      ),
      (
        "knight and bishop require either die",
        "8/7b/5n2/8/4K3/8/8/8 b - - 0 1",
        TwoAttackerTypeProb,
        0.0,
        0.0,
        0.0
      ),
      (
        "two queen targets are both inspected",
        "1r6/7b/8/8/4Q3/8/1Q6/8 b - - 0 1",
        0.0,
        TwoAttackerTypeProb,
        0.0,
        0.0
      ),
      ("black pawn attacks forward", "8/8/8/3p4/4K3/8/8/8 b - - 0 1", SingleAttackerProb, 0.0, 0.0, 0.0),
      ("black pawn does not attack backward", "8/8/8/4K3/3p4/8/8/8 b - - 0 1", 0.0, 0.0, 0.0, 0.0),
      ("white pawn attacks forward", "8/8/8/4k3/3P4/8/8/8 w - - 0 1", 0.0, 0.0, SingleAttackerProb, 0.0),
      ("white pawn does not attack backward", "8/8/8/3P4/4k3/8/8/8 w - - 0 1", 0.0, 0.0, 0.0, 0.0),
      (
        "promotion capture consumes a Pawn die",
        "8/8/8/8/8/8/1p6/Q7 b - - 0 1",
        0.0,
        SingleAttackerProb,
        0.0,
        0.0
      ),
      (
        "castling opens rook ray to king",
        "r3k2r/8/8/8/8/8/8/5K2 b kq - 0 1",
        16.0 / 216.0,
        0.0,
        0.0,
        0.0
      ),
      (
        "en-passant blocker removal opens ray to king",
        "4k3/8/8/3pP3/8/8/8/4R1K1 w - d6 0 1",
        0.0,
        0.0,
        32.0 / 216.0,
        0.0
      )
    )
    cases.foreach { (name, fen, kW, qW, kB, qB) =>
      val st = FenParser.parse(fen).fold(e => fail(s"bad FEN: $e"), identity)
      assertEquals(KingCaptureProbability.kingCaptureProbability(st, Color.White), kW, s"$name: White king")
      assertEquals(KingCaptureProbability.queenCaptureProbability(st, Color.White), qW, s"$name: White queen")
      assertEquals(KingCaptureProbability.kingCaptureProbability(st, Color.Black), kB, s"$name: Black king")
      assertEquals(KingCaptureProbability.queenCaptureProbability(st, Color.Black), qB, s"$name: Black queen")
    }
  }
