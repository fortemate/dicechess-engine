// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.*
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** A frozen batch of nested chance boundaries (#456). Setup takes the first four canonical king-die successors of a
  * synthetic outer position; every measured batch creates independent traversal and exact evaluation caches for those
  * boundaries. All classes and maximal legal replies are consumed in canonical order, with no pruning or random work.
  * Material and constant callbacks distinguish valuation from traversal/storage cost. This models the nested expansion
  * stage, not a complete selective search. Compare the same compiled harness against the published and candidate jars
  * on separate classpaths. Run unprofiled forks separately from `-prof gc`; normalize event totals by `batches`.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(4)
@State(Scope.Thread)
class NestedChanceCacheBenchmark:
  @Param(Array("sparse", "middlegame", "transpositions", "promotion-ep"))
  var position: String = uninitialized

  @Param(Array("material", "constant"))
  var evaluator: String = uninitialized

  private var states: Array[GameState]        = uninitialized
  private var eval: (GameState, Color) => Int = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val fen = position match
      case "sparse"         => "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
      case "middlegame"     => "r3k3/1p2n3/8/3p4/3P4/8/1P2N3/R3K3 w - - 0 9"
      case "transpositions" => "4k3/p6p/8/8/8/8/P6P/4K3 w - - 0 1"
      case "promotion-ep"   => "4k3/4P3/8/8/3Pp3/8/8/4K3 w - d3 0 1"
      case _                => sys.error(s"Unsupported position: $position")
    require(evaluator == "material" || evaluator == "constant")
    eval = if evaluator == "constant" then (_, _) => 0 else Evaluator.evaluateMaterial
    val outer = BenchmarkPositions.parse(fen).withDicePool(List(6))
    states = TurnGenerator
      .generateAllLegalTurnPaths(outer)
      .take(4)
      .map(_.foldLeft(outer)(_.makeMove(_)).endTurn())
      .toArray
    require(states.nonEmpty)
    // Force partition initialization outside measurement and verify against uncached exhaustive scoring.
    states.foreach(s => ActiveDicePartitioner.partitionsFor(s))
    val actual    = batch(new NestedChanceCacheWork)
    val reference = states.map { state =>
      val classes = ActiveDicePartitioner.partitionsFor(state)
      classes.iterator.map { entry =>
        val rolled = entry.roll.applyToState(state)
        val paths  = TurnGenerator.generateAllLegalTurnPaths(rolled)
        val scores = paths.map { path =>
          val before = path.init.foldLeft(rolled)(_.makeMove(_))
          if before.isKingCapture(path.last) then ExpectimaxSearch.utilityBounds(state.activeColor).terminalWin
          else eval(before.makeMove(path.last).endTurn(), state.activeColor).toDouble
        }
        val best = if scores.isEmpty then eval(rolled.endTurn(), state.activeColor).toDouble else scores.max
        best * entry.weight / 216.0
      }.sum
    }.sum
    require(math.abs(actual - reference) < 0.000001, "nested batch must match the independent path oracle")

  @Benchmark
  def batch(work: NestedChanceCacheWork): Double =
    var total = 0.0
    states.foreach { state =>
      val perspective = state.activeColor
      val bounds      = ExpectimaxSearch.utilityBounds(perspective)
      val cache       = new EvaluationCache(eval)
      val traversal   = new TurnGenerator.SharedTraversal(state)
      val classes     = ActiveDicePartitioner.partitionsFor(state)
      val policy      = new ChanceNode.Policy:
        def lowestValue: Double                                                                  = bounds.minNonTerminal
        def highestValue: Double                                                                 = bounds.terminalWin
        override def probingEnabled: Boolean                                                     = false
        def decide(rolled: GameState, index: Int, alpha: Double, beta: Double): ChanceNode.Value =
          var best = Double.NegativeInfinity
          traversal.visit(classes(index).roll) { (_, _, result, king) =>
            val value = if king then bounds.terminalWin else cache(result, perspective).toDouble
            best = math.max(best, value)
            true
          } match
            case BudgetedRules.Outcome.Complete(summary, _) =>
              if summary.emittedPaths == 0 then best = cache(rolled.endTurn(), perspective).toDouble
            case BudgetedRules.Outcome.Incomplete(_) => sys.error("unbounded benchmark interrupted")
          ChanceNode.Value.exact(best)
      val result = ChanceNode.expand(
        state,
        bounds,
        SearchControl.Unbounded,
        Double.NegativeInfinity,
        Double.PositiveInfinity,
        policy
      )
      result.outcome match
        case SearchOutcome.Exact(value, _) => total += value
        case _                             => sys.error("unbounded benchmark must complete exactly")
      work.record(cache, traversal.work)
    }
    work.completeBatch()
    total

/** Exact events from the engine; lookup comparisons and growth require a separate instrumented diagnostic run. */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class NestedChanceCacheWork:
  private var batchTotal      = 0L
  private var calls           = 0L
  private var hits            = 0L
  private var entries         = 0L
  private var generated       = 0L
  private var examined        = 0L
  private var transitions     = 0L
  private var replayed        = 0L
  private var emitted         = 0L
  def batches: Long           = batchTotal
  def evaluatorCalls: Long    = calls
  def cacheHits: Long         = hits
  def retainedEntries: Long   = entries
  def geometryCalls: Long     = generated
  def classEdges: Long        = examined
  def stateTransitions: Long  = transitions
  def replayTransitions: Long = replayed
  def emittedStates: Long     = emitted
  @Setup(Level.Iteration)
  def reset(): Unit =
    batchTotal = 0L
    calls = 0L
    hits = 0L
    entries = 0L
    generated = 0L
    examined = 0L
    transitions = 0L
    replayed = 0L
    emitted = 0L
  def completeBatch(): Unit                                                   = batchTotal += 1
  def record(cache: EvaluationCache, work: TurnGenerator.TraversalWork): Unit =
    calls += cache.evaluatorCalls
    hits += cache.cacheHits
    entries += cache.entryCount
    generated += work.generated
    examined += work.examined
    transitions += work.transitions
    replayed += work.replayed
    emitted += work.emitted
