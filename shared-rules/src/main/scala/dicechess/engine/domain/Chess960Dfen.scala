// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

import dicechess.engine.movegen.MoveGenerator

import scala.util.boundary, boundary.break

/** A Chess960 notation snapshot. Each surviving right identifies a specific rook square, including when another rook
  * stands farther from the king on the same side. Move generation takes a classical GameState.
  */
final class Chess960Dfen private (
    private[engine] val position: GameState,
    val castlingRooks: Bitboard,
    private val rightsField: String
):
  /** Rook origins for pseudo-legal castling micro-moves under the current roll. */
  def availableCastlingRooks: List[Square] = castles.map(_.rook)

  /** Executes one rolled micro-move. The active player can spend any remaining die before ending the turn. */
  def castle(rook: Square): Either[String, Chess960Dfen] =
    castles
      .find(_.rook == rook)
      .toRight("Castling unavailable for selected rook")
      .map(playCastle)

  private def playCastle(move: Chess960Dfen.Castle): Chess960Dfen =
    val after           = afterCastle(move)
    val color           = position.activeColor
    val own             = if color.isWhite then position.whitePieces else position.blackPieces
    val remainingRights = rightsField.filter(c => if color.isWhite then c.isLower else c.isUpper)
    new Chess960Dfen(after, castlingRooks & ~own, if remainingRights.isEmpty then "-" else remainingRights)

  private[engine] def afterCastle(move: Chess960Dfen.Castle): GameState =
    val rook    = move.rook
    val color   = position.activeColor
    val mailbox = position.mailbox.toArray
    mailbox(move.king.index) = Piece.Empty
    mailbox(rook.index) = Piece.Empty
    mailbox(move.kingTo.index) = Piece(color, PieceType.King)
    mailbox(move.rookTo.index) = Piece(color, PieceType.Rook)
    val own   = if color.isWhite then position.whitePieces else position.blackPieces
    val moved = own.remove(move.king).remove(rook).add(move.kingTo).add(move.rookTo)
    position.copy(
      whitePieces = if color.isWhite then moved else position.whitePieces,
      blackPieces = if color.isBlack then moved else position.blackPieces,
      kings = position.kings.remove(move.king).add(move.kingTo),
      rooks = position.rooks.remove(rook).add(move.rookTo),
      mailbox = Mailbox.fromBuilder(mailbox),
      flags = move.remaining.withHalfMoveClock(position.halfMoveClock + 1),
      zobristKey = 0L
    )

  private[engine] def play(move: Move): Either[String, Chess960Dfen] =
    if move.isCastling then castle(move.toSquare)
    else
      val remaining = Chess960Dfen.remainingDice(position, move, allowUnrolled = true)
      if !remaining.isValid then Left("The roll does not contain a die for this move")
      else Chess960Dfen.fromRookSquares(position.makeMove(move).withDiceSlotsOf(remaining), rightsAfter(move))

  private def rightsAfter(move: Move): Bitboard =
    val own           = if position.activeColor.isWhite then position.whitePieces else position.blackPieces
    val unmoved       = castlingRooks.remove(move.fromSquare).remove(move.toSquare)
    val afterKingMove = if position.kings.contains(move.fromSquare) then unmoved & ~own else unmoved
    if position.isKingCapture(move) then afterKingMove & own else afterKingMove

  private[engine] def hasBothKings: Boolean =
    !(position.kings & position.whitePieces).isEmpty && !(position.kings & position.blackPieces).isEmpty

  private[engine] def completedDfen: Either[String, String] =
    serializeCompleted(if hasBothKings then position.endTurn() else position.withDicePool(Nil))

  private def serializeCompleted(finalBoard: GameState): Either[String, String] =
    Either.cond(
      finalBoard.fullMoveNumber > 0,
      FenParser.serializeFields(finalBoard, Some(rightsField)),
      "The next full-move number exceeds the DFEN range"
    )

  private def castles = Chess960Dfen.castles(position, castlingRooks)

  def serialize: String = FenParser.serializeFields(position, Some(rightsField))

