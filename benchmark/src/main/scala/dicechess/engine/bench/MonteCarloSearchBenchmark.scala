package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.search.{MonteCarloSearch, ScoredSequence}
import scala.util.Random

import scala.compiletime.uninitialized

/** Per-move decision-latency benchmark for the Monte-Carlo bot at its default budget.
  *
  * Cost scales with the number of legal turns for the rolled dice × the rollout budget, so latency is reported per
  * representative position with a fixed roll (pawn / knight / bishop dice). This decides where the bot can run within a
  * given time control. A fresh seeded [[Random]] per invocation keeps runs reproducible.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class MonteCarloSearchBenchmark:

  /** Representative per-move slice of a one-minute game clock (≈ 60s spread over the moves of a game). */
  private val MoveBudgetNanos = 20_000_000L // 20 ms

  @Param(Array("initial", "kiwipete", "endgame"))
  var position: String = uninitialized

  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position)).withDicePool(List(1, 2, 3))

  /** Unbounded per-move latency at the default rollout budget — the cost the time budget must cap. */
  @Benchmark
  def decideMove(): Option[ScoredSequence] =
    MonteCarloSearch.findBestMove(state, MonteCarloSearch.DefaultConfig, new Random(1))

  /** The time-budgeted path: candidate ranking + cap + per-candidate deadline slicing under a fixed move budget. */
  @Benchmark
  def decideMoveTimeBudgeted(): Option[ScoredSequence] =
    MonteCarloSearch.findBestMove(
      state,
      MonteCarloSearch.DefaultConfig,
      System.nanoTime() + MoveBudgetNanos,
      new Random(1)
    )
