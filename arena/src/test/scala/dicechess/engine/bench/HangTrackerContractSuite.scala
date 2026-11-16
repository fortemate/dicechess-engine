// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import java.lang.reflect.{Constructor, Method}
import munit.FunSuite

class HangTrackerContractSuite extends FunSuite:
  final private case class Tracker(instance: AnyRef, white: HangTally, black: HangTally)

  private val trackerClass = Class.forName("dicechess.engine.bench.BotMatchRunner$HangTracker")

  private val constructor: Constructor[?] =
    val found = trackerClass.getDeclaredConstructors.filter(_.getParameterCount == 2)
    assertEquals(found.length, 1)
    val result = found.head
    result.setAccessible(true)
    result

  private val onCompletedTurn: Method =
    val result = trackerClass.getDeclaredMethod(
      "onCompletedTurn",
      classOf[GameState],
      classOf[GameState],
      java.lang.Integer.TYPE
    )
    result.setAccessible(true)
    result

  private def state(fen: String): GameState = FenParser.parse(fen).fold(fail(_), identity)

  private def tracker(): Tracker =
    val white    = new HangTally
    val black    = new HangTally
    val instance = constructor.newInstance(white, black).asInstanceOf[AnyRef]
    Tracker(instance, white, black)

  private def completeTurn(tracker: Tracker, beforeFen: String, afterFen: String, mover: Color): Unit =
    val before         = state(beforeFen)
    val after          = state(afterFen)
    val beforeSnapshot = FenParser.serialize(before)
    val afterSnapshot  = FenParser.serialize(after)
    onCompletedTurn.invoke(tracker.instance, before, after, mover.asInstanceOf[AnyRef])
    assertEquals(FenParser.serialize(before), beforeSnapshot)
    assertEquals(FenParser.serialize(after), afterSnapshot)

  private def whiteStats(tracker: Tracker): HangStats = tracker.white.snapshot
  private def blackStats(tracker: Tracker): HangStats = tracker.black.snapshot

  test("quiet bare-king turns increment only the mover's turn count") {
    val run        = tracker()
    val initial    = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
    val afterWhite = "4k3/8/8/8/8/8/8/3K4 b - - 1 1"
    val afterBlack = "3k4/8/8/8/8/8/8/3K4 w - - 2 2"

    completeTurn(run, initial, afterWhite, Color.White)
    assertEquals(whiteStats(run), HangStats(1, 0, 0, 0L, 0, 0L))
    assertEquals(blackStats(run), HangStats.empty)
    completeTurn(run, afterWhite, afterBlack, Color.Black)
    assertEquals(whiteStats(run), HangStats(1, 0, 0, 0L, 0, 0L))
    assertEquals(blackStats(run), HangStats(1, 0, 0, 0L, 0, 0L))
  }

  test("a queen hang and next-turn pawn capture are attributed to the hanging side") {
    val run           = tracker()
    val whiteBefore   = "4k3/8/2p5/8/3Q4/8/8/4K3 w - - 0 1"
    val whiteHung     = "4k3/8/2p5/3Q4/8/8/8/4K3 b - - 1 1"
    val whiteCaptured = "4k3/8/8/3p4/8/8/8/4K3 w - - 0 2"
    completeTurn(run, whiteBefore, whiteHung, Color.White)
    assertEquals(whiteStats(run), HangStats(1, 1, 1, 900L, 0, 0L))
    assertEquals(blackStats(run), HangStats.empty)
    completeTurn(run, whiteHung, whiteCaptured, Color.Black)
    assertEquals(whiteStats(run), HangStats(1, 1, 1, 900L, 1, 900L))
    assertEquals(blackStats(run), HangStats(1, 0, 0, 0L, 0, 0L))

    val mirrored      = tracker()
    val blackBefore   = "4k3/8/8/3q4/8/2P5/8/4K3 b - - 0 1"
    val blackHung     = "4k3/8/8/8/3q4/2P5/8/4K3 w - - 1 1"
    val blackCaptured = "4k3/8/8/8/3P4/8/8/4K3 b - - 0 2"
    completeTurn(mirrored, blackBefore, blackHung, Color.Black)
    assertEquals(blackStats(mirrored), HangStats(1, 1, 1, 900L, 0, 0L))
    completeTurn(mirrored, blackHung, blackCaptured, Color.White)
    assertEquals(blackStats(mirrored), HangStats(1, 1, 1, 900L, 1, 900L))
    assertEquals(whiteStats(mirrored), HangStats(1, 0, 0, 0L, 0, 0L))
  }

  test("non-queen hangs and multiple hanging queens aggregate by turn and material") {
    val rookRun = tracker()
    completeTurn(
      rookRun,
      "4k3/8/2p5/8/3R4/8/8/4K3 w - - 0 1",
      "4k3/8/2p5/3R4/8/8/8/4K3 b - - 1 1",
      Color.White
    )
    assertEquals(whiteStats(rookRun), HangStats(1, 1, 0, 500L, 0, 0L))

    val multiple     = tracker()
    val before       = "4k3/8/2p5/6p1/3Q4/7Q/8/4K3 w - - 0 1"
    val queensHung   = "4k3/8/2p5/3Q2p1/7Q/8/8/4K3 b - - 1 1"
    val bothCaptured = "4k3/8/8/3p4/7p/8/8/4K3 w - - 0 2"
    completeTurn(multiple, before, queensHung, Color.White)
    assertEquals(whiteStats(multiple), HangStats(1, 1, 1, 1800L, 0, 0L))
    completeTurn(multiple, queensHung, bothCaptured, Color.Black)
    assertEquals(whiteStats(multiple), HangStats(1, 1, 1, 1800L, 2, 1800L))
    assertEquals(blackStats(multiple), HangStats(1, 0, 0, 0L, 0, 0L))
  }

  test("fresh tracker history does not punish a prior hang, even when tallies are reused") {
    val whiteHanging = "4k3/8/2p5/3Q4/8/8/8/4K3 b - - 1 1"
    val afterCapture = "4k3/8/8/3p4/8/8/8/4K3 w - - 0 2"

    val fresh = tracker()
    completeTurn(fresh, whiteHanging, afterCapture, Color.Black)
    assertEquals(whiteStats(fresh), HangStats.empty)
    assertEquals(blackStats(fresh), HangStats(1, 0, 0, 0L, 0, 0L))

    val whiteTally = new HangTally
    val blackTally = new HangTally
    val firstGame  = new Tracker(
      constructor.newInstance(whiteTally, blackTally).asInstanceOf[AnyRef],
      whiteTally,
      blackTally
    )
    completeTurn(
      firstGame,
      "4k3/8/2p5/8/3Q4/8/8/4K3 w - - 0 1",
      whiteHanging,
      Color.White
    )
    assertEquals(whiteStats(firstGame), HangStats(1, 1, 1, 900L, 0, 0L))

    val nextGame = Tracker(
      constructor.newInstance(whiteTally, blackTally).asInstanceOf[AnyRef],
      whiteTally,
      blackTally
    )
    completeTurn(nextGame, whiteHanging, afterCapture, Color.Black)
    assertEquals(whiteStats(nextGame), HangStats(1, 1, 1, 900L, 0, 0L))
    assertEquals(blackStats(nextGame), HangStats(1, 0, 0, 0L, 0, 0L))
  }

  test("a piece that remains on the same square is no longer punishable after it stops hanging") {
    val run          = tracker()
    val before       = "k6r/8/8/8/7Q/8/8/4K3 w - - 0 1"
    val queenHung    = "k6r/8/8/7Q/8/8/8/4K3 b - - 1 1"
    val rookMoved    = "k4r2/8/8/7Q/8/8/8/4K3 w - - 2 2"
    val queenSafe    = "k4r2/8/8/7Q/8/8/8/3K4 b - - 3 2"
    val rookCaptures = "k7/8/8/7r/8/8/8/3K4 w - - 0 3"

    completeTurn(run, before, queenHung, Color.White)
    assertEquals(whiteStats(run), HangStats(1, 1, 1, 900L, 0, 0L))
    completeTurn(run, queenHung, rookMoved, Color.Black)
    completeTurn(run, rookMoved, queenSafe, Color.White)
    assertEquals(whiteStats(run), HangStats(2, 1, 1, 900L, 0, 0L))
    // Black completes the two-move rook turn f8-f5-h5.
    completeTurn(run, queenSafe, rookCaptures, Color.Black)
    assertEquals(whiteStats(run), HangStats(2, 1, 1, 900L, 0, 0L))
    assertEquals(blackStats(run), HangStats(2, 0, 0, 0L, 0, 0L))
  }

  test("a repeated queen hang is counted once per turn and punished once") {
    val run        = tracker()
    val initial    = "4k3/8/2p5/8/3Q4/8/8/4K3 w - - 0 1"
    val whiteHung  = "4k3/8/2p5/3Q4/8/8/8/4K3 b - - 1 1"
    val blackQuiet = "5k2/8/2p5/3Q4/8/8/8/4K3 w - - 2 2"
    val whiteQuiet = "5k2/8/2p5/3Q4/8/8/8/3K4 b - - 3 2"
    val captured   = "5k2/8/8/3p4/8/8/8/3K4 w - - 0 3"

    completeTurn(run, initial, whiteHung, Color.White)
    assertEquals(whiteStats(run), HangStats(1, 1, 1, 900L, 0, 0L))
    assertEquals(blackStats(run), HangStats.empty)
    completeTurn(run, whiteHung, blackQuiet, Color.Black)
    assertEquals(blackStats(run), HangStats(1, 1, 0, 100L, 0, 0L))
    completeTurn(run, blackQuiet, whiteQuiet, Color.White)
    assertEquals(whiteStats(run), HangStats(2, 2, 2, 1800L, 0, 0L))
    completeTurn(run, whiteQuiet, captured, Color.Black)
    assertEquals(whiteStats(run), HangStats(2, 2, 2, 1800L, 1, 900L))
    assertEquals(blackStats(run), HangStats(2, 1, 0, 100L, 0, 0L))
  }
