// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.BudgetedRules
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class BudgetedRulesBenchmark:
  @Param(Array("0", "256", "65536"))
  var workLimit: Long  = 0L
  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions("endgame")).withDicePool(List(1, 6))

  @Benchmark
  def turnPaths(): BudgetedRules.Outcome[List[List[Move]]] =
    BudgetedRules.turnPaths(state, new BudgetedRules.Budget(workLimit))

  @Benchmark
  def captureRolls(): BudgetedRules.Outcome[Int] =
    BudgetedRules.kingCaptureRolls(state, Color.Black, new BudgetedRules.Budget(workLimit))

  @Benchmark
  def capturePath(): BudgetedRules.Outcome[Option[List[Move]]] =
    BudgetedRules.kingCapturePath(state, new BudgetedRules.Budget(workLimit))
