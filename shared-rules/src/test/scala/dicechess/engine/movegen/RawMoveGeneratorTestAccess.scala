// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.movegen

import dicechess.engine.domain.*

// Keep API bindings separate from the frozen scenarios and expected move sequences.
private[engine] object RawMoveGeneratorTestAccess:

  def root(state: GameState, color: Color, active: Long, die: Int, out: Array[Int]): Int =
    val isWhite     = color.isWhite
    val myPieces    = if isWhite then state.whitePieces.value else state.blackPieces.value
    val enemyPieces = if isWhite then state.blackPieces.value else state.whitePieces.value
    MoveGenerator.generateRootPieceMovesInto(state, myPieces, enemyPieces, active, isWhite, die, out)

  def continuation(
      myPieces: Long,
      enemyPieces: Long,
      active: Long,
      enemyKings: Long,
      enPassant: Long,
      piece: Piece,
      out: Array[Int]
  ): Int =
    MoveGenerator.generateContinuationPieceMovesInto(
      myPieces,
      enemyPieces,
      active,
      enemyKings,
      enPassant,
      piece,
      out
    )