object Chess960Dfen:
  final private[engine] case class Castle(
      king: Square,
      rook: Square,
      kingTo: Square,
      rookTo: Square,
      remaining: GameFlags
  ):
    def encoded: Move = Move(king, rook, if rook.file > king.file then Move.KingCastle else Move.QueenCastle)

  private[engine] def castles(position: GameState, castlingRooks: Bitboard): List[Castle] =
    castles(position, castlingRooks, allowUnrolled = false)

  private def castles(position: GameState, castlingRooks: Bitboard, allowUnrolled: Boolean): List[Castle] =
    val white = position.activeColor.isWhite
    val home  = if white then 1 else 8
    val files = ('a' to 'h').map(Square(_, home))
    val own   = if white then position.whitePieces else position.blackPieces
    val king  = files.find(sq => (position.kings & own).contains(sq))

    for
      from <- king.toList
      rook <- files.filter(castlingRooks.contains).toList
      short     = rook.file > from.file
      kingTo    = Square(if short then 'g' else 'c', home)
      rookTo    = Square(if short then 'f' else 'd', home)
      move      = Move(from, kingTo, if short then Move.KingCastle else Move.QueenCastle)
      remaining = remainingDice(position, move, allowUnrolled)
      if remaining.isValid
      blockers = (position.whitePieces | position.blackPieces).remove(from).remove(rook)
      if clear(from, kingTo, blockers) && clear(rook, rookTo, blockers)
    yield Chess960Dfen.Castle(from, rook, kingTo, rookTo, remaining)

  private def clear(from: Square, to: Square, blockers: Bitboard): Boolean =
    val low  = from.index.min(to.index)
    val high = from.index.max(to.index)
    (low to high).forall(i => !blockers.contains(Square.fromIndex(i)))

  private def remainingDice(position: GameState, move: Move, allowUnrolled: Boolean): GameFlags =
    if allowUnrolled && position.flags.isDicePoolEmpty then position.flags else position.diceAfter(move)

  /** Applies a rolled Chess960 castle using the eligible rook's algebraic square. */
  def applyCastle(dfen: String, rook: String): Either[String, String] =
    for
      square   <- Option(rook).flatMap(Square.fromNotation).toRight("Invalid castling rook square")
      snapshot <- parse(dfen)
      result   <- snapshot.castle(square)
    yield result.serialize

  /** Applies one Chess960 micro-move. Castling uses the rook origin as the destination. */
  def applyMove(dfen: String, from: String, to: String, promotion: Option[String]): Either[String, String] =
    for
      snapshot <- parse(dfen)
      fromSq   <- Option(from).flatMap(Square.fromNotation).toRight("Invalid origin square")
      toSq     <- Option(to).flatMap(Square.fromNotation).toRight("Invalid destination square")
      after    <- playMicroMove(snapshot, fromSq, toSq, promotion)
    yield after.serialize

  private def playMicroMove(
      snapshot: Chess960Dfen,
      from: Square,
      to: Square,
      promotion: Option[String]
  ): Either[String, Chess960Dfen] =
    val board  = snapshot.position
    val castle = castles(board, snapshot.castlingRooks, allowUnrolled = true)
      .find(move => move.king == from && move.rook == to)
    castle match
      case Some(move) => Right(snapshot.playCastle(move))
      case None       =>
        val mover = board.mailbox(from).pieceType
        val move  = MoveGenerator
          .generatePieceMoves(board, mover)
          .find: m =>
            m.fromSquare == from && m.toSquare == to &&
              (!m.isPromotion || promotion.forall(p => m.promotionPieceType.exists(_.asNotation == p)))
        move.toRight("Invalid Chess960 micro-move").flatMap(snapshot.play)

  def endTurn(dfen: String): Either[String, String] =
    parse(dfen).flatMap(snapshot => snapshot.serializeCompleted(snapshot.position.endTurn()))

  /** The Scharnagl starting position (0 to 959), before White rolls the first turn. */
  def startingPosition(number: Int): Either[String, String] =
    if number < 0 || number >= 960 then Left("Chess960 position number must be between 0 and 959")
    else
      val rank      = Array.fill(8)(' ')
      def openFiles = rank.indices.filter(file => rank(file) == ' ')

      // Successive digits select the light bishop, dark bishop, queen and pair of knights.
      rank(2 * (number % 4) + 1) = 'B'
      rank(2 * ((number / 4) % 4)) = 'B'
      rank(openFiles((number / 16) % 6)) = 'Q'
      val knights = openFiles.combinations(2).drop(number / 96).next()
      knights.foreach(file => rank(file) = 'N')
      openFiles.zip("RKR").foreach((file, piece) => rank(file) = piece)
      val white = rank.mkString
      Right(s"${white.toLowerCase}/pppppppp/8/8/8/8/PPPPPPPP/$white w KQkq - 0 1")

  def parse(dfen: String): Either[String, Chess960Dfen] =
    Option(dfen)
      .toRight("Missing DFEN")
      .flatMap: text =>
        val parts = text.split(" ")
        if parts.length < 4 then Left("Invalid FEN: insufficient parts")
        else
          val rights = parts(2)
          // The board and all other fields follow ordinary DFEN. Resolve rights against the parsed back ranks below.
          parts(2) = "-"
          FenParser.parseFields(parts).flatMap(resolve(_, rights))

  // Resolve explicit origins on the new board: another rook moving onto or off the back rank can
  // change which surviving right may use K/Q shorthand, even when its own rook never moved.
  private def fromRookSquares(position: GameState, rights: Bitboard): Either[String, Chess960Dfen] =
    if rights.isEmpty then Right(new Chess960Dfen(position, rights, "-"))
    else
      val files = for
        rank <- List(1, 8)
        file <- 'a' to 'h'
        if rights.contains(Square(file, rank))
      yield if rank == 1 then file.toUpper else file
      resolve(position, files.mkString)

  private def resolve(position: GameState, field: String): Either[String, Chess960Dfen] = boundary:
    if field == "-" then break(Right(new Chess960Dfen(position, Bitboard.empty, "-")))
    if field.isEmpty || field.length > 4 then break(Left("Invalid Chess960 castling field length"))

    val letters = Array.fill(4)(' ')
    var rights  = Bitboard.empty
    for symbol <- field do
      val white = symbol >= 'A' && symbol <= 'Z'
      val token = symbol.toLower
      if !"KQkqABCDEFGHabcdefgh".contains(symbol) then break(Left(s"Invalid castling character '$symbol'"))

      val backRank = (if white then 0 until 8 else 56 until 64).map(Square.fromIndex)
      val pieces   = if white then position.whitePieces else position.blackPieces
      val kings    = backRank.filter(sq => (pieces & position.kings).contains(sq))
      if kings.size != 1 then break(Left(s"Castling right '$symbol' requires one king on its back rank"))
      val king  = kings.head
      val rooks = backRank.filter(sq => (pieces & position.rooks).contains(sq))
      val left  = rooks.find(_.file < king.file)
      val right = rooks.findLast(_.file > king.file)
      val rook  = token match
        case 'k'  => right
        case 'q'  => left
        case file => rooks.find(_.file == file)
      val square   = rook.getOrElse(break(Left(s"No matching rook for castling right '$symbol'")))
      val kingside = square.file > king.file
      val index    = (if white then 0 else 2) + (if kingside then 0 else 1)
      if letters(index) != ' ' then break(Left(s"Duplicate castling side '$symbol'"))
      val canonical =
        if right.contains(square) then 'k'
        else if left.contains(square) then 'q'
        else square.file
      letters(index) = if white then canonical.toUpper else canonical
      rights = rights.add(square)
    Right(new Chess960Dfen(position, rights, letters.filterNot(_ == ' ').mkString))
