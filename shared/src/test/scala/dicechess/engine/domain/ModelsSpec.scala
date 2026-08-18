package dicechess.engine.domain

import munit.FunSuite

class ModelsSpec extends FunSuite:

  test("Color.opponent should toggle correctly using bitwise logic") {
    assertEquals(Color.White.opponent, Color.Black)
    assertEquals(Color.Black.opponent, Color.White)
    assert(Color.White.isWhite)
    assert(Color.Black.isBlack)
  }

  test("PieceType.fromDice should map values correctly") {
    assertEquals(PieceType.fromDice(1), Some(PieceType.Pawn))
    assertEquals(PieceType.fromDice(6), Some(PieceType.King))
    assertEquals(PieceType.fromDice(0), None)
  }

  test("Piece packing should preserve both Color and PieceType perfectly") {
    val whitePawn = Piece(Color.White, PieceType.Pawn)
    assertEquals(whitePawn.color, Color.White)
    assertEquals(whitePawn.pieceType, PieceType.Pawn)

    val blackKnight = Piece(Color.Black, PieceType.Knight)
    assertEquals(blackKnight.color, Color.Black)
    assertEquals(blackKnight.pieceType, PieceType.Knight)

    val blackKing = Piece(Color.Black, PieceType.King)
    assertEquals(blackKing.color, Color.Black)
    assertEquals(blackKing.pieceType, PieceType.King)
  }

  test("Square index calculations and notation mapping must be bidirectional") {
    val a1 = Square('a', 1)
    assertEquals(a1.index, 0)
    assertEquals(a1.file, 'a')
    assertEquals(a1.rank, 1)

    val h8 = Square('h', 8)
    assertEquals(h8.index, 63)
    assertEquals(h8.file, 'h')
    assertEquals(h8.rank, 8)

    val e4 = Square.fromIndex(28) // ((4-1)*8) + (5-1) = 24 + 4 = 28
    assertEquals(e4.file, 'e')
    assertEquals(e4.rank, 4)
  }

  test("Square.fromNotation should successfully parse valid coordinates") {
    val maybeSquare = Square.fromNotation("e4")
    assert(maybeSquare.isDefined)
    assertEquals(maybeSquare.get.file, 'e')
    assertEquals(maybeSquare.get.rank, 4)
  }

  test("Square.fromNotation should return None for invalid coordinates") {
    assert(Square.fromNotation("i9").isEmpty)
    assert(Square.fromNotation("a").isEmpty)
    assert(Square.fromNotation("e10").isEmpty)
  }

  test("MicroMove.toNotation should format basic moves correctly") {
    val from = Square('g', 1)
    val to   = Square('f', 3)
    val move = MicroMove(from, to)
    assertEquals(move.toNotation, "g1f3")
  }

  test("MicroMove packing should preserve origin, destination, and promotion piece") {
    val from = Square('e', 7)
    val to   = Square('e', 8)
    val move = MicroMove(from, to, Some(PieceType.Queen))

    assertEquals(move.from.toNotation, "e7")
    assertEquals(move.to.toNotation, "e8")
    assertEquals(move.promotion, Some(PieceType.Queen))
    assertEquals(move.toNotation, "e7e8q")
  }

  test("Bitboard properties: empty, full, add, remove, and contains") {
    val empty = Bitboard.empty
    assert(empty.isEmpty)
    assertEquals(empty.count, 0)

    val a1 = Square('a', 1)
    val h8 = Square('h', 8)

    val b1 = empty.add(a1)
    assert(b1.contains(a1))
    assert(!b1.contains(h8))
    assertEquals(b1.count, 1)

    val b2 = b1.add(h8)
    assert(b2.contains(a1))
    assert(b2.contains(h8))
    assertEquals(b2.count, 2)

    val b3 = b2.remove(a1)
    assert(!b3.contains(a1))
    assert(b3.contains(h8))
    assertEquals(b3.count, 1)

    val full = Bitboard.full
    assertEquals(full.count, 64)
    assert(full.contains(a1) && full.contains(h8))
  }

  test("Bitboard bitwise operations (AND, OR, XOR, NOT)") {
    val sq1 = Bitboard.fromSquare(Square('e', 4))
    val sq2 = Bitboard.fromSquare(Square('d', 5))

    val union = sq1 | sq2
    assert(union.contains(Square('e', 4)))
    assert(union.contains(Square('d', 5)))
    assertEquals(union.count, 2)

    val intersection = union & sq1
    assertEquals(intersection, sq1)

    val symDiff = union ^ sq1
    assertEquals(symDiff, sq2)

    val complement = ~union
    assert(!complement.contains(Square('e', 4)))
    assert(!complement.contains(Square('d', 5)))
    assertEquals(complement.count, 62)
  }

  test("GameState.clearEnPassant should correctly wipe out active player's targets while preserving opponent's") {
    val state = GameState(
      whitePieces = Bitboard.empty,
      blackPieces = Bitboard.empty,
      pawns = Bitboard.empty,
      knights = Bitboard.empty,
      bishops = Bitboard.empty,
      rooks = Bitboard.empty,
      queens = Bitboard.empty,
      kings = Bitboard.empty,
      mailbox = Mailbox.empty,
      flags = GameFlags.fromList(Color.White, 0, 0, Nil, 0),
      enPassant = Bitboard.empty.add(Square.fromNotation("e3").get).add(Square.fromNotation("h6").get),
      fullMoveNumber = 1
    )

    // White's turn starts, so White clears White's old targets (rank 3)
    val stateAfterWhiteClears = state.clearEnPassant(Color.White)
    assert(!stateAfterWhiteClears.enPassant.contains(Square.fromNotation("e3").get)) // Cleared!
    assert(stateAfterWhiteClears.enPassant.contains(Square.fromNotation("h6").get))  // Preserved!

    // Black's turn starts, so Black clears Black's old targets (rank 6)
    val stateAfterBlackClears = state.clearEnPassant(Color.Black)
    assert(stateAfterBlackClears.enPassant.contains(Square.fromNotation("e3").get))  // Preserved!
    assert(!stateAfterBlackClears.enPassant.contains(Square.fromNotation("h6").get)) // Cleared!
  }

  test("GameState.endTurn should toggle color, increment full-move for Black, and clean stale EP targets") {
    val state = GameState(
      whitePieces = Bitboard.empty,
      blackPieces = Bitboard.empty,
      pawns = Bitboard.empty,
      knights = Bitboard.empty,
      bishops = Bitboard.empty,
      rooks = Bitboard.empty,
      queens = Bitboard.empty,
      kings = Bitboard.empty,
      mailbox = Mailbox.empty,
      flags = GameFlags.fromList(Color.White, 0, 0, Nil, 0),
      enPassant = Bitboard.empty.add(Square.fromNotation("e3").get).add(Square.fromNotation("h6").get),
      fullMoveNumber = 1
    )

    // End of White's turn
    val stateAfterWhite = state.endTurn()
    assertEquals(stateAfterWhite.activeColor, Color.Black)
    assertEquals(stateAfterWhite.fullMoveNumber, 1) // Doesn't increment yet
    assert(!stateAfterWhite.enPassant.contains(Square.fromNotation("h6").get))
    assert(stateAfterWhite.enPassant.contains(Square.fromNotation("e3").get))

    // End of Black's turn
    val stateAfterBlack = stateAfterWhite.endTurn()
    assertEquals(stateAfterBlack.activeColor, Color.White)
    assertEquals(stateAfterBlack.fullMoveNumber, 2)                            // Increments after Black's turn
    assert(!stateAfterBlack.enPassant.contains(Square.fromNotation("e3").get)) // White's old EP targets are cleared!
  }

  private def emptyState(color: Color = Color.White): GameState =
    GameState(
      whitePieces = Bitboard.empty,
      blackPieces = Bitboard.empty,
      pawns = Bitboard.empty,
      knights = Bitboard.empty,
      bishops = Bitboard.empty,
      rooks = Bitboard.empty,
      queens = Bitboard.empty,
      kings = Bitboard.empty,
      mailbox = Mailbox.empty,
      flags = GameFlags.fromList(color, 0, 0, Nil, 0),
      enPassant = Bitboard.empty,
      fullMoveNumber = 1
    )

  test("GameState.equals should return true for two states with identical mailboxes") {
    val s1 = emptyState()
    val s2 = emptyState()
    assertEquals(s1, s2)
  }

  test("GameState.equals should return false when mailboxes differ") {
    val s1      = emptyState()
    val builder = Array.fill(64)(Piece.Empty)
    builder(Square('e', 4).index) = Piece(Color.White, PieceType.Pawn)
    val s2 = s1.copy(mailbox = Mailbox.fromBuilder(builder))
    assertNotEquals(s1, s2)
  }

  test("GameState.equals should return false for non-GameState object") {
    val s: Any = emptyState()
    assert(!s.equals("not a game state"))
  }

  test("GameState.hashCode should be equal for two structurally identical states") {
    val s1 = emptyState()
    val s2 = emptyState()
    assertEquals(s1.hashCode(), s2.hashCode())
  }

  test("GameState.hashCode should differ for states with different mailboxes") {
    val s1      = emptyState()
    val builder = Array.fill(64)(Piece.Empty)
    builder(Square('a', 1).index) = Piece(Color.Black, PieceType.Rook)
    val s2 = s1.copy(mailbox = Mailbox.fromBuilder(builder))
    assertNotEquals(s1.hashCode(), s2.hashCode())
  }

  test("GameState parsed from FEN should satisfy equals and hashCode contract") {
    val fen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    val state = FenParser.parse(fen).toOption.get
    val copy  = FenParser.parse(fen).toOption.get
    assertEquals(state, copy)
    assertEquals(state.hashCode(), copy.hashCode())
  }

  // The claim `ExpectimaxSearch.LeafKey` once denied (#514): a position reached by two different
  // move orders compares equal, so GameState deduplicates transpositions as a hash key. Two quiet
  // single-step pawn pushes commute — neither sets an en-passant square, and both reset the same
  // half-move clock — so the two orders must land on an identical state.
  private def transposed(first: Move, second: Move): GameState =
    FenParser.parse(FenParser.InitialPosition).toOption.get.makeMove(first).makeMove(second)

  test("GameState reached by transposed move orders compares equal and hashes alike") {
    val a3 = Move(Square('a', 2), Square('a', 3), Move.QuietMove)
    val h3 = Move(Square('h', 2), Square('h', 3), Move.QuietMove)

    val viaA = transposed(a3, h3)
    val viaH = transposed(h3, a3)

    assertEquals(viaA, viaH, "the same position built by different move orders must compare equal")
    assertEquals(viaA.hashCode(), viaH.hashCode(), "equal states must hash alike")
  }

  test("GameState deduplicates transpositions when used directly as a hash key") {
    // The concrete consequence: no separate key type is needed for correctness. LeafKey exists to
    // avoid hashing the 64-entry mailbox, which is a cost argument, not this one.
    val a3 = Move(Square('a', 2), Square('a', 3), Move.QuietMove)
    val h3 = Move(Square('h', 2), Square('h', 3), Move.QuietMove)

    val seen = scala.collection.mutable.HashSet(transposed(a3, h3))
    assert(!seen.add(transposed(h3, a3)), "the transposed twin must be recognised as already seen")
    assertEquals(seen.size, 1)
  }
