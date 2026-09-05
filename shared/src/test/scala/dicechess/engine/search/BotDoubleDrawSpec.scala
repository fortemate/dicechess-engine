package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.util.Random

class BotDoubleDrawSpec extends FunSuite:

  // Class-level reusable test fixtures to follow DRY (Don't Repeat Yourself)
  private val winFen   = "k7/8/8/8/8/8/PPPPPPPP/QNBK4 w - - 0 1"
  private val winState = parseState(winFen)

  private val loseFen   = "k7/q7/r7/8/8/8/8/4K3 w - - 0 1"
  private val loseState = parseState(loseFen)

  private val equalFen   = "k7/8/8/8/8/8/8/K7 w - - 0 1"
  private val equalState = parseState(equalFen)

  private val slightAdvantageFen = "k7/8/8/8/8/8/1P6/K7 w - - 0 1"
  private val advantageState     = parseState(slightAdvantageFen)

  private def parseState(fen: String): GameState =
    FenParser.parse(fen).getOrElse(fail(s"Failed to parse FEN: $fen"))

  test("Cautious Greedy (GreedySearchV2) doubling and draw decisions") {
    // 1. Winning position: Cautious Greedy exploits massive material advantage.
    // Win probability > 70%, matching the cautious double offering threshold.
    assert(GreedySearchV2.shouldOfferDouble(winState, 1))
    assert(GreedySearchV2.shouldAcceptDouble(winState, 2))
    assert(!GreedySearchV2.shouldOfferDraw(winState))
    assert(!GreedySearchV2.shouldAcceptDraw(winState))

    // 2. Losing position: Win probability < 25%.
    // Cautious Greedy declines doubling (Drop) and eagerly accepts draw offers to mitigate loss.
    assert(!GreedySearchV2.shouldOfferDouble(loseState, 1))
    assert(!GreedySearchV2.shouldAcceptDouble(loseState, 2))
    assert(!GreedySearchV2.shouldOfferDraw(loseState))
    assert(GreedySearchV2.shouldAcceptDraw(loseState))

    // 3. Balanced equal position: P(win) is exactly 50%.
    // This is below Cautious Greedy's doubling threshold (70%), but above its Take threshold (35%).
    assert(!GreedySearchV2.shouldOfferDouble(equalState, 1))
    assert(GreedySearchV2.shouldAcceptDouble(equalState, 2))

    // 4. Take/drop is decided for the named responder, not for the side to move: in winState White is to move
    // and winning, so a Black responder drops while a White responder takes; loseState is the mirror image.
    assert(GreedySearchV2.shouldAcceptDouble(winState, 2, Color.White))
    assert(!GreedySearchV2.shouldAcceptDouble(winState, 2, Color.Black))
    assert(!GreedySearchV2.shouldAcceptDouble(loseState, 2, Color.White))
    assert(GreedySearchV2.shouldAcceptDouble(loseState, 2, Color.Black))

    // Draw offers are only triggered in late game scenarios (move count > 30) when the position is equal.
    assert(!GreedySearchV2.shouldOfferDraw(equalState)) // Early game (move 1) -> false

    // Boundary tests for off-by-one errors on move number threshold (30 vs 31):
    val almostLateEqualState = equalState.copy(fullMoveNumber = 30)
    assert(!GreedySearchV2.shouldOfferDraw(almostLateEqualState)) // Move 30 -> false

    val lateEqualState = equalState.copy(fullMoveNumber = 31)
    assert(GreedySearchV2.shouldOfferDraw(lateEqualState)) // Move 31 -> true
  }

  test("AggressiveSearch doubling and draw decisions") {
    // 1. Winning position: Aggressive bot easily doubles and accepts doubles.
    assert(AggressiveSearch.shouldOfferDouble(winState, 1))
    assert(AggressiveSearch.shouldAcceptDouble(winState, 2))
    assert(!AggressiveSearch.shouldOfferDraw(winState))
    assert(!AggressiveSearch.shouldAcceptDraw(winState))

    // 2. Slightly advantageous position (up a single Pawn).
    // Win probability is ~56%, which is above the aggressive bot's early-doubling threshold (> 55%).
    assert(AggressiveSearch.shouldOfferDouble(advantageState, 1))

    // 3. Lost position: Win probability < 25%.
    // Aggressive bot rejects doubling but also rejects draws, choosing to play for checkmate regardless of odds.
    assert(!AggressiveSearch.shouldOfferDouble(loseState, 1))
    assert(!AggressiveSearch.shouldAcceptDouble(loseState, 2))
    assert(!AggressiveSearch.shouldOfferDraw(loseState))
    assert(!AggressiveSearch.shouldAcceptDraw(loseState))

    // 4. The aggressive threshold (0.22) applies to the named responder: Black, the side NOT to move, is the one
    // being asked in loseState and holds Q+R against a bare king.
    assert(!AggressiveSearch.shouldAcceptDouble(winState, 2, Color.Black))
    assert(AggressiveSearch.shouldAcceptDouble(loseState, 2, Color.Black))
    assert(!AggressiveSearch.shouldAcceptDouble(loseState, 2, Color.White))
  }

  test("MonteCarloSearch estimates the responder's equity, not the mover's") {
    // K+Q against a bare king, White to move: the queen on h1 already attacks a8 along the long diagonal, so White
    // captures with any roll that shows a queen (exact ply-0 mass 1 - (5/6)^3 = 0.42) while Black cannot capture at
    // all. A tiny budget keeps the Rao-Blackwellized estimator (216 rolls per ply) cheap under coverage.
    val tiny    = MonteCarloConfig(rollouts = 4, maxPlies = 3)
    val queenUp = parseState("k7/8/8/8/8/8/8/K6Q w - - 0 1")
    val white   = MonteCarloSearch.winProbability(queenUp, Color.White, tiny, new Random(1))
    val black   = MonteCarloSearch.winProbability(queenUp, Color.Black, tiny, new Random(1))
    assert(white > 0.30, s"White responder must take at the 0.30 threshold, got $white")
    assert(black < 0.30, s"Black responder must drop at the 0.30 threshold, got $black")
  }

  test("a strategy overriding only the responder overload is reached through the two-argument form") {
    object BlackOnlyTaker extends SearchAlgorithm:
      override def findBestMove(state: GameState): Option[ScoredSequence]                             = None
      override def shouldAcceptDouble(state: GameState, currentStake: Int, responder: Color): Boolean =
        responder == Color.Black

    assert(!BlackOnlyTaker.shouldAcceptDouble(winState, 2)) // side to move is White
    assert(BlackOnlyTaker.shouldAcceptDouble(parseState("k7/8/8/8/8/8/PPPPPPPP/QNBK4 b - - 0 1"), 2))
    assert(BlackOnlyTaker.shouldAcceptDouble(winState, 2, Color.Black))
  }

  test("Default SearchAlgorithm doubling and draw decisions") {
    object DefaultSearch extends SearchAlgorithm:
      override def findBestMove(state: GameState): Option[ScoredSequence] = None

    assert(!DefaultSearch.shouldOfferDouble(equalState, 1))
    assert(DefaultSearch.shouldAcceptDouble(equalState, 2)) // default takes > 25% (equal is 50%)
    assert(!DefaultSearch.shouldOfferDraw(equalState))
    assert(!DefaultSearch.shouldAcceptDraw(equalState))
  }

  test("shouldAcceptDouble explicit responder perspective overload on asymmetric position") {
    object DefaultSearch extends SearchAlgorithm:
      override def findBestMove(state: GameState): Option[ScoredSequence] = None

    // winState FEN is "k7/8/8/8/8/8/PPPPPPPP/QNBK4 w - - 0 1" (active: White, White is winning massively)
    // White's win prob is near 1.0 (> 0.25), Black's win prob is near 0.0 (< 0.25).
    assert(DefaultSearch.shouldAcceptDouble(winState, 2, Color.White))
    assert(!DefaultSearch.shouldAcceptDouble(winState, 2, Color.Black))

    // Two-argument delegates with state.activeColor (White in winState)
    assert(DefaultSearch.shouldAcceptDouble(winState, 2))

    // Black-to-move asymmetric position
    val blackActiveWinState = parseState("k7/8/8/8/8/8/PPPPPPPP/QNBK4 b - - 0 1")
    // In blackActiveWinState, activeColor is Black, but White is still winning massively.
    assert(DefaultSearch.shouldAcceptDouble(blackActiveWinState, 2, Color.White))
    assert(!DefaultSearch.shouldAcceptDouble(blackActiveWinState, 2, Color.Black))
    // Two-arg overload delegates with state.activeColor (which is Black), returning false
    assert(!DefaultSearch.shouldAcceptDouble(blackActiveWinState, 2))
  }

  test("RandomSearch doubling and draw decisions") {
    // Invoke them to ensure test coverage of all branch lines
    RandomSearch.shouldOfferDouble(equalState, 1)
    RandomSearch.shouldAcceptDouble(equalState, 2)
    RandomSearch.shouldAcceptDouble(equalState, 2, Color.Black)
    RandomSearch.shouldOfferDraw(equalState)
    RandomSearch.shouldAcceptDraw(equalState)
  }
