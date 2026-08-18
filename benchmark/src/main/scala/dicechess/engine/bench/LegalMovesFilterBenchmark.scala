package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.movegen.*

import scala.compiletime.uninitialized

/** Benchmarks for [[LegalMovesFilter.filterMaximalMoves]].
  *
  * This is the primary optimization target of the Dice Chess engine. The recursive Maximum Micro-moves algorithm must
  * explore all possible micro-move sequences to determine the globally optimal length before filtering legal first
  * moves. Small changes to its logic can have exponential effects on execution time.
  *
  * ## Dice combinations rationale
  *
  * | Dice    | Scenario                                            |
  * |:--------|:----------------------------------------------------|
  * | `1,2,3` | Mixed: Pawn + Knight + Bishop — diverse piece types |
  * | `4,5,6` | Heavy: Rook + Queen + King — high branching factor  |
  * | `1,1,1` | Homogeneous: three Pawn moves — deep pawn chains    |
  * | `6,4,2` | Castling-eligible: King + Rook + Knight             |
  * | `5,5,5` | Three Queen moves — worst-case branching            |
  */
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class LegalMovesFilterBenchmark:

  @Param(Array("initial", "kiwipete", "endgame", "castling", "promotion"))
  var position: String = uninitialized

  /** Representative dice combinations covering diverse Dice Chess scenarios. */
  @Param(Array("1,2,3", "4,5,6", "1,1,1", "6,4,2", "5,5,5"))
  var diceStr: String = uninitialized

  var state: GameState = uninitialized
  var dice: List[Int]  = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position))
    dice = diceStr.split(",").map(_.toInt).toList

  @Benchmark
  def filterMaximalMoves(): List[Move] =
    LegalMovesFilter.filterMaximalMoves(state.withDicePool(dice))
