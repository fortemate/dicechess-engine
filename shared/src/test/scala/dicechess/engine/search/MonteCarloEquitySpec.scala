package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.concurrent.duration.*
import scala.util.Random

class MonteCarloEquitySpec extends FunSuite:

  // Rollout tests run KingCaptureProbability per ply; under scoverage on CI's (slow) Scala.js runner that is far
  // slower than locally, so allow generous headroom above the 30s default. Budgets are kept tiny and positions
  // low-mobility (knights, not queens) so they still finish in seconds.
  override def munitTimeout: Duration = 90.seconds

  private def buildState(placement: List[(Square, Piece)], activeColor: Color): GameState =
    val mb      = Array.fill[Piece](64)(Piece.Empty)
    var white   = Bitboard.empty
    var black   = Bitboard.empty
    var pawns   = Bitboard.empty
    var knights = Bitboard.empty
    var bishops = Bitboard.empty
    var rooks   = Bitboard.empty
    var queens  = Bitboard.empty
    var kings   = Bitboard.empty
    placement.foreach { (sq, p) =>
      mb(sq.index) = p
      val bb = Bitboard.fromSquare(sq)
      if p.color.isWhite then white = white | bb else black = black | bb
      p.pieceType match
        case PieceType.Pawn   => pawns = pawns | bb
        case PieceType.Knight => knights = knights | bb
        case PieceType.Bishop => bishops = bishops | bb
        case PieceType.Rook   => rooks = rooks | bb
        case PieceType.Queen  => queens = queens | bb
        case PieceType.King   => kings = kings | bb
        case _                => ()
    }
    GameState(
      white,
      black,
      pawns,
      knights,
      bishops,
      rooks,
      queens,
      kings,
      mailbox = Mailbox.fromBuilder(mb),
      flags = GameFlags.fromList(activeColor, 0, 0, Nil, 0),
      enPassant = Bitboard.empty,
      fullMoveNumber = 1
    )

  private def parseFen(fen: String): GameState =
    FenParser.parse(fen).fold(e => fail(s"bad FEN '$fen': $e"), identity)

  /** White to move with a piece of every type attacking the lone Black king on e4: every one of the 216 rolls captures,
    * so the pre-roll White-win probability is exactly `1.0` and each rollout resolves on the first ply (cheap).
    */
  private val whiteAlwaysWins: GameState =
    buildState(
      List(
        Square('e', 4) -> Piece(Color.Black, PieceType.King),
        Square('d', 3) -> Piece(Color.White, PieceType.Pawn),
        Square('c', 3) -> Piece(Color.White, PieceType.Knight),
        Square('h', 7) -> Piece(Color.White, PieceType.Bishop),
        Square('h', 4) -> Piece(Color.White, PieceType.Rook),
        Square('a', 8) -> Piece(Color.White, PieceType.Queen),
        Square('d', 4) -> Piece(Color.White, PieceType.King)
      ),
      Color.White
    )

  // Sharp but cheap: White knight c7 attacks the Black king on e8, Black knight c2 attacks the White king on e1, so
  // king captures occur within a few random plies (real win mass + spread) while knight move-gen keeps KCP cheap.
  private val sharpKnights = "4k3/2N5/8/8/8/8/2n5/4K3 w - - 0 1"
  // Quiet, cheap: knights face off in the centre, neither reaching a king in one move.
  private val quietKnights = "4k3/8/8/3n4/3N4/8/8/4K3 w - - 0 1"

  // --- Convergence ---

  test("converges to the exact value on a 1-ply-decidable position (White wins with probability 1)") {
    val est = MonteCarloEquity.estimate(whiteAlwaysWins, MonteCarloConfig(rollouts = 32), new Random(1))
    assertEqualsDouble(est.whiteWin, 1.0, 1e-9)
    assertEqualsDouble(est.blackWin, 0.0, 1e-9)
    assertEqualsDouble(est.undecided, 0.0, 1e-9)
  }

  test("symmetric Black-to-move position (via colorFlip) yields Black-win probability 1") {
    val blackAlwaysWins = Symmetry.colorFlip(whiteAlwaysWins)
    assert(blackAlwaysWins.activeColor.isBlack)
    val est = MonteCarloEquity.estimate(blackAlwaysWins, MonteCarloConfig(rollouts = 32), new Random(2))
    assertEqualsDouble(est.blackWin, 1.0, 1e-9)
    assertEqualsDouble(est.whiteWin, 0.0, 1e-9)
  }

  // --- Invariants ---

  test("the three outcome masses always sum to 1") {
    val states = List(whiteAlwaysWins, parseFen(sharpKnights), parseFen(quietKnights))
    states.zipWithIndex.foreach { (state, i) =>
      val est = MonteCarloEquity.estimate(state, MonteCarloConfig(rollouts = 8, maxPlies = 4), new Random(i))
      assertEqualsDouble(est.whiteWin + est.blackWin + est.undecided, 1.0, 1e-9)
    }
  }

  test("all probabilities are in [0, 1] and the standard error is non-negative") {
    val est =
      MonteCarloEquity.estimate(parseFen(sharpKnights), MonteCarloConfig(rollouts = 16, maxPlies = 4), new Random(4))
    List(est.whiteWin, est.blackWin, est.undecided).foreach { p =>
      assert(p >= 0.0 && p <= 1.0, s"probability out of range: $p")
    }
    assert(est.standardError >= 0.0)
    assertEquals(est.rollouts, 16)
  }

  test("a horizon of zero plies leaves all mass undecided") {
    val est = MonteCarloEquity.estimate(whiteAlwaysWins, MonteCarloConfig(rollouts = 4, maxPlies = 0), new Random(5))
    assertEqualsDouble(est.undecided, 1.0, 1e-9)
    assertEqualsDouble(est.whiteWin, 0.0, 1e-9)
  }

  // --- Variance reduction (port of the reference self-check) ---

  test("Rao-Blackwell integration beats vanilla Monte-Carlo (variance reduction > 1)") {
    val est =
      MonteCarloEquity.estimate(parseFen(sharpKnights), MonteCarloConfig(rollouts = 64, maxPlies = 5), new Random(6))
    assert(!est.varianceReductionVsVanilla.isNaN, "variance-reduction ratio is NaN")
    assert(
      est.varianceReductionVsVanilla > 1.0,
      s"expected variance reduction > 1, got ${est.varianceReductionVsVanilla}"
    )
  }

  // --- Determinism & budgets ---

  test("the same seed yields an identical estimate") {
    val cfg = MonteCarloConfig(rollouts = 24, maxPlies = 4)
    val a   = MonteCarloEquity.estimate(parseFen(sharpKnights), cfg, new Random(42))
    val b   = MonteCarloEquity.estimate(parseFen(sharpKnights), cfg, new Random(42))
    assertEquals(a, b)
  }

  test("adaptive stopping halts at minRollouts once the target error is met") {
    // A huge target error is satisfied immediately, so it stops at exactly minRollouts.
    val est = MonteCarloEquity.estimate(
      parseFen(sharpKnights),
      MonteCarloConfig(rollouts = 5000, maxPlies = 4, targetError = 1.0, minRollouts = 32),
      new Random(7)
    )
    assertEquals(est.rollouts, 32)
  }

  // --- Convenience overloads ---

  test("the fixed-budget overload runs the requested number of rollouts") {
    val est = MonteCarloEquity.estimate(whiteAlwaysWins, 16, new Random(8))
    assertEquals(est.rollouts, 16)
    assertEqualsDouble(est.whiteWin, 1.0, 1e-9)
  }

  test("the default overload resolves a decided position regardless of the random source") {
    // whiteAlwaysWins resolves on ply 1, so the unseeded default is still deterministic here.
    val est = MonteCarloEquity.estimate(whiteAlwaysWins)
    assertEqualsDouble(est.whiteWin, 1.0, 1e-9)
  }

  test("a deadline already in the past stops the estimate after a single rollout") {
    val past = System.nanoTime() - 1_000_000L
    val est  = MonteCarloEquity.estimate(whiteAlwaysWins, MonteCarloConfig(rollouts = 1000), past, new Random(9))
    assertEquals(est.rollouts, 1)
    assertEqualsDouble(est.whiteWin, 1.0, 1e-9)
  }

  // Golden values captured from the pre-hoist implementation. Hoisting ply-0 KingCaptureProbability out of the rollout
  // loop must leave the estimate bit-for-bit identical (KCP is deterministic and consumes no RNG, so the random stream
  // and every rollout outcome are unchanged). A regression here means the hoist altered behaviour.
  test("ply-0 hoist preserves the estimate exactly (golden regression on a sharp position)") {
    val est =
      MonteCarloEquity.estimate(parseFen(sharpKnights), MonteCarloConfig(rollouts = 64, maxPlies = 6), new Random(123))
    assertEqualsDouble(est.whiteWin, 0.5251474787726832, 1e-12)
    assertEqualsDouble(est.blackWin, 0.16441636804094195, 1e-12)
    assertEqualsDouble(est.undecided, 0.31043615318637463, 1e-12)
  }
