package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.OnnxEvalSearch
import org.openjdk.jmh.annotations.*

import java.nio.file.{Files, StandardCopyOption}
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** Batched vs. repeated single ONNX inference.
  *
  * Validates the claim behind [[OnnxEvalSearch.onnxEvalBatch]]: session-run cost is dominated by per-call overhead (JNI
  * boundary, graph setup), not row count, so scoring N positions in one call is far cheaper than N separate calls — the
  * hot path for a multi-leaf search (an expectimax chance node scores all its leaves at once). Uses the same throwaway
  * synthetic model as the tests (no trained weights live in this repo); its own compute is negligible, which is
  * precisely what isolates the per-call overhead the batching targets.
  *
  * Run with `mise run bench:quick` (or the full `bench` task).
  */
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
class OnnxEvalBatchBenchmark:

  @Param(Array("8", "56", "200"))
  var batchSize: Int = uninitialized

  private var bot: OnnxEvalSearch      = uninitialized
  private var states: Array[GameState] = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    // OnnxEvalSearch loads from a filesystem path, so unpack the classpath resource to a temp file first.
    val modelFile = Files.createTempFile("onnx-bench-model", ".onnx")
    modelFile.toFile.deleteOnExit()
    val resource = getClass.getResourceAsStream("/synthetic_test_model.onnx")
    try Files.copy(resource, modelFile, StandardCopyOption.REPLACE_EXISTING)
    finally resource.close()
    bot = new OnnxEvalSearch(modelFile.toString)
    // Cycle the standard positions up to batchSize so the batch carries real variety.
    val pool = BenchmarkPositions.AllPositions.values.toArray.map(BenchmarkPositions.parse)
    states = Array.tabulate(batchSize)(i => pool(i % pool.length))

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    bot.close()

  @Benchmark
  def repeatedSingle(): Int =
    var acc = 0
    var i   = 0
    while i < states.length do
      acc += bot.onnxEval(states(i), Color.White)
      i += 1
    acc

  @Benchmark
  def batched(): Array[Int] =
    bot.onnxEvalBatch(states, Color.White)
