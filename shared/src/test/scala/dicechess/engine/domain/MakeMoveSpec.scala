package dicechess.engine.domain

import munit.FunSuite

class MakeMoveSpec extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).getOrElse(sys.error(s"Failed to parse FEN: $fen"))

  // ── Quiet move ────────────────────────────────────────────────────────────

  test("quiet move updates bitboards and mailbox") {
    val state  = parse(FenParser.InitialPosition)
    val mv     = Move(Square('e', 2), Square('e', 3), Move.QuietMove)
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('e', 2)), None)
    assertEquals(result.mailbox.get(Square('e', 3)), Some(Piece(Color.White, PieceType.Pawn)))
    assertEquals(result.activeColor, Color.White)
    assertEquals(result.enPassant, Bitboard.empty)
  }

  // ── Double pawn push & en passant square ─────────────────────────────────

  test("double pawn push sets en passant square") {
    val state  = parse(FenParser.InitialPosition)
    val mv     = Move(Square('e', 2), Square('e', 4), Move.DoublePawnPush)
    val result = state.makeMove(mv)

    assertEquals(result.enPassant, Bitboard.fromSquare(Square('e', 3)))
    assertEquals(result.mailbox.get(Square('e', 4)), Some(Piece(Color.White, PieceType.Pawn)))
  }

  test("black double pawn push sets en passant square on rank 6") {
    val fen    = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('e', 7), Square('e', 5), Move.DoublePawnPush)
    val result = state.makeMove(mv)

    assertEquals(result.enPassant, Bitboard.fromSquare(Square('e', 6)))
  }

  // ── En passant capture ────────────────────────────────────────────────────

  test("en passant capture removes victim pawn") {
    // White pawn on e5, black pawn just moved d7-d5
    val fen   = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"
    val state = parse(fen)

    val mv     = Move(Square('e', 5), Square('d', 6), Move.EnPassantCapture)
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('d', 5)), None) // victim removed
    assertEquals(result.mailbox.get(Square('e', 5)), None) // attacker gone
    assertEquals(result.mailbox.get(Square('d', 6)), Some(Piece(Color.White, PieceType.Pawn)))
    assertEquals(result.enPassant, Bitboard.empty)
  }

  // ── Capture ───────────────────────────────────────────────────────────────

  test("capture removes enemy piece from bitboards") {
    val fen   = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
    val state = parse(fen)

    val mv     = Move(Square('e', 4), Square('d', 5), Move.Capture)
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('d', 5)), Some(Piece(Color.White, PieceType.Pawn)))
    assertEquals(result.blackPieces.contains(Square('d', 5)), false)
  }

  test("regular capture of a pawn removes its en passant target") {
    // White pawn on d4, EP target is d3. Black knight on f5.
    val fen   = "rnbqkbnr/pppppppp/8/5n2/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1"
    val state = parse(fen)

    assertEquals(state.enPassant, Bitboard.fromSquare(Square('d', 3)))

    // Black knight captures the white pawn on d4
    val mv     = Move(Square('f', 5), Square('d', 4), Move.Capture)
    val result = state.makeMove(mv)

    // The pawn is gone, so the EP target should be removed
    assertEquals(result.enPassant, Bitboard.empty)
  }

  // ── Castling ──────────────────────────────────────────────────────────────

  test("white king-side castling moves king and rook") {
    // King and rook in place, path clear
    val fen   = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1"
    val state = parse(fen)

    val mv     = Move(Square('e', 1), Square('g', 1), Move.KingCastle)
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('g', 1)), Some(Piece(Color.White, PieceType.King)))
    assertEquals(result.mailbox.get(Square('f', 1)), Some(Piece(Color.White, PieceType.Rook)))
    assertEquals(result.mailbox.get(Square('e', 1)), None)
    assertEquals(result.mailbox.get(Square('h', 1)), None)
  }

  test("white queen-side castling moves king and rook") {
    val fen   = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1"
    val state = parse(fen)

    val mv     = Move(Square('e', 1), Square('c', 1), Move.QueenCastle)
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('c', 1)), Some(Piece(Color.White, PieceType.King)))
    assertEquals(result.mailbox.get(Square('d', 1)), Some(Piece(Color.White, PieceType.Rook)))
    assertEquals(result.mailbox.get(Square('e', 1)), None)
    assertEquals(result.mailbox.get(Square('a', 1)), None)
  }

  test("black king-side castling moves king and rook") {
    val fen   = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1"
    val state = parse(fen)

    val mv     = Move(Square('e', 8), Square('g', 8), Move.KingCastle)
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('g', 8)), Some(Piece(Color.Black, PieceType.King)))
    assertEquals(result.mailbox.get(Square('f', 8)), Some(Piece(Color.Black, PieceType.Rook)))
    assertEquals(result.mailbox.get(Square('h', 8)), None)
  }

  // ── Castling rights ───────────────────────────────────────────────────────

  test("king move removes all castling rights for that color") {
    val state  = parse(FenParser.InitialPosition)
    val mv     = Move(Square('e', 1), Square('e', 2), Move.QuietMove)
    val result = state.makeMove(mv)

    assert(!result.castlingRights.contains('K'))
    assert(!result.castlingRights.contains('Q'))
    assert(result.castlingRights.contains('k'))
  }

  test("rook move removes corresponding castling right") {
    val fen    = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('h', 1), Square('h', 2), Move.QuietMove)
    val result = state.makeMove(mv)

    assert(!result.castlingRights.contains('K'))
    assert(result.castlingRights.contains('Q'))
  }

  test("capturing enemy rook removes its castling right") {
    val fen   = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/1B2K2R w Kkq - 0 1"
    val state = parse(fen)
    // Bishop on b1 captures rook on a8 — contrived but tests the rule
    val mv     = Move(Square('b', 1), Square('a', 8), Move.Capture)
    val result = state.makeMove(mv)

    assert(!result.castlingRights.contains('q'))
    assert(result.castlingRights.contains('k'))
  }

  // ── Castling rights: captured king (#591) ─────────────────────────────────
  //
  // Dice Chess ends by capturing the king, so a terminal position must not keep the castling letters of a
  // side that no longer has one. The rule is the capture-side mirror of "a king move clears both rights".

  test("capturing the black king on its home square removes both black castling rights") {
    val fen    = "r3k2r/ppppRppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('e', 7), Square('e', 8), Move.Capture)
    val result = state.makeMove(mv)

    assertEquals(result.castlingRights, "KQ")
  }

  test("capturing the black king away from its home square removes both black castling rights") {
    // A king can be taken on any square, so the rule keys on the captured piece, not on the destination.
    val fen    = "r6r/pppppppp/8/3k4/8/8/PPP1PPPP/R2QK2R w KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('d', 1), Square('d', 5), Move.Capture)
    val result = state.makeMove(mv)

    assertEquals(result.castlingRights, "KQ")
  }

  test("capturing the white king removes both white castling rights") {
    val fen    = "r3k2r/pppppppp/8/8/8/8/PPPPqPPP/R3K2R b KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('e', 2), Square('e', 1), Move.Capture)
    val result = state.makeMove(mv)

    assertEquals(result.castlingRights, "kq")
  }

  test("capturing a piece that is neither king nor corner rook leaves castling rights untouched") {
    val fen    = "r3k2r/pppppppp/8/8/8/2n5/PPPPPPPP/R3K2R w KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('b', 2), Square('c', 3), Move.Capture)
    val result = state.makeMove(mv)

    assertEquals(result.castlingRights, "KQkq")
  }

  test("micro-move capturing the black king removes both black castling rights") {
    val fen    = "r3k2r/ppppRppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1 R"
    val state  = parse(fen)
    val result = state.makeMove(MicroMove(Square('e', 7), Square('e', 8)))

    assertEquals(result.castlingRights, "KQ")
  }

  // ── Half-move clock ───────────────────────────────────────────────────────

  test("pawn move resets half-move clock") {
    val fen    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 10 6"
    val state  = parse(fen)
    val mv     = Move(Square('e', 2), Square('e', 3), Move.QuietMove)
    val result = state.makeMove(mv)

    assertEquals(result.halfMoveClock, 0)
  }

  test("non-pawn quiet move increments half-move clock") {
    val fen    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 4 3"
    val state  = parse(fen)
    val mv     = Move(Square('g', 1), Square('f', 3), Move.QuietMove)
    val result = state.makeMove(mv)

    assertEquals(result.halfMoveClock, 5)
  }

  test("non-pawn quiet move at the clock ceiling saturates instead of wrapping to 0 (#586)") {
    val fen    = s"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - ${GameFlags.MaxHalfMoveClock} 100"
    val state  = parse(fen)
    val mv     = Move(Square('g', 1), Square('f', 3), Move.QuietMove)
    val result = state.makeMove(mv)

    assertEquals(result.halfMoveClock, GameFlags.MaxHalfMoveClock)
  }

  test("pawn move at the clock ceiling still resets to 0 (#586)") {
    val fen    = s"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - ${GameFlags.MaxHalfMoveClock} 100"
    val state  = parse(fen)
    val mv     = Move(Square('e', 2), Square('e', 3), Move.QuietMove)
    val result = state.makeMove(mv)

    assertEquals(result.halfMoveClock, 0)
  }

  test("capture at the clock ceiling still resets to 0 (#586)") {
    val fen    = s"r3k2r/pppppppp/8/8/8/8/PPPPPPPP/1B2K2R w Kkq - ${GameFlags.MaxHalfMoveClock} 100"
    val state  = parse(fen)
    val mv     = Move(Square('b', 1), Square('a', 8), Move.Capture)
    val result = state.makeMove(mv)

    assertEquals(result.halfMoveClock, 0)
  }

  // ── Full-move number ──────────────────────────────────────────────────────

  test("makeMove leaves full-move number unchanged after a black micro-move") {
    val fen    = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('e', 7), Square('e', 6), Move.QuietMove)
    val result = state.makeMove(mv)

    assertEquals(result.fullMoveNumber, 1)
  }

  test("full-move number does not increment after white moves") {
    val state  = parse(FenParser.InitialPosition)
    val mv     = Move(Square('e', 2), Square('e', 3), Move.QuietMove)
    val result = state.makeMove(mv)

    assertEquals(result.fullMoveNumber, 1)
  }

  // ── Promotion piece types ─────────────────────────────────────────────────

  test("rook promotion places rook on target square") {
    val fen    = "8/P7/8/8/8/8/8/4K2k w - - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('a', 7), Square('a', 8), Move.RookPromotion)
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('a', 8)), Some(Piece(Color.White, PieceType.Rook)))
    assertEquals(result.rooks.contains(Square('a', 8)), true)
  }

  test("bishop promotion places bishop on target square") {
    val fen    = "8/P7/8/8/8/8/8/4K2k w - - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('a', 7), Square('a', 8), Move.BishopPromotion)
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('a', 8)), Some(Piece(Color.White, PieceType.Bishop)))
    assertEquals(result.bishops.contains(Square('a', 8)), true)
  }

  test("knight promotion places knight on target square") {
    val fen    = "8/P7/8/8/8/8/8/4K2k w - - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('a', 7), Square('a', 8), Move.KnightPromotion)
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('a', 8)), Some(Piece(Color.White, PieceType.Knight)))
    assertEquals(result.knights.contains(Square('a', 8)), true)
  }

  // ── Castling rights: black pieces ─────────────────────────────────────────

  test("black king move removes black castling rights") {
    val fen    = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('e', 8), Square('e', 7), Move.QuietMove)
    val result = state.makeMove(mv)

    assert(!result.castlingRights.contains('k'))
    assert(!result.castlingRights.contains('q'))
    assert(result.castlingRights.contains('K'))
  }

  test("black a8 rook move removes queen-side black castling right") {
    val fen    = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('a', 8), Square('a', 7), Move.QuietMove)
    val result = state.makeMove(mv)

    assert(!result.castlingRights.contains('q'))
    assert(result.castlingRights.contains('k'))
  }

  test("black h8 rook move removes king-side black castling right") {
    val fen    = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('h', 8), Square('h', 7), Move.QuietMove)
    val result = state.makeMove(mv)

    assert(!result.castlingRights.contains('k'))
    assert(result.castlingRights.contains('q'))
  }

  test("capturing h8 rook removes black king-side castling right") {
    val fen    = "r3k2r/pppppppp/8/8/8/8/PPPPPPP1/R3K2Q w KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('h', 1), Square('h', 8), Move.Capture)
    val result = state.makeMove(mv)

    assert(!result.castlingRights.contains('k'))
    assert(result.castlingRights.contains('q'))
  }

  test("capturing h1 rook removes white king-side castling right") {
    val fen    = "r3k2r/7q/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('h', 7), Square('h', 1), Move.Capture)
    val result = state.makeMove(mv)

    assert(!result.castlingRights.contains('K'))
    assert(result.castlingRights.contains('Q'))
  }

  test("capturing rook at non-corner square does not change castling rights") {
    // Rook has moved from a1 to a4, then gets captured — no castling right removed
    val fen    = "r3k2r/pppppppp/8/8/R7/8/1PPPPPPP/4K2R b Kkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('a', 8), Square('a', 4), Move.Capture)
    val result = state.makeMove(mv)

    assert(result.castlingRights.contains('K'))
    assert(result.castlingRights.contains('k'))
  }

  test("all castling rights gone yields '-'") {
    val fen    = "4k3/pppppppp/8/8/8/8/PPPPPPPP/4K3 w - - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('e', 1), Square('e', 2), Move.QuietMove)
    val result = state.makeMove(mv)

    assertEquals(result.castlingRights, "-")
  }

  // ── Black queen-side castling ─────────────────────────────────────────────

  test("black queen-side castling moves king and rook") {
    val fen    = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1"
    val state  = parse(fen)
    val mv     = Move(Square('e', 8), Square('c', 8), Move.QueenCastle)
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('c', 8)), Some(Piece(Color.Black, PieceType.King)))
    assertEquals(result.mailbox.get(Square('d', 8)), Some(Piece(Color.Black, PieceType.Rook)))
    assertEquals(result.mailbox.get(Square('a', 8)), None)
  }

  // ── makeMove(MicroMove) ───────────────────────────────────────────────────

  test("MicroMove: quiet move updates bitboards") {
    val state  = parse(FenParser.InitialPosition)
    val mv     = MicroMove(Square('e', 2), Square('e', 4))
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('e', 4)), Some(Piece(Color.White, PieceType.Pawn)))
    assertEquals(result.mailbox.get(Square('e', 2)), None)
  }

  test("MicroMove: capture removes enemy piece") {
    val fen    = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
    val state  = parse(fen)
    val mv     = MicroMove(Square('e', 4), Square('d', 5))
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('d', 5)), Some(Piece(Color.White, PieceType.Pawn)))
    assertEquals(result.blackPieces.contains(Square('d', 5)), false)
    assertEquals(result.halfMoveClock, 0)
  }

  test("MicroMove: regular capture of a pawn removes its en passant target") {
    // White pawn on d4, EP target is d3. Black knight on f5.
    val fen   = "rnbqkbnr/pppppppp/8/5n2/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1"
    val state = parse(fen)

    assertEquals(state.enPassant, Bitboard.fromSquare(Square('d', 3)))

    // Black knight captures the white pawn on d4
    val mv     = MicroMove(Square('f', 5), Square('d', 4))
    val result = state.makeMove(mv)

    // The pawn is gone, so the EP target should be removed
    assertEquals(result.enPassant, Bitboard.empty)
  }

  test("MicroMove: promotion replaces pawn with promoted piece") {
    val fen    = "8/P7/8/8/8/8/8/4K2k w - - 0 1"
    val state  = parse(fen)
    val mv     = MicroMove(Square('a', 7), Square('a', 8), Some(PieceType.Queen))
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('a', 8)), Some(Piece(Color.White, PieceType.Queen)))
    assertEquals(result.pawns.contains(Square('a', 8)), false)
    assertEquals(result.queens.contains(Square('a', 8)), true)
  }

  test("MicroMove: non-pawn non-capture increments half-move clock") {
    val fen    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 3 2"
    val state  = parse(fen)
    val mv     = MicroMove(Square('g', 1), Square('f', 3))
    val result = state.makeMove(mv)

    assertEquals(result.halfMoveClock, 4)
  }

  test("MicroMove: non-pawn non-capture at the clock ceiling saturates instead of wrapping to 0 (#586)") {
    val fen    = s"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - ${GameFlags.MaxHalfMoveClock} 100"
    val state  = parse(fen)
    val mv     = MicroMove(Square('g', 1), Square('f', 3))
    val result = state.makeMove(mv)

    assertEquals(result.halfMoveClock, GameFlags.MaxHalfMoveClock)
  }

  test("MicroMove: pawn move at the clock ceiling still resets to 0 (#586)") {
    val fen    = s"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - ${GameFlags.MaxHalfMoveClock} 100"
    val state  = parse(fen)
    val mv     = MicroMove(Square('e', 2), Square('e', 4))
    val result = state.makeMove(mv)

    assertEquals(result.halfMoveClock, 0)
  }

  test("MicroMove: capture at the clock ceiling still resets to 0 (#586)") {
    val fen    = s"rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - ${GameFlags.MaxHalfMoveClock} 100"
    val state  = parse(fen)
    val mv     = MicroMove(Square('e', 4), Square('d', 5))
    val result = state.makeMove(mv)

    assertEquals(result.halfMoveClock, 0)
  }

  test("MicroMove: multiple double pushes accumulate en-passant squares") {
    val state  = parse(FenParser.InitialPosition)
    val state1 = state.makeMove(MicroMove(Square('a', 2), Square('a', 4)))
    val state2 = state1.makeMove(MicroMove(Square('c', 2), Square('c', 4)))
    val state3 = state2.makeMove(MicroMove(Square('e', 2), Square('e', 4)))

    val expected = Bitboard.fromSquare(Square('a', 3)) |
      Bitboard.fromSquare(Square('c', 3)) |
      Bitboard.fromSquare(Square('e', 3))
    assertEquals(state3.enPassant, expected)
  }

  test("MicroMove: en-passant capture removes victim") {
    val fen    = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"
    val state  = parse(fen)
    val mv     = MicroMove(Square('e', 5), Square('d', 6))
    val result = state.makeMove(mv)

    assertEquals(result.mailbox.get(Square('d', 5)), None) // victim removed
    assertEquals(result.mailbox.get(Square('d', 6)), Some(Piece(Color.White, PieceType.Pawn)))
  }

  test("MicroMove: playing a move consumes the matching die from the dicePool") {
    val fen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PPR"
    val state = parse(fen)
    assertEquals(state.dicePool, List(1, 1, 4))

    // White pawn moves a2a4 (pawn pieceType = 1)
    val state1 = state.makeMove(MicroMove(Square('a', 2), Square('a', 4)))
    assertEquals(state1.dicePool, List(1, 4)) // One '1' consumed

    // White rook moves a1a3 (rook pieceType = 4)
    val state2 = state1.makeMove(MicroMove(Square('a', 1), Square('a', 3)))
    assertEquals(state2.dicePool, List(1)) // The '4' consumed
  }

  test(
    "Turn: multiple double pawn pushes accumulate en-passant squares and expose en-passant captures to opponent in the next turn"
  ) {
    // Custom board position: White pawns on a2, c2, e2; Black pawns on b4, d4.
    val fen   = "rnbqkbnr/p1p1p1pp/8/8/1p1p4/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PPP"
    val state = parse(fen)

    // White rolls pawn (1), pawn (1), pawn (1)
    assertEquals(state.dicePool, List(1, 1, 1))

    // 1st micro-move: a2a4
    val state1 = state.makeMove(MicroMove(Square('a', 2), Square('a', 4)))
    assertEquals(state1.enPassant, Bitboard.fromSquare(Square('a', 3)))

    // 2nd micro-move: c2c4
    val state2 = state1.makeMove(MicroMove(Square('c', 2), Square('c', 4)))
    assertEquals(
      state2.enPassant,
      Bitboard.fromSquare(Square('a', 3)) | Bitboard.fromSquare(Square('c', 3))
    )

    // 3rd micro-move: e2e4
    val state3 = state2.makeMove(MicroMove(Square('e', 2), Square('e', 4)))
    assertEquals(
      state3.enPassant,
      Bitboard.fromSquare(Square('a', 3)) | Bitboard.fromSquare(Square('c', 3)) | Bitboard.fromSquare(Square('e', 3))
    )

    // Transition state to Black's turn: activeColor = Black, dicePool = Nil
    val finalState = state3.withActiveColor(Color.Black)
    assertEquals(finalState.activeColor, Color.Black)

    // Verify enPassant bitboard contains all three targets on rank 3
    val expectedEP = Bitboard.fromSquare(Square('a', 3)) |
      Bitboard.fromSquare(Square('c', 3)) |
      Bitboard.fromSquare(Square('e', 3))
    assertEquals(finalState.enPassant, expectedEP)
  }

  test(
    "Turn: multiple double pawn pushes followed by a quiet micro-move preserves newly created en-passant squares"
  ) {
    val fen   = "rnbqkbnr/pppppppp/8/8/8/8/P3P3/RNBQKBNR w KQkq - 0 1 PPN"
    val state = parse(fen)

    assertEquals(state.dicePool, List(1, 1, 2))

    // 1st micro-move: a2a4 (double pawn push, creates a3)
    val state1 = state.makeMove(MicroMove(Square('a', 2), Square('a', 4)))
    assertEquals(state1.enPassant, Bitboard.fromSquare(Square('a', 3)))

    // 2nd micro-move: e2e4 (double pawn push, creates e3)
    val state2 = state1.makeMove(MicroMove(Square('e', 2), Square('e', 4)))
    assertEquals(
      state2.enPassant,
      Bitboard.fromSquare(Square('a', 3)) | Bitboard.fromSquare(Square('e', 3))
    )

    // 3rd micro-move: g1f3 (quiet knight move)
    val state3 = state2.makeMove(MicroMove(Square('g', 1), Square('f', 3)))

    // Both a3 and e3 must be preserved
    val expectedEP = Bitboard.fromSquare(Square('a', 3)) | Bitboard.fromSquare(Square('e', 3))
    assertEquals(state3.enPassant, expectedEP)
  }
