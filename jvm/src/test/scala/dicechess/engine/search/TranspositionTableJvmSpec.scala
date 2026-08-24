package dicechess.engine.search

import munit.FunSuite
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

class TranspositionTableJvmSpec extends FunSuite:

  test("concurrent reads and writes are racy-reader safe and never throw exceptions"):
    val tt         = new TranspositionTable(4096)
    val numThreads = 8
    val executor   = Executors.newFixedThreadPool(numThreads)
    val iterations = 20000

    val tasks = (0 until numThreads).map { t =>
      new java.util.concurrent.Callable[Unit]:
        override def call(): Unit =
          var i = 0
          while i < iterations do
            val key = (t * 1000 + (i % 100)).toLong
            if i % 2 == 0 then tt.store(key, i.toDouble, TTBound.Exact, depth = i % 4, lossTainted = i % 3 == 0)
            else tt.probe(key)
            i += 1
    }

    // invokeAll + get(): a worker's thrown Throwable (failed assertion, AIOOBE) resurfaces here instead of being
    // silently swallowed by a discarded Future — otherwise this test could not fail on the very defects it targets.
    val futures = executor.invokeAll(tasks.asJava)
    executor.shutdown()
    assert(executor.awaitTermination(10, TimeUnit.SECONDS), "Multithreaded test timed out")
    futures.asScala.foreach(_.get())
    assert(tt.stores > 0L)
