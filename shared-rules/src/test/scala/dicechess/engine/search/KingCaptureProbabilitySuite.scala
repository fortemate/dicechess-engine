// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.{MoveGenFixtures, MoveGenerator}
import munit.FunSuite
import scala.concurrent.duration.*
import scala.util.Random
import scala.util.boundary, boundary.break

class KingCaptureProbabilitySuite extends FunSuite:

  override def munitTimeout: Duration = 3.minutes

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
    val fen   = "8/8/8/8/8/8/8/4K3 w - - 0 1"
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

  /** Reference unoptimized DFS oracle over [[KcpScratchBoard]] and [[MoveGenerator.generatePieceMoves]].
    *
    * Used as a ground-truth differential oracle to verify 100% bit-exact parity of [[KingCaptureProbability]] across
    * fixture catalogs, tactical edge cases, and seeded random-walk positions.
    */
  private def referenceCaptureProbability(state: GameState, defenderColor: Color, targetBB: Bitboard): Double =
    boundary {
      val defenderPieces = if defenderColor.isWhite then state.whitePieces else state.blackPieces
      val targets        = targetBB & defenderPieces
      if targets.isEmpty then break(0.0)

      val opponent  = defenderColor.opponent
      val scratch   = KcpScratchBoard.fromGameState(state)
      val rootFlags = state.flags.withActiveColor(opponent)
      var count     = 0
      var i         = 0
      while i < DiceRolls.weighted.length do
        val (rolls, weight) = DiceRolls.weighted(i)
        scratch.flags = rootFlags.withDicePool(rolls)
        if referenceCaptureDFS(scratch, targets, GameFlags.DiceSlots) then count += weight
        i += 1
      count.toDouble / DiceRolls.totalOrderedRolls
    }

  private def referenceCaptureDFS(scratch: KcpScratchBoard, targets: Bitboard, remainingDice: Int): Boolean =
    boundary {
      if remainingDice <= 0 then break(false)
      val flags = scratch.flags
      if flags.isDicePoolEmpty then break(false)

      val d1            = flags.diceSlot1
      val d2            = flags.diceSlot2
      val d3            = flags.diceSlot3
      val readOnlyState = scratch.toGameStateReadOnly

      if d1 != 0 && referenceTryPieceMoves(scratch, targets, remainingDice, flags, readOnlyState, d1) then break(true)
      if d2 != 0 && d2 != d1 && referenceTryPieceMoves(scratch, targets, remainingDice, flags, readOnlyState, d2) then
        break(true)
      if d3 != 0 && d3 != d1 && d3 != d2 &&
        referenceTryPieceMoves(scratch, targets, remainingDice, flags, readOnlyState, d3)
      then break(true)

      false
    }

  private def referenceTryPieceMoves(
      scratch: KcpScratchBoard,
      targets: Bitboard,
      remainingDice: Int,
      flags: GameFlags,
      readOnlyState: GameState,
      die: Int
  ): Boolean = boundary {
    val moves = MoveGenerator.generatePieceMoves(readOnlyState, PieceType(die))
    var i     = 0
    while i < moves.length do
      val move      = moves(i)
      val moverType = scratch.mailbox(move.fromSquare.index).pieceType
      val survived  = flags.consumeDiceFor(move, moverType)
      if !(targets & Bitboard.fromSquare(move.toSquare)).isEmpty then break(true)
      if survived.isValid then
        val diceConsumed = if move.isCastling then 2 else 1
        val undo         = scratch.makeMoveInPlace(move)
        scratch.flags = scratch.flags.withDiceSlotsOf(survived)
        val hit = referenceCaptureDFS(scratch, targets, remainingDice - diceConsumed)
        scratch.undoMove(move, undo)
        if hit then break(true)
      i += 1
    false
  }

  private def assertBitExactOracle(state: GameState, context: String): Unit =
    for defender <- List(Color.White, Color.Black) do
      val side    = if defender.isWhite then "White" else "Black"
      val actualK = KingCaptureProbability.kingCaptureProbability(state, defender)
      val expectK = referenceCaptureProbability(state, defender, state.kings)
      assertEquals(actualK, expectK, s"$context [king, defender=$side]")

      val actualQ = KingCaptureProbability.queenCaptureProbability(state, defender)
      val expectQ = referenceCaptureProbability(state, defender, state.queens)
      assertEquals(actualQ, expectQ, s"$context [queen, defender=$side]")

  private def assertMoveGenIntoParity(state: GameState, context: String): Unit =
    val buf = new Array[Int](256)
    for color <- List(Color.White, Color.Black) do
      val st          = state.withActiveColor(color)
      val isWhite     = color.isWhite
      val myPieces    = if isWhite then st.whitePieces.value else st.blackPieces.value
      val enemyPieces = if isWhite then st.blackPieces.value else st.whitePieces.value
      val enemyKings  = enemyPieces & st.kings.value
      val myRooks     = myPieces & st.rooks.value
      val ep          = st.enPassant.value
      val cr          = st.flags.castlingRights

      for die <- 1 to 6 do
        val active = myPieces & (die match
          case 1 => st.pawns.value
          case 2 => st.knights.value
          case 3 => st.bishops.value
          case 4 => st.rooks.value
          case 5 => st.queens.value
          case _ => st.kings.value)
        val count = MoveGenerator.generatePieceMovesInto(
          myPieces,
          enemyPieces,
          active,
          enemyKings,
          myRooks,
          ep,
          cr,
          isWhite,
          die,
          buf
        )
        val actual   = (0 until count).map(buf(_)).toList
        val expected = MoveGenerator.generatePieceMoves(st, PieceType(die)).map(_.toInt)
        assertEquals(actual, expected, s"$context [color=$color, die=$die]")

  test("MoveGenerator.generatePieceMovesInto matches generatePieceMoves element-for-element across fixtures") {
    val fens = KingCaptureFixtures.cases.map(_.fen) ++
      MoveGenFixtures.allSuites.flatMap(_.cases.map(_.fen.split(" ").take(6).mkString(" "))) ++
      List(
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
        "4k3/3P4/8/3pP3/3pP3/8/3p4/4K3 w - d6e3 0 1",
        "1r2k2r/P6P/8/8/8/8/p6p/1R2K2R w KQkq - 0 1"
      )
    for (fen, idx) <- fens.distinct.zipWithIndex do
      val st = FenParser.parse(fen).fold(e => fail(s"bad FEN '$fen': $e"), identity)
      assertMoveGenIntoParity(st, s"fixture-$idx ($fen)")
  }

  test("differential oracle parity across all KingCaptureFixtures and MoveGenFixtures") {
    val fens = (
      KingCaptureFixtures.cases.map(_.fen) ++
        MoveGenFixtures.allSuites.flatMap(_.cases.map(_.fen.split(" ").take(6).mkString(" ")))
    ).distinct

    for (fen, idx) <- fens.zipWithIndex do
      val st = FenParser.parse(fen).fold(e => fail(s"bad FEN '$fen': $e"), identity)
      assertBitExactOracle(st, s"catalog-$idx ($fen)")
  }

  test("differential oracle parity across tactical 3-ply edge cases (castling, EP, promotions, ray unblocking)") {
    val tacticalFens = List(
      // White and Black O-O and O-O-O opening or placing rook on king/queen file
      "r3k2r/8/8/8/8/8/8/2K2Q2 b kq - 0 1",
      "5k1q/8/8/8/8/8/8/R3K2R w KQ - 0 1",
      "2k5/8/8/8/8/8/8/R3K2R w KQ - 0 1",
      "r3k2r/8/8/8/8/8/8/3K4 b kq - 0 1",
      // En-passant at Level 0 and Level 1 (unblocking rays, moving onto targetPawnMask, invalidating EP)
      "4k3/8/8/3pP3/8/8/4Q3/4R1K1 w - d6 0 1",
      "4r1k1/4q3/8/8/3pP3/8/8/4K3 b - e3 0 1",
      "8/8/2k5/3pP3/8/8/8/4K3 w - d6 0 1",
      "4k3/8/8/8/3pP3/2K5/8/8 b - e3 0 1",
      "4k3/3p4/8/4P3/8/8/8/4R1K1 b - - 0 1",
      // Promotions at Level 0 and Level 1 (quiet and capture, all 4 piece types N/B/R/Q)
      "2k5/4P3/8/8/8/8/8/4K3 w - - 0 1",
      "4k3/8/8/8/8/8/4p3/2K5 b - - 0 1",
      "1n1qk3/2P5/8/8/8/8/2p5/1N1QK3 w - - 0 1",
      "k7/7P/8/8/8/8/7p/K7 w - - 0 1",
      "3k4/8/4P3/8/8/4p3/8/3K4 w - - 0 1",
      // 2-step and 3-step reachability for all 6 piece types (Pawn, Knight, Bishop light/dark, Rook, Queen, King)
      "4k3/8/8/8/4P3/8/8/4K3 w - - 0 1",
      "4k3/8/8/4p3/8/8/8/4K3 b - - 0 1",
      "4k3/8/8/8/8/3N4/8/4K3 w - - 0 1",
      "4k3/8/3n4/8/8/8/8/4K3 b - - 0 1",
      "4k3/8/8/8/8/8/1B6/B3K3 w - - 0 1",
      "b3k3/1b6/8/8/8/8/8/4K3 b - - 0 1",
      "4k3/8/8/4K3/8/8/8/8 w - - 0 1",
      // Double blockers on orthogonal and diagonal rays (requiring 2 unblocking moves + 1 slider capture)
      "4k3/8/8/4N3/4B3/8/4R2Q/4K3 w - - 0 1",
      "4k3/4r2q/8/4b3/4n3/8/8/4K3 b - - 0 1",
      "7k/8/5N2/4P3/8/2B5/1Q6/4K3 w - - 0 1",
      // Multi-queen positions (2 and 3 queens on different square colors and files)
      "1q2k2q/8/3n1b2/8/4N3/2B3R1/1Q5Q/4K1Q1 w - - 0 1"
    )

    for (fen, idx) <- tacticalFens.zipWithIndex do
      val st = FenParser.parse(fen).fold(e => fail(s"bad FEN '$fen': $e"), identity)
      assertBitExactOracle(st, s"tactical-$idx ($fen)")
      assertMoveGenIntoParity(st, s"tactical-movegen-$idx ($fen)")
  }

  test("differential oracle parity across 200 seeded random-walk positions") {
    val rng      = new Random(20261111L)
    val seedFens = List(
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
      "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
      "r1b1k2r/pp1p1ppp/2n1pn2/8/1bP5/2N1PN2/PP1B1PPP/R2QKB1R w KQkq - 0 8",
      "r3k2r/pppq1ppp/2npbn2/4p3/2B1P3/2NPBN2/PPPQ1PPP/R3K2R w KQkq - 4 7"
    )
    val roots = seedFens.map(fen => FenParser.parse(fen).fold(e => fail(s"bad FEN: $e"), identity))

    var tested = 0
    for (root, rootIdx) <- roots.zipWithIndex do
      for walk <- 1 to 10 do
        var current = root
        for ply <- 1 to 4 do
          val moves = MoveGenerator.generateAllMoves(current).filter(m => !current.isKingCapture(m))
          if moves.nonEmpty then
            val mv = moves(rng.nextInt(moves.length))
            current = current.makeMove(mv)
            if rng.nextBoolean() then current = current.endTurn()
            tested += 1
            assertBitExactOracle(current, s"root=$rootIdx walk=$walk ply=$ply")
            if ply == 2 then assertMoveGenIntoParity(current, s"movegen root=$rootIdx walk=$walk ply=$ply")
    assertEquals(tested, 200)
  }
