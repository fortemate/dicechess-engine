package dicechess

/** Core library and entry points for the Dice Chess Engine.
  *
  * The Dice Chess Engine is a high-performance game engine written in Scala 3, cross-compiled to JVM, JavaScript, and
  * WebAssembly targets.
  *
  * ## Package Structure
  *
  *   - [[dicechess.engine.domain]]: Fundamental domain types, board representations, opaque wrappers, and FEN parsers.
  *   - [[dicechess.engine.movegen]]: High-performance bitboard move generators (pawns, leapers, magic sliders).
  *   - [[dicechess.engine.search]]: Turn path generation, AI search algorithms (Greedy, Monte-Carlo), and evaluation.
  *   - `dicechess.engine.cli`: Interactive command-line REPL interpreter (JVM).
  *   - `dicechess.engine.bench`: Bot battle arena match simulation framework (JVM).
  *   - `dicechess.engine.api`: JavaScript and WebAssembly API facade and wrapper classes.
  *
  * The last three live in separate sbt projects (`cli`, `arena`, `js`, `wasm`) not aggregated into this Scaladoc build,
  * so they cannot be cross-referenced as links from here — see [[dicechess.engine.domain]],
  * [[dicechess.engine.movegen]] and [[dicechess.engine.search]] above for the packages this documentation actually
  * covers.
  */
package object engine
