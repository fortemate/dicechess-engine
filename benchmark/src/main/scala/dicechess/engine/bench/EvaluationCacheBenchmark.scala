// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.{EvaluationCache, Evaluator}
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.compiletime.uninitialized

/** Fixed-work comparison for the invocation-local cache (#295, #443, #459).
  *
  * Measures evaluation memoization across different distributions:
  *   - **repeated**: repeated identical states (hits on both exact and valuation keys)
  *   - **transposition**: transposition-rich states sharing board configuration but varying move counters (misses on
  *     exact cache, hits on counter-invariant valuation keys)
  *   - **unique**: distinct board configurations (zero hits on both caches, measuring miss overhead and allocation)
  *
  * Compares:
  *   - `uncached`: direct evaluation without cache
  *   - `cached`: exact-identity linear probing cache (engine default)
  *   - `cachedValuationKey`: opt-in valuation key cache (#459)
  *   - `legacyTupleCache`: pre-#443 tuple HashMap cache (0.18.1 baseline representation)
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class EvaluationCacheBenchmark:

  @Param(Array("repeated", "transposition", "unique"))
  var distribution: String = uninitialized

  @Param(Array("aggressive", "cheap", "clock"))
  var evaluator: String = uninitialized

  private var states: Array[GameState]            = uninitialized
  private var fullEval: (GameState, Color) => Int = uninitialized

  final case class BenchValuationKey(
      white: Long,
      black: Long,
      active: Color,
      perspective: Color,
      counter: Int
  ) derives CanEqual

  private inline def isCounterSensitive: Boolean = evaluator == "clock"

  def keyFor(state: GameState, color: Color): BenchValuationKey =
    BenchValuationKey(
      state.whitePieces.value,
      state.blackPieces.value,
      state.activeColor,
      color,
      if isCounterSensitive then state.fullMoveNumber else 0
    )

  private def createEvaluator(): (GameState, Color) => Int =
    require(
      evaluator == "aggressive" || evaluator == "cheap" || evaluator == "clock",
      s"Unsupported evaluator: $evaluator"
    )
    evaluator match
      case "aggressive" => Evaluator.evaluateAggressive
      case "cheap"      =>
        // Inexpensive, deterministic counter-invariant material callback
        (state, color) =>
          val myPieces  = if color.isWhite then state.whitePieces else state.blackPieces
          val oppPieces = if color.isWhite then state.blackPieces else state.whitePieces
          myPieces.count - oppPieces.count
      case "clock" =>
        // Meaningful negative control: counter-sensitive callback returning move counter
        (state, color) => if color.isWhite then state.fullMoveNumber else -state.fullMoveNumber
      case _ => Evaluator.evaluateAggressive

  private def buildState(base: GameState, sq: Square, counter: Int): GameState =
    val knightBit = Bitboard.fromSquare(sq)
    val mb        = base.mailbox.toArray
    mb(sq.index) = Piece(Color.White, PieceType.Knight)
    base.copy(
      whitePieces = base.whitePieces | knightBit,
      knights = base.knights | knightBit,
      mailbox = Mailbox.fromBuilder(mb),
      fullMoveNumber = counter
    )

  private def createStates(base: GameState): Array[GameState] =
    require(
      distribution == "repeated" || distribution == "transposition" || distribution == "unique",
      s"Unsupported distribution: $distribution"
    )
    val occupied = base.whitePieces | base.blackPieces
    distribution match
      case "repeated" =>
        Array.tabulate(32)(i => base.copy(fullMoveNumber = (i % 4) + 1))
      case "transposition" =>
        val emptySquares = (0 until 64).map(Square.fromIndex).filter(sq => !occupied.contains(sq)).take(4)
        Array.tabulate(32)(i => buildState(base, emptySquares(i / 8), (i % 8) + 1))
      case "unique" =>
        val emptySquares = (0 until 64).map(Square.fromIndex).filter(sq => !occupied.contains(sq)).take(32)
        Array.tabulate(32)(i => buildState(base, emptySquares(i), i + 1))
      case _ => Array.empty[GameState]

  private def validateCaches(): Unit =
    val exactCache  = new EvaluationCache(fullEval)
    val keyedCache  = EvaluationCache.withValuationKey(fullEval)(keyFor)
    val legacyCache = new LegacyCache
    states.foreach { state =>
      val expected  = fullEval(state, Color.White)
      val exactVal  = exactCache(state, Color.White)
      val keyedVal  = keyedCache(state, Color.White)
      val legacyVal = legacyCache(state)
      require(exactVal == expected, s"Exact cache mismatch for $state: got $exactVal, expected $expected")
      require(keyedVal == expected, s"Keyed cache mismatch for $state: got $keyedVal, expected $expected")
      require(legacyVal == expected, s"Legacy cache mismatch for $state: got $legacyVal, expected $expected")
    }
    if evaluator != "clock" then
      distribution match
        case "repeated" =>
          require(exactCache.cacheHits == 28 && keyedCache.cacheHits == 31, "Hit count mismatch for repeated")
        case "transposition" =>
          require(exactCache.cacheHits == 0 && keyedCache.cacheHits == 28, "Hit count mismatch for transposition")
        case "unique" =>
          require(exactCache.cacheHits == 0 && keyedCache.cacheHits == 0, "Hit count mismatch for unique")
        case _ => ()

  @Setup(Level.Trial)
  def setup(): Unit =
    fullEval = createEvaluator()
    states = createStates(BenchmarkPositions.parse(BenchmarkPositions.Kiwipete))
    validateCaches()

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
  def cachedValuationKey(work: EvaluationCacheWork): Int =
    val cache = EvaluationCache.withValuationKey(fullEval)(keyFor)
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
