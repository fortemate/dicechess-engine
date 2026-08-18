package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.movegen.*

import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class MoveGeneratorBenchmark:

  @Param(Array("initial", "kiwipete", "endgame", "castling", "promotion"))
  var position: String = uninitialized

  @Param(Array("1", "2", "3", "4", "5", "6"))
  var diceRoll: Int = uninitialized

  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position))

  @Benchmark
  def generateMoves(): List[Move] =
    MoveGenerator.generateMoves(state.withDicePool(List(diceRoll)))

  @Benchmark
  def generateAllMoves(): List[Move] =
    MoveGenerator.generateAllMoves(state)

  @Benchmark
  def isSquareAttacked(): Bitboard =
    MoveGenerator.isSquareAttacked(state, Square('e', 4), Color.Black)
