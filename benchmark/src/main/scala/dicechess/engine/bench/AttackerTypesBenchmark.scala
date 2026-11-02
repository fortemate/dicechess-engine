// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import dicechess.engine.search.{AttackerTypes, PieceValues}
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** What the type reduction and the minimum-price lookup add on top of [[MoveGenerator.allAttackers]] (#395).
  *
  * The attacker set is the geometry a safety term already pays for; the helpers add six bitboard intersections and a
  * six-way minimum. Measuring them next to the bare `allAttackers` call on the same square shows the overhead as a
  * ratio rather than a number that depends on the host. `d5` is the probed square in every position, as in
  * [[AllAttackersBenchmark]].
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class AttackerTypesBenchmark:

  @Param(Array("initial", "kiwipete", "endgame", "castling"))
  var position: String = uninitialized

  private var state: GameState    = uninitialized
  private var square: Square      = uninitialized
  private var prices: PieceValues = uninitialized

  @Setup(Level.Trial)
  def setUp(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position))
    square = Square.fromNotation("d5").getOrElse(sys.error("d5 must parse"))
    prices = PieceValues.Canonical

  /** The geometry alone: the baseline every other method includes. */
  @Benchmark
  def allAttackers(): Bitboard = MoveGenerator.allAttackers(state, square, Color.White)

  /** Geometry plus the six intersections that reduce squares to types. */
  @Benchmark
  def typeMask(): Int = AttackerTypes.mask(state, square, Color.White)

  /** Geometry, type mask and the `216 - (6 - k)^3` availability numerator. */
  @Benchmark
  def availableRolls(): Int = AttackerTypes.availableRolls(AttackerTypes.mask(state, square, Color.White))

  /** Geometry, type mask and the allocation-free six-way minimum over a price table. */
  @Benchmark
  def cheapestAttacker(): Int =
    prices.minimumOrElse(AttackerTypes.mask(state, square, Color.White), empty = Int.MaxValue)
