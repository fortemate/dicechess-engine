// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

import java.lang.reflect.InvocationTargetException
import munit.FunSuite

private[engine] object TestOracleContractCases:
  final case class NamedState(name: String, state: GameState)

  private val emptyFen   = "8/8/8/8/8/8/8/8 w - - 17 42"
  private val initialFen = FenParser.InitialPosition
  private val castleFen  = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 17 42"

  def ordinaryValid: List[NamedState] =
    List(
      named("empty board", emptyFen),
      named("initial position", initialFen),
      named("castling layout", castleFen),
      named("white en-passant", "4k3/8/8/3pP3/8/8/8/4K3 w - d6 17 42"),
      named("black en-passant", "4k3/8/8/8/3pP3/8/8/4K3 b - e3 17 42"),
      named("promotion-ready pawn", "4k3/P7/8/8/8/8/8/4K3 w - - 17 42"),
      named("unbacked en-passant target", "4k3/8/8/8/8/8/8/4K3 w - e3 17 42")
    )

  def mailboxMismatches: List[NamedState] =
    val empty      = parsed(emptyFen)
    val e4         = Square('e', 4)
    val boardCases = List(
      ("whitePieces", (s: GameState) => s.copy(whitePieces = s.whitePieces.add(e4))),
      ("blackPieces", (s: GameState) => s.copy(blackPieces = s.blackPieces.add(e4))),
      ("pawns", (s: GameState) => s.copy(pawns = s.pawns.add(e4))),
      ("knights", (s: GameState) => s.copy(knights = s.knights.add(e4))),
      ("bishops", (s: GameState) => s.copy(bishops = s.bishops.add(e4))),
      ("rooks", (s: GameState) => s.copy(rooks = s.rooks.add(e4))),
      ("queens", (s: GameState) => s.copy(queens = s.queens.add(e4))),
      ("kings", (s: GameState) => s.copy(kings = s.kings.add(e4)))
    )
    val initial   = parsed(initialFen)
    val e2        = Square('e', 2)
    val e7        = Square('e', 7)
    val sideCases = List(
      named("white e2 missing from white side", initial.copy(whitePieces = initial.whitePieces.remove(e2))),
      named(
        "white e2 in black side",
        initial.copy(whitePieces = initial.whitePieces.remove(e2), blackPieces = initial.blackPieces.add(e2))
      ),
      named("black e7 missing from black side", initial.copy(blackPieces = initial.blackPieces.remove(e7))),
      named(
        "black e7 in white side",
        initial.copy(blackPieces = initial.blackPieces.remove(e7), whitePieces = initial.whitePieces.add(e7))
      )
    )
    val pieceCases = PieceType.all.map { pieceType =>
      val square = initial.mailbox.toArray.indices
        .map(Square.fromIndex)
        .find(sq => initial.mailbox(sq).pieceType == pieceType && initial.mailbox(sq).color == Color.White)
        .getOrElse(sys.error(s"Missing White $pieceType in initial fixture"))
      val alternate = if pieceType == PieceType.Pawn then PieceType.Knight else PieceType.Pawn
      named(
        s"${pieceType.asNotation} absent from its type board",
        changeTypeBoard(initial, square, pieceType, alternate)
      )
    }
    val additionalTypeCases = PieceType.all.filter(_ != PieceType.Pawn).map { pieceType =>
      named(s"unexpected $pieceType membership at white pawn", addTypeBoard(initial, e2, pieceType))
    } ++ List(
      named("unexpected pawn membership at white knight", addTypeBoard(initial, Square('b', 1), PieceType.Pawn))
    )
    val mailboxCases = List(
      named("mailbox pawn has wrong color", replaceMailbox(initial, e2, Piece(Color.Black, PieceType.Pawn))),
      named("mailbox pawn has wrong type", replaceMailbox(initial, e2, Piece(Color.White, PieceType.Knight)))
    )
    val overlap = named("white and black side boards overlap", initial.copy(whitePieces = initial.whitePieces.add(e7)))
    boardCases.map { case (label, change) => named(s"empty e4 appears in $label", change(empty)) } ++
      sideCases ++ pieceCases ++ additionalTypeCases ++ mailboxCases ++ List(
        named(
          "empty e4 in white and pawn boards",
          empty.copy(whitePieces = empty.whitePieces.add(e4), pawns = empty.pawns.add(e4))
        ),
        named(
          "empty e4 in black and pawn boards",
          empty.copy(blackPieces = empty.blackPieces.add(e4), pawns = empty.pawns.add(e4))
        ),
        overlap,
        named("black side overlaps white pawn", initial.copy(blackPieces = initial.blackPieces.add(e2)))
      )

  def turnOnlyViolations: List[NamedState] =
    val backRankPawns = List(
      named(
        "white pawn on a1",
        replaceMailbox(parsed("4k3/8/8/8/8/8/8/P3K3 w - - 17 42"), Square('a', 1), Piece(Color.White, PieceType.Pawn))
      ),
      named(
        "black pawn on a8",
        replaceMailbox(parsed("p3k3/8/8/8/8/8/8/4K3 w - - 17 42"), Square('a', 8), Piece(Color.Black, PieceType.Pawn))
      )
    )
    backRankPawns ++ List(1, 2, 4, 8).flatMap(castlingViolations)

  private def castlingViolations(right: Int): List[NamedState] =
    val (rightName, kingSquare, rookSquare, color) = right match
      case 1 => ("white king-side", Square('e', 1), Square('h', 1), Color.White)
      case 2 => ("white queen-side", Square('e', 1), Square('a', 1), Color.White)
      case 4 => ("black king-side", Square('e', 8), Square('h', 8), Color.Black)
      case _ => ("black queen-side", Square('e', 8), Square('a', 8), Color.Black)
    val fenRight = right match
      case 1 => "K"
      case 2 => "Q"
      case 4 => "k"
      case _ => "q"
    val base = parsed(s"r3k2r/8/8/8/8/8/8/R3K2R w $fenRight - 17 42")
    List(
      named(s"$rightName king removed", editMailbox(base, kingSquare, Piece.Empty)),
      named(s"$rightName king wrong color", editMailbox(base, kingSquare, Piece(color.opponent, PieceType.King))),
      named(s"$rightName king wrong type", editMailbox(base, kingSquare, Piece(color, PieceType.Knight))),
      named(s"$rightName rook removed", editMailbox(base, rookSquare, Piece.Empty)),
      named(s"$rightName rook wrong color", editMailbox(base, rookSquare, Piece(color.opponent, PieceType.Rook))),
      named(s"$rightName rook wrong type", editMailbox(base, rookSquare, Piece(color, PieceType.Bishop)))
    )

  private def named(name: String, fen: String): NamedState = named(name, parsed(fen))

  private def named(name: String, state: GameState): NamedState =
    val flags = GameFlags(state.activeColor, state.flags.castlingRights, state.flags.enPassantFiles, 1, 2, 0, 17)
    NamedState(name, state.copy(flags = flags, fullMoveNumber = 42, zobristKey = 42L))

  private def parsed(fen: String): GameState =
    FenParser.parse(fen).getOrElse(sys.error(s"Failed to parse contract fixture: $fen"))

  private def changeTypeBoard(state: GameState, square: Square, from: PieceType, to: PieceType): GameState =
    updateTypeBoard(updateTypeBoard(state, from, _.remove(square)), to, _.add(square))

  private def addTypeBoard(state: GameState, square: Square, pieceType: PieceType): GameState =
    updateTypeBoard(state, pieceType, _.add(square))

  private def updateTypeBoard(state: GameState, pieceType: PieceType, change: Bitboard => Bitboard): GameState =
    pieceType match
      case PieceType.Pawn   => state.copy(pawns = change(state.pawns))
      case PieceType.Knight => state.copy(knights = change(state.knights))
      case PieceType.Bishop => state.copy(bishops = change(state.bishops))
      case PieceType.Rook   => state.copy(rooks = change(state.rooks))
      case PieceType.Queen  => state.copy(queens = change(state.queens))
      case PieceType.King   => state.copy(kings = change(state.kings))

  private def replaceMailbox(state: GameState, square: Square, piece: Piece): GameState =
    val mailbox = state.mailbox.toArray
    mailbox(square.index) = piece
    state.copy(mailbox = Mailbox.fromBuilder(mailbox))

  private def editMailbox(state: GameState, square: Square, piece: Piece): GameState =
    val replaced = replaceMailbox(state, square, piece)
    parsed(FenParser.serialize(replaced))

