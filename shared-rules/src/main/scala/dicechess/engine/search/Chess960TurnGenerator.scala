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

  /** Maximum remaining multiplicity per die face across legal paths continuing the given prefix. */
  def playableDice(snapshot: Chess960Dfen, played: List[String]): Option[List[Int]] =
    if !snapshot.hasBothKings then Option.when(played.isEmpty)(Nil)
    else if played.lengthCompare(GameFlags.DiceSlots) > 0 then None
    else
      val castles = castlingPaths(snapshot)
      if castles.isEmpty then
        val prefix = played.foldRight(Option(List.empty[Move])): (uci, rest) =>
          for tail <- rest; text <- Option(uci); move <- Move.fromUci(text) yield move :: tail
        prefix.flatMap(PlayableDice.of(snapshot.position, _))
      else playableWithCastles(snapshot, played, castles)

  private def playableWithCastles(snapshot: Chess960Dfen, played: List[String], castles: List[List[Move]]) =
    val legal      = legalMoves(snapshot.position, castles)
    val prefixSize = played.size
    val matching   =
      if played.isEmpty then legal else legal.filter(_.iterator.take(prefixSize).map(_.toUci).sameElements(played))
    matching.headOption.flatMap: first =>
      replay(snapshot, first.take(prefixSize)).toOption.flatMap: prefix =>
        val pool = prefix.position.dicePool
        // A legal continuation that spends the entire roll is a witness for every die left after this prefix.
        val rollSize = snapshot.position.dicePool.size
        if matching.exists(diceCost(_) == rollSize) then Some(pool.sorted)
        else
          matching
            .foldLeft[Either[String, List[Int]]](Right(Nil)): (result, path) =>
              for
                maxima <- result
                end    <- replay(prefix, path.drop(prefixSize))
              yield
                val spent = pool.diff(end.position.dicePool)
                maxima ::: spent.diff(maxima)
            .toOption
            .map(_.sorted)

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
            result <- replay(snapshot, steps)
            dfen   <- result.completedDfen
          yield dfen

  private def replay(snapshot: Chess960Dfen, path: List[Move]): Either[String, Chess960Dfen] =
    path.foldLeft[Either[String, Chess960Dfen]](Right(snapshot)): (state, move) =>
      state.flatMap(_.play(move))

  private def moves(snapshot: Chess960Dfen): List[List[Move]] =
    legalMoves(snapshot.position, castlingPaths(snapshot))

  private def legalMoves(board: GameState, castles: List[List[Move]]): List[List[Move]] =
    val ordinary = TurnGenerator.generateAllLegalTurnPaths(board)
    if castles.isEmpty then ordinary else maximalOrWinningPaths(board, ordinary ::: castles)

  private def castlingPaths(snapshot: Chess960Dfen): List[List[Move]] =
    val board = snapshot.position // Classical castling flags are zero; variant rights live on the snapshot.
    if !board.flags.hasCastlingDice then Nil
    else
      val own    = if board.activeColor.isWhite then board.whitePieces else board.blackPieces
      val rights = snapshot.castlingRooks & own
      if rights.isEmpty then Nil else pathsStartingWithCastle(snapshot, rights) ::: pathsEndingWithCastle(board, rights)

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
    val maximum = paths.iterator.map(diceCost).max
    // Opposing pieces never move during the turn. Only the last move can capture their king.
    paths.filter(path => diceCost(path) == maximum || (!path.last.isCastling && board.isKingCapture(path.last)))

  private def diceCost(path: List[Move]): Int = path.foldLeft(0)((n, m) => n + (if m.isCastling then 2 else 1))
