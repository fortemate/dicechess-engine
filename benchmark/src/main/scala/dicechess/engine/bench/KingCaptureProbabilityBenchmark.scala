// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.search.KingCaptureProbability

import scala.compiletime.uninitialized

/** Micro-benchmarks for [[KingCaptureProbability]] — the per-ply hot path of the Monte-Carlo bot.
  *
  * `MonteCarloEquity.singleRollout` calls [[KingCaptureProbability.kingCaptureProbability]] once on every ply of every
  * rollout, so KCP throughput is roughly proportional to the bot's playing strength under a fixed time budget. This
  * benchmark establishes the baseline for KCP optimizations; measure on defended-king mid/endgames (not a hanging king)
  * so a direct-capture fast-path cannot flatter the numbers.
  *
  * The opt-in `direct-rook` position pairs a defended king under direct rook attack with an opposite-side no-hit
  * control. Run it with `-p position=direct-rook` and report it separately from the conservative default corpus.
  *
  * Returning the primitive `Double` is enough for JMH to consume the result and prevent dead-code elimination.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class KingCaptureProbabilityBenchmark:

  private val DirectRookPosition = "8/2p5/3p4/KP5r/1R5k/8/4P1P1/8 w - - 0 1"
  private val EnPassantPosition  = "4k3/8/8/3pP3/8/8/4Q3/4R1K1 w - d6 0 1"
  private val WhiteKingUnblocks  = "4k3/8/8/8/8/3nK3/4P3/4R3 w - - 0 1"
  private val BlackKingUnblocks  = "4r3/4p3/3Nk3/8/8/8/8/4K3 b - - 0 1"
  private val RootLastRoll       = "8/8/8/8/b7/3k4/4pn2/r2K3q b - - 0 1"

  @Param(Array("initial", "kiwipete", "endgame"))
  var position: String = uninitialized

  @Param(Array("white", "black"))
  var defender: String = uninitialized

  var state: GameState     = uninitialized
  var defenderColor: Color = Color.White

  @Setup(Level.Trial)
  def setup(): Unit =
    val fen = position match
      case "direct-rook"         => DirectRookPosition
      case "en-passant"          => EnPassantPosition
      case "king-unblocks-white" => WhiteKingUnblocks
      case "king-unblocks-black" => BlackKingUnblocks
      case "root-last-roll"      => RootLastRoll
      case _                     => BenchmarkPositions.AllPositions(position)
    state = BenchmarkPositions.parse(fen)
    defenderColor = if defender == "white" then Color.White else Color.Black

  @Benchmark
  def kingCaptureProbability(): Double =
    KingCaptureProbability.kingCaptureProbability(state, defenderColor)

  @Benchmark
  def queenCaptureProbability(): Double =
    KingCaptureProbability.queenCaptureProbability(state, defenderColor)
