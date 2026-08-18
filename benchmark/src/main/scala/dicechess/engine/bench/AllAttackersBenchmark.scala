package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** [[MoveGenerator.allAttackers]] against [[MoveGenerator.isSquareAttacked]] (#509).
  *
  * Two questions this measures. First, what the complete set costs relative to the early-exit variant on the same
  * square — the answer bounds how freely a per-square safety or defender term can be evaluated. Second, how that gap
  * behaves on an *unattacked* square, where neither function can exit early and both must run all five branches: there
  * the two should converge, which is the honest baseline for the comparison.
  *
  * `d5` is the probed square in every position: contested in the middlegame fixtures, quiet in the sparse ones.
  */
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class AllAttackersBenchmark:

  @Param(Array("initial", "kiwipete", "endgame", "castling"))
  var position: String = uninitialized

  private var state: GameState = uninitialized
  private var square: Square   = uninitialized

  @Setup(Level.Trial)
  def setUp(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position))
    square = Square.fromNotation("d5").getOrElse(sys.error("d5 must parse"))

  /** The complete set: no branch can be skipped, five per-type masks always computed. */
  @Benchmark
  def allAttackersWhite(): Bitboard = MoveGenerator.allAttackers(state, square, Color.White)

  @Benchmark
  def allAttackersBlack(): Bitboard = MoveGenerator.allAttackers(state, square, Color.Black)

  /** The early-exit baseline, for the ratio. */
  @Benchmark
  def isSquareAttackedWhite(): Bitboard = MoveGenerator.isSquareAttacked(state, square, Color.White)

  @Benchmark
  def isSquareAttackedBlack(): Bitboard = MoveGenerator.isSquareAttacked(state, square, Color.Black)
