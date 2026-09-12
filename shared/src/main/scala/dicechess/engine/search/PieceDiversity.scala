// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*

/** Unweighted Piece Diversity Index (PDI): presence of Pawn, Knight, Bishop, Rook, and Queen for one side.
  *
  * Each represented type contributes one, regardless of its number of pieces or their squares. Kings are excluded. This
  * measures material diversity, not mobility or the number of dice a side can use: a present type may be blocked, and
  * promotion can restore an absent type. The index is independent of the active color and dice pool.
  */
object PieceDiversity:

  /** Returns the number of non-king piece types present for `color`, in the inclusive range 0 to 5. */
  def count(state: GameState, color: Color): Int =
    val pieces = if color.isWhite then state.whitePieces else state.blackPieces
    var result = 0
    if !(pieces & state.pawns).isEmpty then result += 1
    if !(pieces & state.knights).isEmpty then result += 1
    if !(pieces & state.bishops).isEmpty then result += 1
    if !(pieces & state.rooks).isEmpty then result += 1
    if !(pieces & state.queens).isEmpty then result += 1
    result

  /** Returns own PDI minus the opponent's PDI from `color`'s perspective, in the inclusive range -5 to 5. */
  def difference(state: GameState, color: Color): Int =
    count(state, color) - count(state, color.opponent)
