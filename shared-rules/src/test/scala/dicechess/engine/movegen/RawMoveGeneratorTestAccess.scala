// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.movegen

import dicechess.engine.domain.*

// Keep API bindings separate from the frozen scenarios and expected move sequences.
private[engine] object RawMoveGeneratorTestAccess:

  def root(state: GameState, color: Color, active: Long, die: Int, out: Array[Int]): Int =
    val myPieces    = if color.isWhite then state.whitePieces.value else state.blackPieces.value
    val enemyPieces = if color.isWhite then state.blackPieces.value else state.whitePieces.value
    MoveGenerator.generatePieceMovesInto(
      myPieces,
      enemyPieces,
      active,
      enemyPieces & state.kings.value,
      myPieces & state.rooks.value,
      state.enPassant.value,
      state.flags.castlingRights,
      color.isWhite,
      die,
      out
    )

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
      0L,
      enPassant,
      0,
      piece.color.isWhite,
      piece.pieceType.diceValue,
      out
    )
