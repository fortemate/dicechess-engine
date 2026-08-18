package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*

import scala.compiletime.uninitialized

/** Micro-benchmarks for [[Symmetry]] color-flip canonicalization.
  *
  * `colorFlip` runs on every position analytics canonicalizes, so its cost matters. `canonical` is measured on
  * white-to-move positions (the no-op branch) to confirm the common case is near-free; `canonicalKey` additionally pays
  * for FEN serialization.
  */
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class SymmetryBenchmark:

  @Param(Array("initial", "kiwipete", "endgame", "castling"))
  var position: String = uninitialized

  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position))

  @Benchmark
  def colorFlip(): GameState =
    Symmetry.colorFlip(state)

  @Benchmark
  def horizontalMirror(): GameState =
    Symmetry.horizontalMirror(state)

  @Benchmark
  def canonical(): GameState =
    Symmetry.canonical(state)

  @Benchmark
  def canonicalKey(): String =
    Symmetry.canonicalKey(state)
