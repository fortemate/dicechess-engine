package dicechess.engine.search

import munit.FunSuite
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TranspositionTableSpec extends FunSuite:

  test("store and probe retrieve exact entries correctly"):
    val tt  = new TranspositionTable(1024)
    val key = 0x123456789abcdef0L

    assertEquals(tt.probe(key), None)
    assertEquals(tt.misses, 1L)

    tt.store(key, 123.45, TTBound.Exact, depth = 2, lossTainted = false)
    assertEquals(tt.stores, 1L)

    val probed = tt.probe(key)
    assert(probed.isDefined)
    val entry = probed.get
    assertEquals(entry.key, key)
    assertEquals(entry.value, 123.45)
    assertEquals(entry.bound, TTBound.Exact)
    assertEquals(entry.depth, 2)
    assertEquals(entry.lossTainted, false)
    assertEquals(tt.hits, 1L)
    assertEquals(tt.hitRate, 0.5) // 1 hit, 1 miss

  test("store and probe preserve bounds and lossTainted"):
    val tt   = new TranspositionTable(256)
    val key1 = 111111L
    val key2 = 222222L

    tt.store(key1, -100.0, TTBound.UpperBound, depth = 1, lossTainted = true)
    tt.store(key2, 50.0, TTBound.LowerBound, depth = 3, lossTainted = false)

    val entry1 = tt.probe(key1).get
    assertEquals(entry1.bound, TTBound.UpperBound)
    assertEquals(entry1.lossTainted, true)

    val entry2 = tt.probe(key2).get
    assertEquals(entry2.bound, TTBound.LowerBound)
    assertEquals(entry2.lossTainted, false)

  test("depth-preferred replacement policy"):
    val tt  = new TranspositionTable(256)
    val key = 99999L

    tt.store(key, 10.0, TTBound.Exact, depth = 1, lossTainted = false)
    assertEquals(tt.probe(key).get.depth, 1)

    // Deeper search replaces shallower search
    tt.store(key, 20.0, TTBound.Exact, depth = 3, lossTainted = false)
    assertEquals(tt.probe(key).get.value, 20.0)
    assertEquals(tt.probe(key).get.depth, 3)

    // Shallower search does NOT replace deeper exact entry
    tt.store(key, 5.0, TTBound.Exact, depth = 1, lossTainted = false)
    assertEquals(tt.probe(key).get.value, 20.0)
    assertEquals(tt.probe(key).get.depth, 3)

  test("telemetry metrics and reset work as expected"):
    val tt = new TranspositionTable(128)

    tt.probe(1L) // miss
    tt.probe(2L) // miss
    tt.store(1L, 1.0, TTBound.Exact, 1, false)
    tt.probe(1L) // hit

    assertEquals(tt.misses, 2L)
    assertEquals(tt.hits, 1L)
    assertEquals(tt.stores, 1L)
    assertEquals(tt.hitRate, 1.0 / 3.0)

    tt.resetTelemetry()
    assertEquals(tt.hits, 0L)
    assertEquals(tt.misses, 0L)
    assertEquals(tt.stores, 0L)
    assertEquals(tt.hitRate, 0.0)

  test("clear wipes table entries"):
    val tt  = new TranspositionTable(128)
    val key = 777L

    tt.store(key, 42.0, TTBound.Exact, 2, false)
    assert(tt.probe(key).isDefined)

    tt.clear()
    assertEquals(tt.probe(key), None)

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