class TestOracleContractSuite extends FunSuite:
  private val mailboxValidator = validator(new GameStateInvariantsSpec, "assertMailboxBitboardCorrespondence")
  private val turnValidator    = validator(new dicechess.engine.search.TurnGeneratorPropertySpec, "assertConsistent")

  test("mailbox correspondence accepts ordinary valid fixtures") {
    TestOracleContractCases.ordinaryValid.foreach(fixture => assertPreserved(fixture, mailboxValidator))
  }

  test("turn consistency accepts ordinary valid fixtures") {
    TestOracleContractCases.ordinaryValid.foreach(fixture => assertPreserved(fixture, turnValidator))
  }

  test("mailbox correspondence rejects mailbox and bitboard mismatches") {
    TestOracleContractCases.mailboxMismatches.foreach(fixture => assertRejected(fixture, mailboxValidator))
  }

  test("turn consistency rejects mailbox and bitboard mismatches") {
    TestOracleContractCases.mailboxMismatches.foreach(fixture => assertRejected(fixture, turnValidator))
  }

  test("mailbox correspondence accepts turn-only violations") {
    TestOracleContractCases.turnOnlyViolations.foreach(fixture => assertPreserved(fixture, mailboxValidator))
  }

  test("turn consistency rejects turn-only violations") {
    TestOracleContractCases.turnOnlyViolations.foreach(fixture => assertRejected(fixture, turnValidator))
  }

  test("raw path oracle rejects a move whose mailbox type cannot consume its die") {
    val base =
      FenParser.parse("7k/8/8/8/8/2N5/8/6K1 w - - 0 1").getOrElse(fail("Invalid fixture")).withDicePool(List(2))
    val mailbox = base.mailbox.toArray
    mailbox(Square('c', 3).index) = Piece(Color.White, PieceType.Rook)
    val fixture = TestOracleContractCases.NamedState(
      "knight bitboard with rook mailbox and only a knight die",
      base.copy(mailbox = Mailbox.fromBuilder(mailbox))
    )
    assertRejected(fixture, validator(new dicechess.engine.search.TurnGeneratorPropertySpec, "generateAllPathsOld"))
  }

  test("raw path oracle continues after castling with a remaining rook die") {
    val state = FenParser
      .parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
      .getOrElse(fail("Invalid fixture"))
      .withDicePool(List(6, 4, 4))
    val instance = new dicechess.engine.search.TurnGeneratorPropertySpec
    val method   = oracleMethod(instance, "generateAllPathsOld", 1)
    val castle   = Move(Square('e', 1), Square('g', 1), Move.KingCastle)
    val targets  = List(
      Square('a', 1),
      Square('b', 1),
      Square('c', 1),
      Square('d', 1),
      Square('e', 1),
      Square('f', 2),
      Square('f', 3),
      Square('f', 4),
      Square('f', 5),
      Square('f', 6),
      Square('f', 7),
      Square('f', 8)
    )
    val expected = targets.map(target => List(castle.toInt, Move(Square('f', 1), target, Move.QuietMove).toInt))
    assertPreserved(
      TestOracleContractCases.NamedState("castle then rook continuation", state),
      current =>
        val paths       = method.invoke(instance, current).asInstanceOf[List[List[Move]]]
        val castlePaths = paths.filter(_.headOption.exists(_.isCastling)).map(_.map(_.toInt))
        assertEquals(castlePaths, expected)
    )
  }

  test("reference king-capture continuation preserves its castling dice guards") {
    val base = FenParser
      .parse("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
      .getOrElse(fail("Invalid fixture"))
      .withDicePool(List(6, 4))
    val move = dicechess.engine.movegen.MoveGenerator
      .generateMoves(base)
      .find(_.isCastling)
      .getOrElse(fail("Missing castle move"))
    val instance = new dicechess.engine.movegen.MutableLegalMovesFilterSpec
    val method   = oracleMethod(instance, "hasKingCaptureContinuationRef", 2)
    for dice <- List(Nil, List(6), List(4), List(6, 4), List(4, 6), List(6, 4, 2)) do
      val fixture = TestOracleContractCases.NamedState(s"castling dice $dice", base.withDicePool(dice))
      assertPreserved(
        fixture,
        state =>
          val outcome =
            method.invoke(instance, state, Int.box(move.toInt)).asInstanceOf[java.lang.Boolean].booleanValue()
          assertEquals(outcome, false)
      )
  }

  test("reference king-capture continuation rejects an empty move origin") {
    val castle = FenParser
      .parse("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
      .getOrElse(fail("Invalid fixture"))
      .withDicePool(List(6, 4))
    val move = dicechess.engine.movegen.MoveGenerator
      .generateMoves(castle)
      .find(_.isCastling)
      .getOrElse(fail("Missing castle move"))
    val empty = FenParser
      .parse("8/8/8/8/8/8/8/8 w - - 0 1")
      .getOrElse(fail("Invalid fixture"))
      .withDicePool(List(6, 4))
    val instance = new dicechess.engine.movegen.MutableLegalMovesFilterSpec
    val method   = oracleMethod(instance, "hasKingCaptureContinuationRef", 2)
    assertPreserved(
      TestOracleContractCases.NamedState("empty castle origin", empty),
      state =>
        val invocation = intercept[InvocationTargetException](method.invoke(instance, state, Int.box(move.toInt)))
        assert(Option(invocation.getCause).exists(_.isInstanceOf[IllegalArgumentException]))
    )
  }

  private case class Snapshot(state: GameState, mailbox: List[Piece], key: Long)

  private def snapshot(state: GameState): Snapshot =
    val mailbox = state.mailbox.toArray
    Snapshot(state.copy(mailbox = Mailbox.fromBuilder(mailbox.clone())), mailbox.toList, state.zobristKey)

  private def assertPreserved(fixture: TestOracleContractCases.NamedState, check: GameState => Unit): Unit =
    val before = snapshot(fixture.state)
    check(fixture.state)
    assertEquals(fixture.state, before.state, fixture.name)
    assertEquals(fixture.state.mailbox.toArray.toList, before.mailbox, fixture.name)
    assertEquals(fixture.state.zobristKey, before.key, fixture.name)

  private def assertRejected(fixture: TestOracleContractCases.NamedState, check: GameState => Unit): Unit =
    val before = snapshot(fixture.state)
    val thrown = intercept[InvocationTargetException](check(fixture.state))
    assert(
      Option(thrown.getCause).exists { cause =>
        cause.isInstanceOf[munit.FailException] || cause.isInstanceOf[munit.ComparisonFailException]
      },
      fixture.name
    )
    assertEquals(fixture.state, before.state, fixture.name)
    assertEquals(fixture.state.mailbox.toArray.toList, before.mailbox, fixture.name)
    assertEquals(fixture.state.zobristKey, before.key, fixture.name)

  private def validator(instance: AnyRef, suffix: String): GameState => Unit =
    val method = oracleMethod(instance, suffix, 1)
    state =>
      val _ = method.invoke(instance, state)

  private def oracleMethod(instance: AnyRef, suffix: String, arity: Int): java.lang.reflect.Method =
    val declaredMethods = Option(instance.getClass.getDeclaredMethods).getOrElse(fail("Missing declared methods"))
    val candidates      =
      declaredMethods.filter(m => Option(m.getName).contains(suffix) && m.getParameterCount == arity)
    assertEquals(candidates.length, 1, s"Expected one declared $suffix method")
    val method = candidates.head
    method.setAccessible(true)
    method
