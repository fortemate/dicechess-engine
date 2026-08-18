package dicechess.engine.cli

import dicechess.engine.domain.FenParser
import munit.FunSuite

class BoardPrinterSpec extends FunSuite:

  test("BoardPrinter renders starting position correctly in ASCII"):
    val fen    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    val state  = FenParser.parse(fen).toOption.get
    val output = BoardPrinter.printBoard(state, useUnicode = false)

    val expectedPart = """
  +------------------------+
8 | r  n  b  q  k  b  n  r |
7 | p  p  p  p  p  p  p  p |
6 | .  .  .  .  .  .  .  . |
5 | .  .  .  .  .  .  .  . |
4 | .  .  .  .  .  .  .  . |
3 | .  .  .  .  .  .  .  . |
2 | P  P  P  P  P  P  P  P |
1 | R  N  B  Q  K  B  N  R |
  +------------------------+
    a  b  c  d  e  f  g  h
""".trim

    assert(output.contains(expectedPart))

  test("BoardPrinter renders starting position correctly in Unicode"):
    val fen    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    val state  = FenParser.parse(fen).toOption.get
    val output = BoardPrinter.printBoard(state, useUnicode = true)

    val expectedPart = """
  +------------------------+
8 | ♜  ♞  ♝  ♛  ♚  ♝  ♞  ♜ |
7 | ♟  ♟  ♟  ♟  ♟  ♟  ♟  ♟ |
6 | .  .  .  .  .  .  .  . |
5 | .  .  .  .  .  .  .  . |
4 | .  .  .  .  .  .  .  . |
3 | .  .  .  .  .  .  .  . |
2 | ♙  ♙  ♙  ♙  ♙  ♙  ♙  ♙ |
1 | ♖  ♘  ♗  ♕  ♔  ♗  ♘  ♖ |
  +------------------------+
    a  b  c  d  e  f  g  h
""".trim

    assert(output.contains(expectedPart))
