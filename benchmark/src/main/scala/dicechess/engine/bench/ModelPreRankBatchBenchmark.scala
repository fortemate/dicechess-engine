// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.{OnnxEvalSearch, PreRankModel}
import org.openjdk.jmh.annotations.*

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** One tensor or several, at pre-ranking widths (#78).
  *
  * Bounding the pre-rank batch is a safety property: the pass sees *every* legal turn, so an unbounded call allocates a
  * tensor whose size follows the position's branching factor and hands the native runtime one uninterruptible call no
  * configuration limits. This measures what that safety costs, because "chunking is free" and "chunking halves
  * throughput" lead to different defaults.
  *
  * Row counts are the widths that actually occur: Dice Chess offers hundreds of legal turns routinely and thousands in
  * the tail. The model is the throwaway synthetic fixture (no trained weights live in this repository), which is what
  * isolates per-call overhead — the only thing chunking can add.
  *
  * Recorded in `benchmark/BASELINE.md`. Run with `mise run bench:filter ModelPreRankBatchBenchmark`.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class ModelPreRankBatchBenchmark:

  @Param(Array("64", "256", "1024"))
  var rows: Int = uninitialized

  private var bot: OnnxEvalSearch      = uninitialized
  private var states: Array[GameState] = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val modelFile = Files.createTempFile("prerank-batch-bench", ".onnx")
    modelFile.toFile.deleteOnExit()
    val resource = getClass.getResourceAsStream("/synthetic_test_model.onnx")
    try Files.copy(resource, modelFile, StandardCopyOption.REPLACE_EXISTING)
    finally resource.close()
    bot = new OnnxEvalSearch(modelFile.toString)
    val pool = BenchmarkPositions.AllPositions.values.toArray.map(BenchmarkPositions.parse)
    states = Array.tabulate(rows)(i => pool(i % pool.length))

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    bot.close()

  @Benchmark
  def oneShot(): Array[Int] = bot.onnxEvalBatch(states, Color.White)

  @Benchmark
  def chunked(): Array[Int] = bot.onnxEvalBatchChunked(states, Color.White, PreRankModel.DefaultChunkSize)

  /** The bound at its smallest useful value, which is where chunking can cost the most. */
  @Benchmark
  def chunkedTight(): Array[Int] = bot.onnxEvalBatchChunked(states, Color.White, 32)
