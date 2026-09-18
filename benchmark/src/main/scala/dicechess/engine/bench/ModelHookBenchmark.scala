// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.*
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.util.Random

/** CPU budgets of the two production model hooks (#78), at the widths they actually see.
  *
  * The hook trades an exact expectation for an estimate, so the number that decides whether it is worth enabling is how
  * much latency it buys. `exactRoot` expands every selected candidate's chance node — the opponent's 56 weighted rolls
  * and all their replies, batched per node — while `collapsedRoot` answers the whole candidate set in one batched call.
  * The gap between them, per candidate limit, is the hook's budget. The model is the throwaway synthetic fixture the
  * tests use — no trained weights live in this repository — so its own compute is negligible. That means this measures
  * the *search work the hook removes*, not a real network's inference time: a heavier model raises both sides, and the
  * exact side by as many times as it has leaves.
  *
  * [[ModelPreRankBatchBenchmark]] measures the other hook's seam.
  *
  * Recorded in `benchmark/BASELINE.md`. Run with `mise run bench:filter ModelHookBenchmark`.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class ModelHookBenchmark:

  /** Candidate limits spanning the width a root decision node is configured with. */
  @Param(Array("1", "4", "8"))
  var candidateLimit: Int = uninitialized

  private var bot: OnnxEvalSearch         = uninitialized
  private var exact: ExpectimaxSearch     = uninitialized
  private var collapsed: ExpectimaxSearch = uninitialized
  private var state: GameState            = uninitialized
  private var random: Random              = uninitialized

  @Setup(Level.Trial)
  def setupTrial(): Unit =
    bot = new OnnxEvalSearch(BenchmarkModelFixture.unpack("model-hook-bench").toString)
    val evalBatch: (Array[GameState], Color) => Array[Int] = (states, color) => bot.onnxEvalBatch(states, color)
    val config                                             = ExpectimaxConfig(candidateLimit = candidateLimit)
    exact = ExpectimaxSearch(evalBatch, config)
    collapsed = ExpectimaxSearch(evalBatch, config, chanceCollapse = Some(ChanceCollapse(evalBatch)))
    // The position has to offer at least as many legal turns as the widest candidate limit, or the wider settings
    // would silently measure the same narrow root as the narrower ones — a scaling claim about a width that was never
    // searched. Three pawns, a knight and a king against a lone king give a few hundred paths with a cheap opponent
    // reply set, which keeps the exact side affordable while the width is real.
    state = BenchmarkPositions.parse("4k3/8/8/8/8/8/PPP5/4KN2 w - - 0 1").withDicePool(List(1, 2, 6))
    val legalTurns = TurnGenerator.generateAllLegalTurnPaths(state).size
    require(
      legalTurns >= candidateLimit,
      s"the benchmark position offers $legalTurns legal turns, fewer than candidateLimit $candidateLimit"
    )

  @Setup(Level.Invocation)
  def setupInvocation(): Unit =
    random = Random(42L)

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    bot.close()

  @Benchmark
  def exactRoot(): Option[ScoredSequence] = exact.findBestMove(state, random)

  @Benchmark
  def collapsedRoot(): Option[ScoredSequence] = collapsed.findBestMove(state, random)
