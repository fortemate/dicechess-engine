package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.search.Evaluator

import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class EvaluatorBenchmark:

  @Param(Array("initial", "kiwipete", "endgame", "castling", "promotion"))
  var position: String = uninitialized

  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position))

  @Benchmark
  def evaluateMaterialWhite(): Int =
    Evaluator.evaluateMaterial(state, Color.White)

  @Benchmark
  def evaluateMaterialBlack(): Int =
    Evaluator.evaluateMaterial(state, Color.Black)
