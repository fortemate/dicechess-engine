// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.{EvaluationCache, Evaluator}
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** Fixed-work comparison for the invocation-local cache (#295).
  *
  * Every operation scores the same synthetic batch of 32 states using the public aggressive evaluator. Repeated inputs
  * are separately allocated equal states; unique inputs differ by full-move counter. State construction is outside
  * measurement, while cache construction, hashing, lookups and retained entries are inside it. A fresh cache is created
  * for every batch, including the zero-hit case. This measures callback memoization, not move replay or search
  * strength. Run with `-prof gc` to include allocated bytes per batch, and divide the auxiliary evaluator-call, hit and
  * entry totals by the batch total to recover work per invocation.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class EvaluationCacheBenchmark:

  @Param(Array("repeated", "unique"))
  var distribution: String = uninitialized

  private var states: Array[GameState]            = uninitialized
  private val fullEval: (GameState, Color) => Int = Evaluator.evaluateAggressive

  @Setup(Level.Trial)
  def setup(): Unit =
    states = Array.tabulate(32) { i =>
      val counter = if distribution == "repeated" then i % 4 + 1 else i + 1
      BenchmarkPositions.parse(BenchmarkPositions.Kiwipete).copy(fullMoveNumber = counter)
    }

  @Benchmark
  def uncached(work: EvaluationCacheWork): Int =
    var sum   = 0
    var calls = 0L
    var i     = 0
    while i < states.length do
      sum += fullEval(states(i), Color.White)
      calls += 1
      i += 1
    work.record(calls, 0L, 0)
    sum

  @Benchmark
  def cached(work: EvaluationCacheWork): Int =
    val cache = new EvaluationCache(fullEval)
    var sum   = 0
    var i     = 0
    while i < states.length do
      sum += cache(states(i), Color.White)
      i += 1
    work.record(cache.evaluatorCalls, cache.cacheHits, cache.entryCount)
    sum

/** Event totals are reported separately from elapsed cost; their ratios to `batches` describe work per invocation. */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class EvaluationCacheWork:
  private var batchTotal = 0L
  private var callTotal  = 0L
  private var hitTotal   = 0L
  private var entryTotal = 0L

  def batches: Long        = batchTotal
  def evaluatorCalls: Long = callTotal
  def cacheHits: Long      = hitTotal
  def entries: Long        = entryTotal

  @Setup(Level.Iteration)
  def reset(): Unit =
    batchTotal = 0L
    callTotal = 0L
    hitTotal = 0L
    entryTotal = 0L

  def record(calls: Long, hits: Long, entryCount: Int): Unit =
    batchTotal += 1
    callTotal += calls
    hitTotal += hits
    entryTotal += entryCount
