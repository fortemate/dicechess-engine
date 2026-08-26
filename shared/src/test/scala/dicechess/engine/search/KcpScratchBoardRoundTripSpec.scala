package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite
import scala.util.Random

/** Exhaustive round-trip tests for [[KcpScratchBoard]].
  *
  * Verifies that for any move:
  *   1. `scratch.makeMoveInPlace(mv)` produces a board bit-identical to `state.makeMove(mv)` across all bitboards,
  *      mailbox squares, castling rights, EP targets, and flags.
  *   2. `scratch.undoMove(mv, undo)` restores the board bit-identical to the original `state`.
  *   3. Multi-move nested sequences and random walks restore the root board perfectly.
  */
class KcpScratchBoardRoundTripSpec extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN $fen: $err"), identity)

  private def assertStateEquals(actual: GameState, expected: GameState, context: String): Unit =
    assertEquals(actual.whitePieces, expected.whitePieces, s"whitePieces diverged in $context")
    assertEquals(actual.blackPieces, expected.blackPieces, s"blackPieces diverged in $context")
    assertEquals(actual.pawns, expected.pawns, s"pawns diverged in $context")
    assertEquals(actual.knights, expected.knights, s"knights diverged in $context")
    assertEquals(actual.bishops, expected.bishops, s"bishops diverged in $context")
    assertEquals(actual.rooks, expected.rooks, s"rooks diverged in $context")
    assertEquals(actual.queens, expected.queens, s"queens diverged in $context")
    assertEquals(actual.kings, expected.kings, s"kings diverged in $context")
    assertEquals(actual.enPassant, expected.enPassant, s"enPassant diverged in $context")
    assertEquals(actual.flags, expected.flags, s"flags diverged in $context")
    assertEquals(actual.fullMoveNumber, expected.fullMoveNumber, s"fullMoveNumber diverged in $context")
    assert(
      java.util.Arrays.equals(
        actual.mailbox.asInstanceOf[Array[Int]],
        expected.mailbox.asInstanceOf[Array[Int]]
      ),
      s"mailbox diverged in $context"
    )

  private def testAllMovesRoundTrip(state: GameState, label: String): Unit =
    val scratch = KcpScratchBoard.fromGameState(state)
    val moves   = MoveGenerator.generateAllMoves(state)
    assert(moves.nonEmpty, s"Expected moves for $label")

    for move <- moves do
      val undo       = scratch.makeMoveInPlace(move)
      val afterState = state.makeMove(move)

      // Forward assertion
      assertStateEquals(scratch.toGameState, afterState, s"$label -> makeMove($move)")

      // Undo assertion
      scratch.undoMove(move, undo)
      assertStateEquals(scratch.toGameState, state, s"$label -> undoMove($move)")

  test("initial position all moves make/undo round-trip"):
    testAllMovesRoundTrip(parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"), "initial")

  test("kiwipete all moves make/undo round-trip"):
    testAllMovesRoundTrip(
      parse("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"),
      "kiwipete"
    )

  test("castling rights and execution round-trip (White & Black, king-side and queen-side)"):
    val fen   = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
    val state = parse(fen)
    testAllMovesRoundTrip(state, "castling white")
    testAllMovesRoundTrip(state.withActiveColor(Color.Black), "castling black")

  test("en-passant capture and double-push round-trip"):
    // White pawn on e5, Black pawn on d7 can double-push to d5, creating an EP target on d6.
    val fen1 = "8/3p4/8/4P3/8/8/8/4K2k b - - 0 1"
    testAllMovesRoundTrip(parse(fen1), "ep double push black")

    // White pawn on e5, Black pawn on d5, EP target on d6. White can capture d6 via e.p.
    val fen2 = "8/8/8/3pP3/8/8/8/4K2k w - d6 0 1"
    testAllMovesRoundTrip(parse(fen2), "ep capture white")

    // Black pawn on d4, White pawn on e4, EP target on e3. Black can capture e3 via e.p.
    val fen3 = "4k2K/8/8/8/3pP3/8/8/8 b - e3 0 1"
    testAllMovesRoundTrip(parse(fen3), "ep capture black")

  test("pawn promotions (all 4 types, quiet and captures) round-trip"):
    // White pawn on a7 with black rook on b8 and empty a8
    val fenWhite = "1r5k/P7/8/8/8/8/8/4K3 w - - 0 1"
    testAllMovesRoundTrip(parse(fenWhite), "white promotions")

    // Black pawn on a2 with white rook on b1 and empty a1
    val fenBlack = "4k3/8/8/8/8/8/p7/1R5K b - - 0 1"
    testAllMovesRoundTrip(parse(fenBlack), "black promotions")

  test("nested 3-ply DFS make/undo tree matches expected states"):
    val root    = parse("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
    val scratch = KcpScratchBoard.fromGameState(root)

    def dfs(depth: Int, current: GameState): Unit =
      if depth > 0 then
        val moves = MoveGenerator.generateAllMoves(current).take(5)
        for move <- moves do
          val undo   = scratch.makeMoveInPlace(move)
          val nextSt = current.makeMove(move)
          assertStateEquals(scratch.toGameState, nextSt, s"dfs depth $depth makeMove $move")
          dfs(depth - 1, nextSt)
          scratch.undoMove(move, undo)
          assertStateEquals(scratch.toGameState, current, s"dfs depth $depth undoMove $move")

    dfs(3, root)
    assertStateEquals(scratch.toGameState, root, "dfs completed")

  test("seeded random walks maintain exact state on make and undo"):
    val rng       = new Random(42)
    val positions = List(
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
      "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
      "r1b1k2r/pp1p1ppp/2n1pn2/8/1bP5/2N1PN2/PP1B1PPP/R2QKB1R w KQkq - 0 8",
      "8/8/8/8/8/8/1p6/Q7 b - - 0 1"
    )

    for (fen, posIdx) <- positions.zipWithIndex do
      val root    = parse(fen)
      val scratch = KcpScratchBoard.fromGameState(root)

      for walk <- 1 to 20 do
        var current    = root
        var undoStack  = List.empty[(Move, KcpUndoInfo)]
        val walkLength = 4

        for step <- 1 to walkLength do
          val moves = MoveGenerator.generateAllMoves(current)
          if moves.nonEmpty then
            val move = moves(rng.nextInt(moves.length))
            val undo = scratch.makeMoveInPlace(move)
            current = current.makeMove(move)
            assertStateEquals(scratch.toGameState, current, s"pos $posIdx walk $walk step $step")
            undoStack = (move, undo) :: undoStack

        while undoStack.nonEmpty do
          val (mv, undo) = undoStack.head
          undoStack = undoStack.tail
          scratch.undoMove(mv, undo)

        assertStateEquals(scratch.toGameState, root, s"pos $posIdx walk $walk fully unwound")
