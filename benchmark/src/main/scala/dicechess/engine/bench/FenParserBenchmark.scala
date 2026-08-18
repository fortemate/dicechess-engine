package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*

import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class FenParserBenchmark:

  @Param(Array("initial", "kiwipete", "endgame"))
  var position: String = uninitialized

  var fen: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    fen = BenchmarkPositions.AllPositions(position)

  @Benchmark
  def parseFen(): Either[String, GameState] =
    FenParser.parse(fen)
