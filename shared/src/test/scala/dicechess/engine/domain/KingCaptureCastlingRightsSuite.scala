package dicechess.engine.domain

import munit.FunSuite

/** Regression tests for issue #591: castling rights must not survive the capture of the king.
  *
  * Dice Chess ends by capturing the king, a move standard chess does not have, so no FEN rule covers it.
  * [[Position.updatedCastlingRights]] used to revoke rights only for a captured *rook* and for a *moving* king or rook,
  * which left the losing side's letters in the terminal FEN: a position claiming a castling right that no legal move
  * could ever exercise. Measured on the analytics corpus (2026-08-09) that was 214,714 of 20.8M positions, 18,328 of
  * the affected games replayed by this very engine — current output, not legacy scrape residue.
  *
  * The replay below is the shortest realistic line to a king capture, driven micro-move by micro-move exactly the way
  * an ingest replay drives it, and asserts the castling field of the DFEN the game ends on.
  *
  * There is deliberately no `KingCaptureProbability.captureDFS` fixture here. Measured while fixing this issue, a
  * position whose king has been captured cannot reach the non-termination of #549: `MoveGenerator` emits castling moves
  * from inside its per-king loop, so a side with no king on the board is never offered one, stale rights or not. The
  * looping shape needs a king that is present but off its home square — the separate class #549 covers.
  */
class KingCaptureCastlingRightsSuite extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)

  private def castlingField(state: GameState): String =
    FenParser.serialize(state).split(" ")(2)

  test("a game ending in king capture leaves no castling rights for the captured king's color") {
    // Turn 1 — White rolls Pawn, Pawn, Queen.
    val start  = parse(FenParser.InitialPosition).withDicePool(List(1, 1, 5))
    val white1 = start
      .makeMove(MicroMove(Square('e', 2), Square('e', 4)))
      .makeMove(MicroMove(Square('a', 2), Square('a', 3)))
      .makeMove(MicroMove(Square('d', 1), Square('h', 5)))
      .endTurn()

    assertEquals(castlingField(white1), "KQkq")

    // Turn 2 — Black rolls three Pawns and vacates f7, opening the h5-e8 diagonal.
    val black1 = white1
      .withDicePool(List(1, 1, 1))
      .makeMove(MicroMove(Square('f', 7), Square('f', 5)))
      .makeMove(MicroMove(Square('a', 7), Square('a', 6)))
      .makeMove(MicroMove(Square('h', 7), Square('h', 6)))
      .endTurn()

    assertEquals(castlingField(black1), "KQkq")

    // Turn 3 — White rolls a Queen and takes the king on e8; the game ends on this micro-move.
    val terminal = black1
      .withDicePool(List(5))
      .makeMove(MicroMove(Square('h', 5), Square('e', 8)))

    assertEquals(terminal.mailbox.get(Square('e', 8)), Some(Piece(Color.White, PieceType.Queen)))
    assertEquals(terminal.kings & terminal.blackPieces, Bitboard.empty)
    assertEquals(castlingField(terminal), "KQ")
  }

  test("a king captured away from its home square also forfeits the rights") {
    // The FEN a mid-game resignation-by-king-capture ends on: the rooks are still home, so the rights are
    // consistent right up until the king comes off the board.
    val terminal = parse("r6r/pppppppp/8/3k4/8/8/PPP1PPPP/R2QK2R w KQkq - 0 1")
      .withDicePool(List(5))
      .makeMove(MicroMove(Square('d', 1), Square('d', 5)))

    assertEquals(terminal.kings & terminal.blackPieces, Bitboard.empty)
    assertEquals(castlingField(terminal), "KQ")
  }
