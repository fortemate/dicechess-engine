// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.util.Random

/** Whole-move latency with all experimental settings supplied explicitly through JMH -p arguments. */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class TacticianSearchBenchmark:
  @Param(Array("initial", "kiwipete", "endgame"))
  var position: String = uninitialized
  // Zero is a sentinel: setup rejects an omitted candidate-limit argument instead of embedding a search profile.
  @Param(Array("0"))
  var candidateLimit: Int = uninitialized
  @Param(Array("0"))
  var kingThreatCp: Int = uninitialized
  @Param(Array("0"))
  var kingDangerCp: Int = uninitialized
  @Param(Array("0"))
  var hangingWeight: Double   = uninitialized
  var state: GameState        = uninitialized
  var search: TacticianSearch = uninitialized
  var random: Random          = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    state = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position)).withDicePool(List(1, 2, 3))
    search = new TacticianSearch(TacticianConfig(candidateLimit, kingThreatCp, kingDangerCp, hangingWeight))
    random = new Random(0)

  @Benchmark
  def tactician(): Option[ScoredSequence] = search.findBestMove(state, random)
  @Benchmark
  def aggressive(): Option[ScoredSequence] = AggressiveSearch.findBestMove(state, random)
