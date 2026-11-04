// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import dicechess.engine.search.{ActiveDicePartitioner, BudgetedRules, TurnGenerator}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** One fresh chance boundary per operation (#442). The independent variant retains the legacy budgeted visitor,
  * including its complete discovery before replay and its two-slot replay cache. The shared variant creates one
  * invocation-local traversal for all classes. Setup computes independent deterministic discovery/replay counts;
  * neither that probe nor partition-table initialization is measured. Run with `-prof gc` and retain raw output in
  * private qualification evidence. Event counters divided by `batches` give work per boundary, not search strength.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class SharedClassTraversalBenchmark:

  @Param(Array("sparse", "all-types", "castling", "promotion", "en-passant", "capture"))
  var position: String = uninitialized

  private var state: GameState                                    = uninitialized
  private var classes: IArray[ActiveDicePartitioner.WeightedRoll] = uninitialized
  private var independentFull: Array[LegacyWork]                  = uninitialized
  private var independentFirst: Array[LegacyWork]                 = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val fen = position match
      case "sparse"     => "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
      case "all-types"  => "4k3/8/8/8/8/8/P7/RNBQK3 w - - 0 1"
      case "castling"   => "4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1"
      case "promotion"  => "7k/8/4P3/8/8/8/8/K7 w - - 0 1"
      case "en-passant" => "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1"
      case _            => "8/8/8/8/8/8/Q7/k6K w - - 0 1"
    state = BenchmarkPositions.parse(fen)
    classes = ActiveDicePartitioner.partitionsFor(state)
    independentFull = classes.iterator.map(entry => legacyWork(entry.roll.applyToState(state), false)).toArray
    independentFirst = classes.iterator.map(entry => legacyWork(entry.roll.applyToState(state), true)).toArray
    qualify(stopFirst = false)
    qualify(stopFirst = true)

  private def qualify(stopFirst: Boolean): Unit =
    val shared = new TurnGenerator.SharedTraversal(state)
    classes.zipWithIndex.foreach { (entry, index) =>
      val reference = Vector.newBuilder[(List[Move], GameState, Boolean)]
      val actual    = Vector.newBuilder[(List[Move], GameState, Boolean)]
      val budget    = new BudgetedRules.Budget(Long.MaxValue)
      val rolled    = entry.roll.applyToState(state)
      if !rolled.flags.isDicePoolEmpty then
        BudgetedRules.visitTurnStates(rolled, budget) { (moves, length, result, king) =>
          reference += ((moves.take(length).toList, result, king))
          !stopFirst
        }
      val counts = if stopFirst then independentFirst(index) else independentFull(index)
      require(budget.used == counts.generated + counts.examined, "independent work probe must match visitor work")
      shared.visit(entry.roll) { (moves, length, result, king) =>
        actual += ((moves.take(length).toList, result, king))
        !stopFirst
      }
      require(reference.result() == actual.result(), s"traversals must match before measurement: $position")
    }

  @Benchmark
  def independentPerClass(work: SharedClassTraversalWork, blackhole: Blackhole): Int =
    independent(work, blackhole, stopFirst = false)

  @Benchmark
  def independentPerClassStopFirst(work: SharedClassTraversalWork, blackhole: Blackhole): Int =
    independent(work, blackhole, stopFirst = true)

  @Benchmark
  def sharedPrefixes(work: SharedClassTraversalWork, blackhole: Blackhole): Int =
    shared(work, blackhole, stopFirst = false)

  @Benchmark
  def sharedPrefixesStopFirst(work: SharedClassTraversalWork, blackhole: Blackhole): Int =
    shared(work, blackhole, stopFirst = true)

  private def independent(work: SharedClassTraversalWork, blackhole: Blackhole, stopFirst: Boolean): Int =
    val budget                                                   = new BudgetedRules.Budget(Long.MaxValue)
    var results                                                  = 0
    var passes                                                   = 0L
    var generated                                                = 0L
    var examined                                                 = 0L
    var transitions                                              = 0L
    var replayed                                                 = 0L
    var index                                                    = 0
    val visit: (Array[Move], Int, GameState, Boolean) => Boolean = (_, _, result, _) =>
      blackhole.consume(result)
      results += 1
      !stopFirst
    while index < classes.length do
      val rolled = classes(index).roll.applyToState(state)
      if rolled.flags.isDicePoolEmpty then passes += 1
      else
        BudgetedRules.visitTurnStates(rolled, budget)(visit) match
          case BudgetedRules.Outcome.Complete(summary, _) =>
            if summary.emittedPaths == 0 then passes += 1
          case BudgetedRules.Outcome.Incomplete(_) => sys.error("unbounded reference traversal interrupted")
      val counts = if stopFirst then independentFirst(index) else independentFull(index)
      generated += counts.generated
      examined += counts.examined
      transitions += counts.transitions
      replayed += counts.replayed
      index += 1
    work.record(generated, examined, transitions, replayed, results, passes)
    results

  private def shared(work: SharedClassTraversalWork, blackhole: Blackhole, stopFirst: Boolean): Int =
    val traversal = new TurnGenerator.SharedTraversal(state, Some(new BudgetedRules.Budget(Long.MaxValue)))
    var results   = 0
    var passes    = 0L
    var index     = 0
    val visit: (Array[Move], Int, GameState, Boolean) => Boolean = (_, _, result, _) =>
      blackhole.consume(result)
      results += 1
      !stopFirst
    while index < classes.length do
      traversal.visit(classes(index).roll)(visit) match
        case BudgetedRules.Outcome.Complete(summary, _) =>
          if summary.emittedPaths == 0 then passes += 1
        case BudgetedRules.Outcome.Incomplete(_) => sys.error("unbounded shared traversal interrupted")
      index += 1
    val counts = traversal.work
    work.record(counts.generated, counts.examined, counts.transitions, counts.replayed, results, passes)
    results

  private case class LegacyWork(generated: Long, examined: Long, transitions: Long, replayed: Long)

  /** Independent accounting probe for the unchanged BudgetedRules visitor, outside measured operations. */
  private def legacyWork(rolled: GameState, stopFirst: Boolean): LegacyWork =
    var generated                                              = 0L
    var examined                                               = 0L
    var transitions                                            = 0L
    def discover(current: GameState, eligible: Bitboard): Unit =
      generated += 1
      MoveGenerator.generateMoves(current, eligible).foreach { move =>
        examined += 1
        if !current.isKingCapture(move) then
          val remaining = current.diceAfter(move)
          if remaining.isValid then
            transitions += 1
            val nextEligible =
              if move.isEnPassant || eligible.contains(move.fromSquare) then eligible.remove(move.fromSquare)
              else eligible
            discover(current.makeMove(move).withDiceSlotsOf(remaining), nextEligible)
      }
    if !rolled.flags.isDicePoolEmpty then discover(rolled, MoveGenerator.computeEligibleEpPawns(rolled))

    val all          = TurnGenerator.generateAllLegalTurnPaths(rolled)
    val paths        = if stopFirst then all.take(1) else all
    var prevFirst    = -1
    var prevSecond   = -1
    var previousKing = true
    var replayed     = 0L
    paths.foreach { path =>
      val beforeLast = path.init.foldLeft(rolled)(_.makeMove(_))
      val king       = beforeLast.isKingCapture(path.last)
      if previousKing && !king then
        prevFirst = -1
        prevSecond = -1
      val first = path.head.toInt
      if first != prevFirst then
        replayed += 1
        prevFirst = first
        prevSecond = -1
      if path.length >= 2 then
        val second = path(1).toInt
        if second != prevSecond then
          replayed += 1
          prevSecond = second
      if path.length == 3 then replayed += 1
      previousKing = king
    }
    LegacyWork(generated, examined, transitions + replayed, replayed)

/** Counts are accumulated per measurement iteration. Normalize by `batches` for deterministic work per boundary. */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class SharedClassTraversalWork:
  private var batchTotal      = 0L
  private var generatedTotal  = 0L
  private var examinedTotal   = 0L
  private var transitionTotal = 0L
  private var replayTotal     = 0L
  private var resultTotal     = 0L
  private var passTotal       = 0L

  def batches: Long      = batchTotal
  def generated: Long    = generatedTotal
  def examined: Long     = examinedTotal
  def transitions: Long  = transitionTotal
  def replayed: Long     = replayTotal
  def results: Long      = resultTotal
  def forcedPasses: Long = passTotal

  @Setup(Level.Iteration)
  def reset(): Unit =
    batchTotal = 0L
    generatedTotal = 0L
    examinedTotal = 0L
    transitionTotal = 0L
    replayTotal = 0L
    resultTotal = 0L
    passTotal = 0L

  def record(generated: Long, examined: Long, transitions: Long, replayed: Long, results: Int, passes: Long): Unit =
    batchTotal += 1
    generatedTotal += generated
    examinedTotal += examined
    transitionTotal += transitions
    replayTotal += replayed
    resultTotal += results
    passTotal += passes
