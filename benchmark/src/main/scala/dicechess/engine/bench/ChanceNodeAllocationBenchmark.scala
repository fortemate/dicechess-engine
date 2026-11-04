// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** Isolates chance-node construction with constant exact decision work (#444). The legacy-none policy keeps its
  * existing probe callback; no-probe explicitly opts out; active supplies sound MIN probes. Callback telemetry counts
  * distinct rolled-state construction (eager arrays or lazy first decisions), not searches or playing strength.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class ChanceNodeAllocationBenchmark:
  @Param(Array("sparse", "all-types"))
  var position: String = uninitialized

  @Param(Array("legacy-none", "no-probe", "active"))
  var policyMode: String = uninitialized

  @Param(Array("open", "full", "cut", "cancelled"))
  var scenario: String = uninitialized

  private var state: GameState = uninitialized
  private val bounds           = ExpectimaxSearch.utilityBounds(Color.Black)
  private val probeValue       = ChanceNode.Value(SearchOutcome.UpperBound(5.0, 0L))

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(if position == "sparse" then "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
    else "4k3/8/8/8/8/8/P7/RNBQK3 w - - 0 1")
    val classes = ActiveDicePartitioner.partitionsFor(state).length
    val result  = expand(new MeasuredPolicy)
    require(result.classes == classes && !result.lossTainted)
    if scenario == "cancelled" then require(result.outcome.isIncomplete && result.classesResolved == 0)
    else if scenario == "cut" then
      val upper = if policyMode == "active" then 5.0 else 10.0
      require(result.outcome == SearchOutcome.UpperBound(upper, 0L) && result.classesResolved == 0)
    else require(result.outcome == SearchOutcome.Exact(2.0, 0L) && result.classesResolved == classes)

  private def expand(policy: MeasuredPolicy): ChanceNode.Result =
    val control = if scenario == "cancelled" then SearchControl.untilDeadline(1L, () => 2L) else SearchControl.Unbounded
    val alpha   = if scenario == "open" then Double.NegativeInfinity else if scenario == "cut" then 11.0 else 0.0
    ChanceNode.expand(state, bounds, control, alpha, Double.PositiveInfinity, policy)

  @Benchmark
  def construction(work: ChanceNodeAllocationWork, blackhole: Blackhole): Unit =
    val policy = new MeasuredPolicy
    val result = expand(policy)
    work.record(policy.rolledStates, result.classesResolved, result.classes, policy.probes, policy.decisions)
    blackhole.consume(result)

  final private class MeasuredPolicy extends ChanceNode.Policy:
    var rolledStates                                                               = 0L
    var probes                                                                     = 0L
    var decisions                                                                  = 0L
    private var eager                                                              = false
    override def probingEnabled: Boolean                                           = policyMode != "no-probe"
    def lowestValue: Double                                                        = 0.0
    def highestValue: Double                                                       = 10.0
    override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]] =
      probes += 1
      rolledStates += rolled.length
      eager = true
      if policyMode == "active" then Some(Array.fill(rolled.length)(probeValue)) else None
    def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
      if !eager then rolledStates += 1
      decisions += 1
      ChanceNode.Value.exact(2.0)

/** Normalize event totals by batches to compare identical resolved classes and policy work. */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class ChanceNodeAllocationWork:
  private var batchTotal    = 0L
  private var rolledTotal   = 0L
  private var resolvedTotal = 0L
  private var classTotal    = 0L
  private var probeTotal    = 0L
  private var decisionTotal = 0L
  def batches: Long         = batchTotal
  def rolledStates: Long    = rolledTotal
  def resolvedClasses: Long = resolvedTotal
  def classes: Long         = classTotal
  def probes: Long          = probeTotal
  def decisions: Long       = decisionTotal
  @Setup(Level.Iteration)
  def reset(): Unit =
    batchTotal = 0L
    rolledTotal = 0L
    resolvedTotal = 0L
    classTotal = 0L
    probeTotal = 0L
    decisionTotal = 0L
  def record(rolled: Long, resolved: Int, count: Int, probes: Long, decisions: Long): Unit =
    batchTotal += 1
    rolledTotal += rolled
    resolvedTotal += resolved
    classTotal += count
    probeTotal += probes
    decisionTotal += decisions
