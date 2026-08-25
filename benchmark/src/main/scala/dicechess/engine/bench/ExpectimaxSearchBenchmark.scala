package dicechess.engine.bench

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import dicechess.engine.domain.{Color, GameState}
import dicechess.engine.search.{Evaluator, ExpectimaxConfig, ExpectimaxSearch, ScoredSequence, TranspositionTable}

import scala.compiletime.uninitialized
import scala.util.Random

/** Cold-tree latency benchmark for configurable expectimax depth (#60).
  *
  * The sparse tactical position keeps depth 3 practical in a local JMH run while still exercising both recursive chance
  * layers, terminal-win handling, and inner TT traffic. The invocation-level setup clears the table before every
  * benchmark invocation.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class ExpectimaxSearchBenchmark:

  @Param(Array("2", "3"))
  var searchDepth: Int = uninitialized

  private var state: GameState          = uninitialized
  private var table: TranspositionTable = uninitialized
  private var search: ExpectimaxSearch  = uninitialized
  private var random: Random            = uninitialized

  @Setup(Level.Trial)
  def setupTrial(): Unit =
    state = BenchmarkPositions
      .parse("k7/8/8/8/8/8/8/R3K3 w - - 0 1")
      .withDicePool(List(2, 3, 6))
    table = new TranspositionTable(4096)
    val evalBatch: (Array[GameState], Color) => Array[Int] =
      (states, color) => states.map(Evaluator.evaluateMaterial(_, color))
    search = ExpectimaxSearch(
      evalBatch,
      ExpectimaxConfig(candidateLimit = 1, searchDepth = searchDepth),
      tt = Some(table)
    )

  @Setup(Level.Invocation)
  def setupInvocation(): Unit =
    table.clear()
    random = Random(42L)

  @Benchmark
  def coldTree(): Option[ScoredSequence] = search.findBestMove(state, random)
