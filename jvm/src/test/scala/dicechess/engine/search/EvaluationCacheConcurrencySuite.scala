// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

import java.util.concurrent.{Callable, CyclicBarrier, Executors, TimeUnit}

/** Invocation-local tables must stay independent while callbacks overlap and nested caches grow (#456). */
class EvaluationCacheConcurrencySuite extends FunSuite:
  test("independent concurrent and nested caches retain their own configuration, growth and telemetry") {
    val base    = FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").fold(fail(_), identity)
    val barrier = new CyclicBarrier(4)
    val pool    = Executors.newFixedThreadPool(4)
    try
      val futures = (0 until 4).map { offset =>
        pool.submit(new Callable[Int]:
          def call(): Int =
            var first = true
            val cache = new EvaluationCache((s, c) =>
              if first then
                first = false
                barrier.await(30, TimeUnit.SECONDS)
                val nested = new EvaluationCache((n, _) => n.fullMoveNumber - offset)
                for counter <- 1 to 128 do
                  assertEquals(nested(base.copy(fullMoveNumber = counter), c), counter - offset)
                assertEquals(nested.entryCount, 128)
                assertEquals(nested.evaluatorCalls, 128L)
                assertEquals(nested.cacheHits, 0L)
              s.fullMoveNumber + offset
            )
            for counter <- 1 to 256 do
              assertEquals(cache(base.copy(fullMoveNumber = counter), Color.White), counter + offset)
            for counter <- (1 to 256).reverse do
              assertEquals(cache(base.copy(fullMoveNumber = counter), Color.White), counter + offset)
            assertEquals(cache.entryCount, 256)
            assertEquals(cache.evaluatorCalls, 256L)
            assertEquals(cache.cacheHits, 256L)
            cache(base, Color.White))
      }
      assertEquals(futures.map(_.get(60, TimeUnit.SECONDS)).toList, List(1, 2, 3, 4))
    finally pool.shutdownNow()
  }
