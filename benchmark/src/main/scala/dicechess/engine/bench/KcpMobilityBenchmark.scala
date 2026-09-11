package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.{KcpFeatures, KcpMobilityFeatures, KcpMobilityPawnsFeatures, PassedPawns, PieceMobility}
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** Micro-benchmarks comparing [[KcpFeatures]] against the extended [[KcpMobilityFeatures]] and
  * [[KcpMobilityPawnsFeatures]] extractors across standard benchmark positions.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class KcpMobilityBenchmark:

  @Param(Array("initial", "kiwipete", "endgame", "castling", "promotion"))
  var position: String = uninitialized

  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position))

  @Benchmark
  def kcp(): Array[Float] = KcpFeatures.extract(state, Color.White)

  @Benchmark
  def kcpMobility27(): Array[Float] = KcpMobilityFeatures.extract(state, Color.White)

  @Benchmark
  def kcpMobilityPawns31(): Array[Float] = KcpMobilityPawnsFeatures.extract(state, Color.White)

  @Benchmark
  def pieceMobilityCounts(): Array[Int] = PieceMobility.counts(state, Color.White)

  @Benchmark
  def passedPawnsSummary(): (Int, Int) = PassedPawns.countAndMaxRank(state, Color.White)
