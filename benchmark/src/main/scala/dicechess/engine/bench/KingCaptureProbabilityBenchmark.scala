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

  @Param(Array("initial", "kiwipete", "endgame"))
  var position: String = uninitialized

  @Param(Array("white", "black"))
  var defender: String = uninitialized

  var state: GameState     = uninitialized
  var defenderColor: Color = Color.White

  @Setup(Level.Trial)
  def setup(): Unit =
    val fen = if position == "direct-rook" then DirectRookPosition else BenchmarkPositions.AllPositions(position)
    state = BenchmarkPositions.parse(fen)
    defenderColor = if defender == "white" then Color.White else Color.Black

  @Benchmark
  def kingCaptureProbability(): Double =
    KingCaptureProbability.kingCaptureProbability(state, defenderColor)

  @Benchmark
  def queenCaptureProbability(): Double =
    KingCaptureProbability.queenCaptureProbability(state, defenderColor)
