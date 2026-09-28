// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.search.{PlayableDice, TurnGenerator}

import scala.compiletime.uninitialized

/** What [[dicechess.engine.search.PlayableDice.of]] costs next to the enumeration of legal turns (#293).
  *
  * The playable dice take the search that `forEachLegalTurnPath` runs, plus a replay of the moves of every legal turn
  * to see which dice they spend. The benchmark shows what the replay adds. The positions span the range seen in 300
  * random games:
  *   - **start-bnq**: the start position with bishop, knight and queen, four legal turns and two dice no legal turn can
  *     spend;
  *   - **blocked-brq**: the largest tree of those games in which no turn spends every die (1,299 turns);
  *   - **open-qqr**: the largest tree of those games (63,734 turns), two queens and a rook on an open board.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class PlayableDiceBenchmark:

  @Param(Array("start-bnq", "blocked-brq", "open-qqr"))
  var position: String = uninitialized

  var state: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val dfen = position match
      case "start-bnq"   => s"${BenchmarkPositions.Initial} BNQ"
      case "blocked-brq" => "8/5r2/1P2Q3/3R4/p3QR1p/1k5P/2p3K1/8 w - - 0 36 BRQ"
      case _             => "Q5k1/4P3/r1Q4p/1p5N/1P5K/4R2P/8/3R3B w - - 0 32 QQR"
    state = BenchmarkPositions.parse(dfen)

  /** Every legal turn, visited without being kept: the search alone. */
  @Benchmark
  def forEachLegalTurnPath(blackhole: Blackhole): Int =
    var count = 0
    TurnGenerator.forEachLegalTurnPath(state) { (moves, len) =>
      blackhole.consume(moves(len - 1))
      count += 1
    }
    count

  /** The dice every legal turn can spend, asked at the roll. */
  @Benchmark
  def playableDice(): Option[List[Int]] =
    PlayableDice.of(state, Nil)
