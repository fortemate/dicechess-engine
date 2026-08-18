package dicechess.engine

/** Interactive Command Line Interface (CLI) for the Dice Chess Engine.
  *
  * This package implements the terminal REPL interface. The CLI is isolated in its own module to prevent heavy terminal
  * dependencies (like `decline` and `jline`) from leaking into the main engine artifact, ensuring a lightweight
  * footprint for downstream consumers.
  */
package object cli
