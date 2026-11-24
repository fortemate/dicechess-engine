// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*

object ConfiguredTurns:
  /** Applies one complete legal turn using the coordinate notation selected by the layout. */
  def applyTurn(dfen: String, path: List[String], config: RulesConfig): Either[String, String] =
    if config.isChess960 then Chess960TurnGenerator.applyTurn(dfen, path)
    else FenParser.parse(dfen).flatMap(applyClassical(_, path))

  private def applyClassical(board: GameState, path: List[String]): Either[String, String] =
    if board.flags.isDicePoolEmpty then Left("A turn requires a roll")
    else if (board.kings & board.whitePieces).isEmpty || (board.kings & board.blackPieces).isEmpty then
      Left("The game has already ended")
    else
      val length = path.length
      var result = Option.empty[GameState]
      val count  = TurnGenerator.forEachLegalTurnState(board): (moves, size, next, _) =>
        val matches = length == size && path.iterator.zipWithIndex.forall((uci, index) => moves(index).toUci == uci)
        if matches then result = Some(next)
        !matches

      val selected = if count == 0 && path.isEmpty then Some(board.endTurn()) else result
      selected
        .toRight("Expected a complete legal turn")
        .flatMap: next =>
          Either.cond(
            next.fullMoveNumber > 0,
            FenParser.serialize(next),
            "The next full-move number exceeds the DFEN range"
          )
