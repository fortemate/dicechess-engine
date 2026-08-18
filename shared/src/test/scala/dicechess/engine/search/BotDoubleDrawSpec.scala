package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

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
  }

  test("Default SearchAlgorithm doubling and draw decisions") {
    object DefaultSearch extends SearchAlgorithm:
      override def findBestMove(state: GameState): Option[ScoredSequence] = None

    assert(!DefaultSearch.shouldOfferDouble(equalState, 1))
    assert(DefaultSearch.shouldAcceptDouble(equalState, 2)) // default takes > 25% (equal is 50%)
    assert(!DefaultSearch.shouldOfferDraw(equalState))
    assert(!DefaultSearch.shouldAcceptDraw(equalState))
  }

  test("RandomSearch doubling and draw decisions") {
    // Invoke them to ensure test coverage of all branch lines
    RandomSearch.shouldOfferDouble(equalState, 1)
    RandomSearch.shouldAcceptDouble(equalState, 2)
    RandomSearch.shouldOfferDraw(equalState)
    RandomSearch.shouldAcceptDraw(equalState)
  }
