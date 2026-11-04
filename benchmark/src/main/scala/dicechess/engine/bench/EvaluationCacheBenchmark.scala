// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.{EvaluationCache, Evaluator}
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.compiletime.uninitialized

/** Fixed-work comparison for the invocation-local cache (#295, #443).
  *
  * Every operation scores the same synthetic batch of 32 states using the public aggressive evaluator or an inexpensive
  * deterministic counter callback. The legacy method retains the original tuple-key HashMap representation. Repeated
  * inputs are separately allocated equal states; unique inputs differ by full-move counter. State construction is
  * outside measurement, while cache construction, hashing, lookups and retained entries are inside it. A fresh cache is
  * created for every batch, including the zero-hit case. This measures callback memoization, not move replay or search
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

  @Param(Array("aggressive", "cheap"))
  var evaluator: String = uninitialized

  private var states: Array[GameState]            = uninitialized
  private var fullEval: (GameState, Color) => Int = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    require(evaluator == "aggressive" || evaluator == "cheap", s"Unsupported evaluator: $evaluator")
    require(distribution == "repeated" || distribution == "unique", s"Unsupported distribution: $distribution")
    fullEval =
      if evaluator == "cheap" then
        (state, color) => if color.isWhite then state.fullMoveNumber else -state.fullMoveNumber
      else Evaluator.evaluateAggressive
    states = Array.tabulate(32) { i =>
      val counter = if distribution == "repeated" then i % 4 + 1 else i + 1
      BenchmarkPositions.parse(BenchmarkPositions.Kiwipete).copy(fullMoveNumber = counter)
    }
    val cache  = new EvaluationCache(fullEval)
    val legacy = new LegacyCache
    states.foreach { state =>
      val expected = fullEval(state, Color.White)
      require(cache(state, Color.White) == expected && legacy(state) == expected)
    }
    require(cache.evaluatorCalls == legacy.calls && cache.cacheHits == legacy.hits && cache.entryCount == legacy.size)

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

  @Benchmark
  def legacyTupleCache(work: EvaluationCacheWork): Int =
    val cache = new LegacyCache
    var sum   = 0
    var index = 0
    while index < states.length do
      sum += cache(states(index))
      index += 1
    work.record(cache.calls, cache.hits, cache.size)
    sum

  // The pre-#443 implementation, isolated in the existing harness for same-source measurement.
  final private class LegacyCache:
    private val entries              = mutable.HashMap.empty[(GameState, Color), Int]
    var calls                        = 0L
    var hits                         = 0L
    def size: Int                    = entries.size
    def apply(state: GameState): Int =
      val key = (state, Color.White)
      entries.get(key) match
        case Some(value) =>
          hits += 1
          value
        case None =>
          calls += 1
          val value = fullEval(state, Color.White)
          entries.update(key, value)
          value

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
