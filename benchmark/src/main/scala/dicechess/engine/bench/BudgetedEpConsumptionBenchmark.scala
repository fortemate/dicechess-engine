// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.BudgetedRules.*
import dicechess.engine.search.BudgetedRules
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** Public synthetic fixtures for #446. Use `-prof gc` for allocation and `workUnits` for admitted traversal work. */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class BudgetedEpConsumptionBenchmark:
  @Param(Array("white", "black", "late"))
  var fixture: String  = "white"
  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val fen = fixture match
      case "white" => "4k3/8/8/3pP3/4P3/8/8/4K3 w - d6 0 1 PPP"
      case "black" => "3k4/8/8/3p4/3pP3/8/8/3K4 b - e3 0 1 PPP"
      case _       => "4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RRP"
    state = BenchmarkPositions.parse(fen)

  private def record[A](outcome: Outcome[A], work: BudgetedEpWork): Outcome[A] =
    work.queries += 1
    outcome match
      case Outcome.Complete(_, used) => work.workUnits += used
      case Outcome.Incomplete(used)  => work.workUnits += used
    outcome

  @Benchmark
  def turnPaths(work: BudgetedEpWork): Outcome[List[List[Move]]] =
    record(BudgetedRules.turnPaths(state, new Budget(Long.MaxValue)), work)

  @Benchmark
  def visitTurnStates(work: BudgetedEpWork): Outcome[TurnVisitSummary] =
    record(BudgetedRules.visitTurnStates(state, new Budget(Long.MaxValue))((_, _, _, _) => true), work)

  @Benchmark
  def foldTurnStates(work: BudgetedEpWork): Outcome[TurnFoldResult[Int]] =
    record(BudgetedRules.foldTurnStates(state, 0, new Budget(Long.MaxValue))((n, _, _, _) => (n + 1, true)), work)

  @Benchmark
  def capturePath(work: BudgetedEpWork): Outcome[Option[List[Move]]] =
    record(BudgetedRules.kingCapturePath(state, new Budget(Long.MaxValue)), work)

  @Benchmark
  def captureRolls(work: BudgetedEpWork): Outcome[Int] =
    record(BudgetedRules.kingCaptureRolls(state, state.activeColor.opponent, new Budget(Long.MaxValue)), work)

@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class BudgetedEpWork:
  var workUnits: Long = 0L
  var queries: Long   = 0L
