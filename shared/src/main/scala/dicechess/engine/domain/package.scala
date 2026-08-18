package dicechess.engine

/** Core domain models for the Dice Chess Engine.
  *
  * This package contains the fundamental data structures used to represent the chess board, pieces, moves, and game
  * state. It heavily relies on Scala 3 Opaque Types and bitwise memory packing to guarantee zero-allocation performance
  * during hot-path evaluations like Expectimax search.
  */
package object domain
