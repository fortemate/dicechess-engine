// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.KingCaptureProbability
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(20)
@State(Scope.Thread)
class KingCaptureInitializationBenchmark:

  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.Initial)

  @Benchmark
  def firstKingCaptureProbability(): Double =
    KingCaptureProbability.kingCaptureProbability(state, Color.White)
