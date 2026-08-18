package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.movegen.*

import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class MagicBitboardsBenchmark:

  var square: Square      = uninitialized
  var occupancy: Bitboard = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    square = Square('d', 4)
    // Create an occupancy board combining white and black pieces from Kiwipete position
    val state = BenchmarkPositions.parse(BenchmarkPositions.Kiwipete)
    occupancy = state.whitePieces | state.blackPieces

  @Benchmark
  def bishopAttacks(): Bitboard =
    MagicBitboards.bishopAttacks(square, occupancy)

  @Benchmark
  def rookAttacks(): Bitboard =
    MagicBitboards.rookAttacks(square, occupancy)

  @Benchmark
  def queenAttacks(): Bitboard =
    MagicBitboards.queenAttacks(square, occupancy)
