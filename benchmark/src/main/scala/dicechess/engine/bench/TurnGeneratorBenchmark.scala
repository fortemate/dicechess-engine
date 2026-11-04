// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.search.TurnGenerator

import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class TurnGeneratorBenchmark:

  @Param(Array("initial", "kiwipete", "endgame", "castling", "promotion"))
  var position: String = uninitialized

  @Param(Array("1", "1,2", "1,2,3"))
  var dicePoolStr: String = uninitialized

  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val dice = dicePoolStr.split(",").map(_.toInt).toList
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position)).withDicePool(dice)

  @Benchmark
  def generateAllLegalTurnPaths(): List[List[Move]] =
    TurnGenerator.generateAllLegalTurnPaths(state)

  @Benchmark
  def forEachLegalTurnPath(blackhole: Blackhole): Int =
    var count = 0
    TurnGenerator.forEachLegalTurnPath(state) { (moves, len) =>
      var i = 0
      while i < len do
        blackhole.consume(moves(i))
        i += 1
      count += len
    }
    count

  /** Baseline manual replay: `generateAllLegalTurnPaths` + `foldLeft` + `endTurn()` on non-capturing turns. */
  @Benchmark
  def replayAllLegalTurnPaths(blackhole: Blackhole): Int =
    var count = 0
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    var rem   = paths
    while rem.nonEmpty do
      val path       = rem.head
      val beforeLast = path.init.foldLeft(state)(_.makeMove(_))
      val isWin      = beforeLast.isKingCapture(path.last)
      val afterState = if isWin then beforeLast.makeMove(path.last) else beforeLast.makeMove(path.last).endTurn()
      blackhole.consume(afterState)
      count += 1
      rem = rem.tail
    count

  /** Single-roll canonical visitor (#392): discovers packed paths, then replays with two intermediate board slots
    * without allocating `List[Move]`. Cross-class prefix caching is measured by `SharedClassTraversalBenchmark`.
    */
  @Benchmark
  def forEachLegalTurnState(blackhole: Blackhole): Int =
    TurnGenerator.forEachLegalTurnState(state) { (_, _, afterState, _) =>
      blackhole.consume(afterState)
      true
    }
