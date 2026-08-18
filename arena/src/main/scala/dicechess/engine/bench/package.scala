package dicechess.engine

/** Performance benchmarking and simulation for the Dice Chess Engine.
  *
  * This package contains the bot match arena and match runner execution pipelines on the JVM.
  *
  * ## Features
  *
  *   - **Bot-vs-Bot Arena:** Run in-memory games between search algorithms via
  *     [[dicechess.engine.bench.BotMatchRunner]].
  *   - **Outcome Analysis:** Metrics collection for wins (as White/Black), losses, draws, and average move timings.
  *   - **Correctness Verification:** Dynamic desync checks between bitboards and mailbox representations.
  *
  * Additional micro-benchmarks are defined in the separate `benchmark` module using the JMH harness.
  */
package object bench
