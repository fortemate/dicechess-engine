// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

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
      .map: move =>
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
    val white = position.activeColor.isWhite
    val home  = if white then 1 else 8
    val files = ('a' to 'h').map(Square(_, home))
    val own   = if white then position.whitePieces else position.blackPieces
    val king  = files.find(sq => (position.kings & own).contains(sq))
    def clear(from: Square, to: Square, blockers: Bitboard): Boolean =
      val low  = from.index.min(to.index)
      val high = from.index.max(to.index)
      (low to high).forall(i => !blockers.contains(Square.fromIndex(i)))

    for
      from <- king.toList
      rook <- files.filter(castlingRooks.contains).toList
      short     = rook.file > from.file
      kingTo    = Square(if short then 'g' else 'c', home)
      rookTo    = Square(if short then 'f' else 'd', home)
      move      = Move(from, kingTo, if short then Move.KingCastle else Move.QueenCastle)
      remaining = position.flags.consumeDiceFor(move, PieceType.King)
      if remaining.isValid
      blockers = (position.whitePieces | position.blackPieces).remove(from).remove(rook)
      if clear(from, kingTo, blockers) && clear(rook, rookTo, blockers)
    yield Chess960Dfen.Castle(from, rook, kingTo, rookTo, remaining)

  /** Applies a rolled Chess960 castle using the eligible rook's algebraic square. */
  def applyCastle(dfen: String, rook: String): Either[String, String] =
    for
      square   <- Option(rook).flatMap(Square.fromNotation).toRight("Invalid castling rook square")
      snapshot <- parse(dfen)
      result   <- snapshot.castle(square)
    yield result.serialize

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
