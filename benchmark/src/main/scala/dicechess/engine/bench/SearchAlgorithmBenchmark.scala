package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.search.{GreedySearch, RandomSearch, ScoredSequence}
import java.util.Random as JavaRandom
import scala.util.Random as ScalaRandom

import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class SearchAlgorithmBenchmark:

  @Param(Array("initial", "kiwipete", "endgame", "castling", "promotion"))
  var position: String = uninitialized

  @Param(Array("1", "1,2", "1,2,3"))
  var dicePoolStr: String = uninitialized

  var state: GameState          = uninitialized
  var seededRandom: ScalaRandom = uninitialized

  @Setup(Level.Trial)
  def setupTrial(): Unit =
    val dice = dicePoolStr.split(",").map(_.toInt).toList
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position)).withDicePool(dice)

  @Setup(Level.Iteration)
  def setupIteration(): Unit =
    seededRandom = new ScalaRandom(new JavaRandom(42L))

  @Benchmark
  def greedySearch(): Option[ScoredSequence] =
    GreedySearch.findBestMove(state)

  @Benchmark
  def randomSearch(): Option[ScoredSequence] =
    RandomSearch.findBestMove(state, seededRandom)
