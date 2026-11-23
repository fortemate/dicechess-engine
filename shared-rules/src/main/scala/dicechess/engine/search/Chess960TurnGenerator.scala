// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator

/** Full rolled turns in UCI_Chess960 notation, where castling names the king and the participating rook. A roll
  * contains at most three dice. A castle pays two, so at most one ordinary move can accompany it.
  */
object Chess960TurnGenerator:
  def legalPaths(snapshot: Chess960Dfen): List[List[String]] =
    moves(snapshot).map(_.map(_.toUci))

  private def moves(snapshot: Chess960Dfen): List[List[Move]] =
    val board    = snapshot.position // Its classical castling flags are zero; variant rights live on the snapshot.
    val ordinary = TurnGenerator.generateAllLegalTurnPaths(board)
    val own      = if board.activeColor.isWhite then board.whitePieces else board.blackPieces
    val rights   = snapshot.castlingRooks & own
    if rights.isEmpty || !board.flags.containsDie(PieceType.King.diceValue) ||
      !board.flags.containsDie(PieceType.Rook.diceValue)
    then ordinary
    else
      val castleFirst = Chess960Dfen
        .castles(board, rights)
        .flatMap: castle =>
          val suffixes = TurnGenerator.generateAllLegalTurnPaths(snapshot.afterCastle(castle))
          if suffixes.isEmpty then List(List(castle.encoded))
          else suffixes.map(castle.encoded :: _)
      val castleLast =
        if board.dicePool.size < 3 then Nil
        else
          val eligible = TurnGenerator.computeEligibleEpPawns(board)
          MoveGenerator
            .generateMoves(board, eligible)
            .flatMap: move =>
              val remaining = board.diceAfter(move)
              if board.isKingCapture(move) || board.mailbox(move.fromSquare).pieceType == PieceType.King ||
                !remaining.containsDie(PieceType.King.diceValue) || !remaining.containsDie(PieceType.Rook.diceValue)
              then Nil
              else
                val next = board.makeMove(move).withDiceSlotsOf(remaining)
                Chess960Dfen.castles(next, rights.remove(move.fromSquare)).map(castle => List(move, castle.encoded))

      if castleFirst.isEmpty && castleLast.isEmpty then ordinary
      else
        val paths                       = ordinary ::: castleFirst ::: castleLast
        def cost(path: List[Move]): Int = path.foldLeft(0)((n, m) => n + (if m.isCastling then 2 else 1))
        val maximum                     = paths.iterator.map(cost).max
        // Opposing pieces never move during the turn. Only the last move can capture their king.
        paths.filter(path => cost(path) == maximum || (!path.last.isCastling && board.isKingCapture(path.last)))
