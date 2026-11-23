// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

import scala.util.boundary, boundary.break

/** A Chess960 notation snapshot. Each surviving right identifies a specific rook square, including when another rook
  * stands farther from the king on the same side. Move generation takes a classical GameState.
  */
final class Chess960Dfen private (
    private val position: GameState,
    val castlingRooks: Bitboard,
    private val rightsField: String
):
  def serialize: String = FenParser.serializeFields(position, Some(rightsField))

object Chess960Dfen:
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
