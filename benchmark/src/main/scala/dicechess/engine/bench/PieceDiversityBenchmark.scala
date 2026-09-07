package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.PieceDiversity
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** Measures the standalone, unweighted PDI primitives across different material configurations. */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class PieceDiversityBenchmark:

  @Param(Array("initial", "kiwipete", "endgame", "promotion"))
  var position: String = uninitialized

  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position))

  @Benchmark
  def count(): Int = PieceDiversity.count(state, Color.White)

  @Benchmark
  def difference(): Int = PieceDiversity.difference(state, Color.White)
