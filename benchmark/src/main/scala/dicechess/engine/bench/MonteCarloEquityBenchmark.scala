package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.search.{MonteCarloConfig, MonteCarloEquity}
import scala.util.Random

import scala.compiletime.uninitialized

/** Micro-benchmarks for [[MonteCarloEquity]].
  *
  * [[fixedBudget]] measures the cost of a fixed-rollout estimate (divide rollouts by the average time for rollouts/sec,
  * which decides whether this runs server-side, cached, or in WASM). [[targetCi]] measures the time to reach a target
  * confidence-interval width via adaptive stopping.
  *
  * A fresh seeded [[Random]] is created per invocation so runs are reproducible and comparable across positions.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class MonteCarloEquityBenchmark:

  @Param(Array("initial", "kiwipete", "endgame"))
  var position: String = uninitialized

  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position))

  @Benchmark
  def fixedBudget(): EquityHolder =
    EquityHolder(MonteCarloEquity.estimate(state, MonteCarloConfig(rollouts = 200, maxPlies = 200), new Random(1)))

  @Benchmark
  def targetCi(): EquityHolder =
    EquityHolder(
      MonteCarloEquity.estimate(
        state,
        MonteCarloConfig(rollouts = 5000, maxPlies = 200, targetError = 0.01, minRollouts = 64),
        new Random(1)
      )
    )

/** Wraps the estimate so JMH cannot dead-code-eliminate the computation. */
final case class EquityHolder(rollouts: Int)

object EquityHolder:
  def apply(est: dicechess.engine.search.EquityEstimate): EquityHolder = new EquityHolder(est.rollouts)
