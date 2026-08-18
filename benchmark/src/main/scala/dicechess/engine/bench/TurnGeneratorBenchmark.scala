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
