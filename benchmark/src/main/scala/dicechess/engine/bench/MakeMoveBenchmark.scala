package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*

import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class MakeMoveBenchmark:

  var initialState: GameState   = uninitialized
  var quietMove: Move           = uninitialized
  var doublePawnMove: Move      = uninitialized
  var quietMicroMove: MicroMove = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    initialState = FenParser.parse(FenParser.InitialPosition).toOption.get
    quietMove = Move(Square('g', 1), Square('f', 3), Move.QuietMove)
    doublePawnMove = Move(Square('e', 2), Square('e', 4), Move.DoublePawnPush)
    quietMicroMove = MicroMove(Square('g', 1), Square('f', 3))

  @Benchmark
  def makeMoveQuiet(): GameState =
    initialState.makeMove(quietMove)

  @Benchmark
  def makeMoveDoublePawn(): GameState =
    initialState.makeMove(doublePawnMove)

  @Benchmark
  def makeMicroMoveQuiet(): GameState =
    initialState.makeMove(quietMicroMove)
