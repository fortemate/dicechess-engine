package dicechess.engine.search

import munit.FunSuite
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TranspositionTableJvmSpec extends FunSuite:

  test("concurrent reads and writes are thread-safe and never throw exceptions"):
    val tt         = new TranspositionTable(4096)
    val numThreads = 8
    val executor   = Executors.newFixedThreadPool(numThreads)
    val iterations = 20000

    for t <- 0 until numThreads do
      executor.submit(new Runnable {
        override def run(): Unit =
          var i = 0
          while i < iterations do
            val key = (t * 1000 + (i % 100)).toLong
            if i % 2 == 0 then tt.store(key, i.toDouble, TTBound.Exact, depth = i % 4, lossTainted = i % 3 == 0)
            else tt.probe(key)
            i += 1
      })

    executor.shutdown()
    val finished = executor.awaitTermination(10, TimeUnit.SECONDS)
    assert(finished, "Multithreaded test timed out")
    assert(tt.stores > 0L)
