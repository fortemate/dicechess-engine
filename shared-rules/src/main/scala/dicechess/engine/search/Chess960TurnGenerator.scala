// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator

/** Full rolled turns in UCI_Chess960 notation, where castling names the king and the participating rook. A roll
  * contains at most three dice. A castle pays two, so at most one ordinary move can accompany it.
  */
object Chess960TurnGenerator:
  def legalPaths(snapshot: Chess960Dfen): List[List[String]] =
    if snapshot.hasBothKings then moves(snapshot).map(_.map(_.toUci)) else Nil

  /** Applies a complete legal UCI_Chess960 path, or an empty path for a rolled forced pass. */
  def applyTurn(dfen: String, path: List[String]): Either[String, String] =
    Chess960Dfen
      .parse(dfen)
      .flatMap: snapshot =>
        val board = snapshot.position
        if board.flags.isDicePoolEmpty then Left("A Chess960 turn requires a roll")
        else if !snapshot.hasBothKings then Left("The game has already ended")
        else
          val legal    = moves(snapshot)
          val selected = if legal.isEmpty && path == Nil then Some(Nil) else legal.find(_.map(_.toUci) == path)
          for
            steps  <- selected.toRight("Expected a complete legal Chess960 turn")
            result <- steps.foldLeft[Either[String, Chess960Dfen]](Right(snapshot)): (state, move) =>
              state.flatMap(_.play(move))
            dfen <- result.completedDfen
          yield dfen

  private def moves(snapshot: Chess960Dfen): List[List[Move]] =
    val board    = snapshot.position // Its classical castling flags are zero; variant rights live on the snapshot.
    val ordinary = TurnGenerator.generateAllLegalTurnPaths(board)
    val own      = if board.activeColor.isWhite then board.whitePieces else board.blackPieces
    val rights   = snapshot.castlingRooks & own
    if rights.isEmpty || !board.flags.hasCastlingDice then ordinary
    else
      val castles = pathsStartingWithCastle(snapshot, rights) ::: pathsEndingWithCastle(board, rights)
      if castles.isEmpty then ordinary else maximalOrWinningPaths(board, ordinary ::: castles)

  private def pathsStartingWithCastle(snapshot: Chess960Dfen, rights: Bitboard): List[List[Move]] =
    Chess960Dfen
      .castles(snapshot.position, rights)
      .flatMap: castle =>
        val suffixes = TurnGenerator.generateAllLegalTurnPaths(snapshot.afterCastle(castle))
        if suffixes.isEmpty then List(List(castle.encoded))
        else suffixes.map(castle.encoded :: _)

  private def pathsEndingWithCastle(board: GameState, rights: Bitboard): List[List[Move]] =
    if board.dicePool.size < 3 then Nil
    else
      val eligible = TurnGenerator.computeEligibleEpPawns(board)
      MoveGenerator
        .generateMoves(board, eligible)
        .flatMap: move =>
          val remaining = board.diceAfter(move)
          if board.isKingCapture(move) || board.mailbox(move.fromSquare).pieceType == PieceType.King ||
            !remaining.hasCastlingDice
          then Nil
          else
            val next = board.makeMove(move).withDiceSlotsOf(remaining)
            Chess960Dfen.castles(next, rights.remove(move.fromSquare)).map(castle => List(move, castle.encoded))

  private def maximalOrWinningPaths(board: GameState, paths: List[List[Move]]): List[List[Move]] =
    def cost(path: List[Move]): Int = path.foldLeft(0)((n, m) => n + (if m.isCastling then 2 else 1))
    val maximum                     = paths.iterator.map(cost).max
    // Opposing pieces never move during the turn. Only the last move can capture their king.
    paths.filter(path => cost(path) == maximum || (!path.last.isCastling && board.isKingCapture(path.last)))
