package dicechess.engine.cli

import dicechess.engine.domain.{GameState, PieceType, Square}

/** Renders [[dicechess.engine.domain.GameState]] boards to the terminal.
  *
  * The printer supports both ASCII and Unicode rendering modes because some environments (like Windows cmd.exe or
  * non-UTF8 terminals) cannot reliably display chess glyphs, requiring a standard ASCII fallback for compatibility.
  */
object BoardPrinter:
  def printBoard(state: GameState, useUnicode: Boolean): String =
    val sb = new StringBuilder()
    sb.append("\n  +------------------------+\n")
    for rank <- 7 to 0 by -1 do
      sb.append(s"${rank + 1} |")
      for file <- 0 to 7 do
        val sq    = Square((file + 'a').toChar, rank + 1)
        val piece = state.mailbox(sq)
        if piece.isEmpty then sb.append(" . ")
        else
          val pt      = piece.pieceType
          val isWhite = piece.color.isWhite
          val char    = if useUnicode then getUnicodeChar(pt, isWhite)
          else getAsciiChar(pt, isWhite)
          sb.append(s" $char ")
      sb.append("|\n")
    sb.append("  +------------------------+\n")
    sb.append("    a  b  c  d  e  f  g  h\n")
    sb.toString()

  private def getAsciiChar(pt: PieceType, isWhite: Boolean): Char =
    val char = pt match
      case PieceType.Pawn   => 'p'
      case PieceType.Knight => 'n'
      case PieceType.Bishop => 'b'
      case PieceType.Rook   => 'r'
      case PieceType.Queen  => 'q'
      case PieceType.King   => 'k'
    if isWhite then char.toUpper else char

  private def getUnicodeChar(pt: PieceType, isWhite: Boolean): Char =
    if isWhite then
      pt match
        case PieceType.Pawn   => '♙'
        case PieceType.Knight => '♘'
        case PieceType.Bishop => '♗'
        case PieceType.Rook   => '♖'
        case PieceType.Queen  => '♕'
        case PieceType.King   => '♔'
    else
      pt match
        case PieceType.Pawn   => '♟'
        case PieceType.Knight => '♞'
        case PieceType.Bishop => '♝'
        case PieceType.Rook   => '♜'
        case PieceType.Queen  => '♛'
        case PieceType.King   => '♚'
