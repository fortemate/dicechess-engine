// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.{ActiveDicePartitioner, DiceRolls, TurnGenerator}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** Separates cached partition lookup from its effect on exhaustive legal-turn traversal (#387). No private evaluation
  * or search configuration is needed to reproduce these measurements.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class ActiveDicePartitionerBenchmark:

  @Param(Array("initial", "lone-king", "promotion"))
  var position: String = uninitialized
  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val fen = position match
      case "initial"   => FenParser.InitialPosition
      case "lone-king" => "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
      case _           => "k7/4P3/8/8/8/8/8/4K3 w - - 0 1"
    state = BenchmarkPositions.parse(fen)
    ActiveDicePartitioner.partitionsFor(state)
    ()

  /** Lookup and packed-entry access without constructing dice pools or generating moves. */
  @Benchmark
  def lookup(): Int =
    val classes = ActiveDicePartitioner.partitionsFor(state)
    classes(0).weight + classes.length

  /** Reference traversal of all 56 weighted rolls. */
  @Benchmark
  def exhaustive(blackhole: Blackhole): Int =
    var mass = 0
    DiceRolls.weighted.foreach { (dice, weight) =>
      TurnGenerator.forEachLegalTurnPath(state.withDicePool(dice)) { (moves, length) =>
        blackhole.consume(moves(length - 1))
        mass += weight
      }
    }
    mass

  /** Same probability-weighted paths, enumerating each equivalence class once. */
  @Benchmark
  def grouped(blackhole: Blackhole): Int =
    var mass = 0
    ActiveDicePartitioner.partitionsFor(state).foreach { entry =>
      TurnGenerator.forEachLegalTurnPath(entry.roll.applyToState(state)) { (moves, length) =>
        blackhole.consume(moves(length - 1))
        mass += entry.weight
      }
    }
    mass
