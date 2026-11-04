// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.{BudgetedRules, CompactRoll, TurnGenerator}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** One roll per operation (#442). The single-roll API retains two replay boards; the explicitly shared traversal
  * retains each requested ordered prefix. Both return identical ordered results, but a terminal first-result stop can
  * avoid discovery only in the shared traversal. Consume every result to keep state construction observable.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class SingleRollTraversalBenchmark:
  @Param(Array("queens", "castling", "capture"))
  var position: String = uninitialized

  private var state: GameState  = uninitialized
  private var roll: CompactRoll = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val (fen, dice) = position match
      case "queens"   => ("7k/8/8/8/8/8/Q7/6K1 w - - 0 1", List(5, 5, 5))
      case "castling" => ("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1", List(4, 6, 6))
      case _          => ("8/8/8/8/8/8/Q7/k6K w - - 0 1", List(5, 5, 5))
    state = BenchmarkPositions.parse(fen).withDicePool(dice)
    roll = CompactRoll.fromSortedList(dice)
    val canonical = Vector.newBuilder[(List[Move], GameState, Boolean)]
    val shared    = Vector.newBuilder[(List[Move], GameState, Boolean)]
    TurnGenerator.forEachLegalTurnState(state) { (moves, length, result, king) =>
      canonical += ((moves.take(length).toList, result, king))
      true
    }
    new TurnGenerator.SharedTraversal(state).visit(roll) { (moves, length, result, king) =>
      shared += ((moves.take(length).toList, result, king))
      true
    }
    require(canonical.result() == shared.result(), "single-roll visitors must agree before measurement")

  @Benchmark
  def canonicalFull(blackhole: Blackhole): Int = canonical(blackhole, stopFirst = false)

  @Benchmark
  def canonicalStopFirst(blackhole: Blackhole): Int = canonical(blackhole, stopFirst = true)

  @Benchmark
  def sharedFull(blackhole: Blackhole): Int = shared(blackhole, stopFirst = false)

  @Benchmark
  def sharedStopFirst(blackhole: Blackhole): Int = shared(blackhole, stopFirst = true)

  private def canonical(blackhole: Blackhole, stopFirst: Boolean): Int =
    TurnGenerator.forEachLegalTurnState(state) { (_, _, result, _) =>
      blackhole.consume(result)
      !stopFirst
    }

  private def shared(blackhole: Blackhole, stopFirst: Boolean): Int =
    new TurnGenerator.SharedTraversal(state).visit(roll) { (_, _, result, _) =>
      blackhole.consume(result)
      !stopFirst
    } match
      case BudgetedRules.Outcome.Complete(summary, _) => summary.emittedPaths
      case BudgetedRules.Outcome.Incomplete(_)        => sys.error("unbounded shared traversal interrupted")
