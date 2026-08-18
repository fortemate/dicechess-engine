package dicechess.engine.testutils

import dicechess.engine.domain.{Bitboard, Square}

case class TestPosition(pieces: Map[Char, Bitboard]):
  /** Returns the bitboard for a specific FEN character (e.g., 'P', 'k'). */
  def apply(c: Char): Bitboard = pieces.getOrElse(c, Bitboard.empty)

  /** Returns a bitboard of all occupied squares. */
  def occupied: Bitboard = pieces.values.foldLeft(Bitboard.empty)(_ | _)

object TestBoard:
  /** Parses an 8x8 ASCII board representation into a map of Bitboards. Each character other than '.', '-', and
    * whitespace is treated as a piece.
    */
  def fromAscii(ascii: String): TestPosition =
    val lines = ascii.trim.split('\n').map(_.trim).filter(_.nonEmpty)
    require(lines.length == 8, s"ASCII board must have exactly 8 ranks, but got ${lines.length}")

    var pieces = Map.empty[Char, Bitboard]

    for rankIdx <- 0 until 8 do
      val rankChars = lines(rankIdx).replace(" ", "")
      require(
        rankChars.length == 8,
        s"Rank must have exactly 8 files, but got ${rankChars.length} at rank ${8 - rankIdx}"
      )

      for fileIdx <- 0 until 8 do
        val c = rankChars(fileIdx)
        if c != '.' && c != '-' then
          val sq        = Square((fileIdx + 'a').toChar, 8 - rankIdx)
          val currentBb = pieces.getOrElse(c, Bitboard.empty)
          pieces = pieces.updated(c, currentBb | Bitboard.fromSquare(sq))

    TestPosition(pieces)

  /** Creates a Bitboard from a list of squares in algebraic notation (e.g. "e2", "e4"). */
  def apply(squares: String*): Bitboard =
    squares.foldLeft(Bitboard.empty)((bb, sqStr) => bb | sqStr.bb)

  extension (s: String)
    /** Converts a 2-character algebraic notation string to a Square. */
    def sq: Square = Square.fromNotation(s).getOrElse(sys.error(s"Invalid square: $s"))

    /** Converts a 2-character algebraic notation string to a Bitboard. */
    def bb: Bitboard = Bitboard.fromSquare(s.sq)
