// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite

/** Regression tests for issue #351: en passant must stay legal beyond the first micro-move of a turn.
  *
  * In Dice Chess a turn is up to three micro-moves, and the en-passant target set by the opponent's pawn double-step
  * (present in the incoming FEN) stays capturable throughout the whole turn — not only on the first micro-move. The
  * target's expiry happens once at the turn boundary in GameState.endTurn (clearEnPassant), not in the per-micro-move
  * makeMove, so [[TurnGenerator.generateAllLegalTurnPaths]] offers the en-passant capture on any micro-move. Before the
  * #351 fix the engine dropped the target after the first intra-turn micro-move, wrongly rejecting real dicechess.com
  * games that played the capture as the 2nd/3rd micro-move (422 IllegalTurn in dicechess-analytics).
  *
  * The first two positions and dice below are the reproductions recorded in issue #351.
  */
class EnPassantMicroMoveSuite extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)

  /** True if some legal turn path ends with an en-passant capture (i.e. ep is the LAST micro-move). */
  private def hasEnPassantLast(paths: List[List[Move]]): Boolean =
    paths.exists(path => path.lastOption.exists(_.isEnPassant))

  /** True if some legal turn path plays an en-passant capture exactly at micro-move index `slot`. */
  private def hasEnPassantAt(paths: List[List[Move]], slot: Int): Boolean =
    paths.exists(path => path.lift(slot).exists(_.isEnPassant))

  test("en passant is legal as the LAST micro-move (#351 case 1: ep d3, dice P/Q/Q)") {
    // Black to move, en-passant target d3. The real game played [d8d6, d6c6, e4d3] (ep capture
    // last) and dicechess.com accepted it; the engine must offer ep beyond the first micro-move.
    val state = parse("r2q2r1/ppp1nk1p/2B2p2/3p3b/1P1Pp2P/4P2N/PP3P2/RNB4K b - d3 0 7")
      .withDicePool(List(1, 5, 5))
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    assert(paths.exists(_.exists(_.isEnPassant)), "en-passant capture e4d3 is not generated at all")
    assert(hasEnPassantLast(paths), "en passant is not generated as the last micro-move of a legal turn (#351)")
  }

  test("en passant is legal as the 2nd micro-move (#351 case 2: ep d3, dice P/N/R)") {
    // Black to move, en-passant target d3. The real game played [b8c8, e4d3, g8f6] (ep capture
    // as the 2nd micro-move) and dicechess.com accepted it.
    val state = parse("1rNk2nr/1ppp1ppp/4q3/2b5/1n1Pp3/5N2/PPPQPPPP/1RBK1B1R b - d3 2 4")
      .withDicePool(List(1, 2, 4))
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    assert(paths.exists(_.exists(_.isEnPassant)), "en-passant capture e4d3 is not generated at all")
    assert(hasEnPassantAt(paths, 1), "en passant is not generated as the 2nd micro-move (#351)")
  }

  test("an occupied en-passant target can be vacated before the capture") {
    // White played a2-a4 and Ra1-a3 in the same turn. Initially b4-a3 is only a regular capture of the rook:
    // the pawn on a4 must survive. With Black's N/N/P dice, however, Nc4xa3 followed by Na3-c4 vacates the
    // inherited target again, so b4xa3 must then be generated as en passant and remove the a4 pawn.
    val state = parse("r1bqkbnr/p1pppp1p/8/8/Ppn3pP/R6R/1PPPPPP1/1NBQKBN1 b kq a3 0 2")
      .withDicePool(List(2, 2, 1))

    val initialPawnCaptures = MoveGenerator
      .generateMoves(state.withDicePool(List(1)))
      .filter(move => move.fromSquare == Square('b', 4) && move.toSquare == Square('a', 3))
    assertEquals(initialPawnCaptures.size, 1, "occupied a3 must offer exactly one b4a3 capture")
    assert(!initialPawnCaptures.head.isEnPassant, "b4a3 must capture the rook normally while a3 is occupied")

    val expectedUci = List("c4a3", "a3c4", "b4a3")
    val path        = TurnGenerator
      .generateAllLegalTurnPaths(state)
      .find(_.map(_.toUci) == expectedUci)
      .getOrElse(fail(s"missing legal turn path ${expectedUci.mkString(" ")}"))

    assert(path.last.isEnPassant, "the final b4a3 move must be an en-passant capture")
    val result = path.foldLeft(state)((position, move) => position.makeMove(move))
    assertEquals(result.mailbox(Square('a', 3)).pieceType, PieceType.Pawn)
    assertEquals(result.mailbox(Square('a', 4)), Piece.Empty)
    assertEquals(result.mailbox(Square('c', 4)).pieceType, PieceType.Knight)
  }
