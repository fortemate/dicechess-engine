// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.movegen

import dicechess.engine.domain.*

// Keep API bindings separate from the frozen scenarios and expected move sequences.
private[engine] object RawMoveGeneratorTestAccess:

  def root(state: GameState, color: Color, active: Long, die: Int, out: Array[Int]): Int =
    MoveGenerator.generatePieceMovesInto(state, color, active, die, out)

  def continuation(
      myPieces: Long,
      enemyPieces: Long,
      active: Long,
      enemyKings: Long,
      enPassant: Long,
      piece: Piece,
      out: Array[Int]
  ): Int =
    MoveGenerator.generatePieceMovesInto(
      myPieces,
      enemyPieces,
      active,
      enemyKings,
      enPassant,
      piece,
      out
    )
