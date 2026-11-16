// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

import dicechess.engine.movegen.MoveGeneratorPuritySpec
import dicechess.engine.search.TurnGeneratorPropertySpec
import java.lang.reflect.Method
import munit.FunSuite

class TestStateNormalizationContractSuite extends FunSuite:

  private case class Normalizer(label: String, receiver: AnyRef, method: Method)
  private case class Snapshot(
      serialized: String,
      mailbox: Vector[Piece],
      flags: GameFlags,
      fullMoveNumber: Int,
      zobristKey: Long
  ) derives CanEqual
  private case class HomePiece(label: String, square: Square, piece: Piece, rightsMask: Int)
  private case class Replacement(label: String, piece: Piece)

  private val CachedKey    = 42L
  private val MetadataDice = List(6, 2, 4)

  private lazy val normalizers = List(
    findNormalizer("GameStateInvariantsSpec", new GameStateInvariantsSpec),
    findNormalizer("MoveGeneratorPuritySpec", new MoveGeneratorPuritySpec),
    findNormalizer("TurnGeneratorPropertySpec", new TurnGeneratorPropertySpec)
  )

  private val homePieces = List(
    HomePiece("White king e1", Square('e', 1), Piece(Color.White, PieceType.King), 3),
    HomePiece("Black king e8", Square('e', 8), Piece(Color.Black, PieceType.King), 12),
    HomePiece("White rook h1", Square('h', 1), Piece(Color.White, PieceType.Rook), 1),
    HomePiece("White rook a1", Square('a', 1), Piece(Color.White, PieceType.Rook), 2),
    HomePiece("Black rook h8", Square('h', 8), Piece(Color.Black, PieceType.Rook), 4),
    HomePiece("Black rook a8", Square('a', 8), Piece(Color.Black, PieceType.Rook), 8)
  )

  private def findNormalizer(label: String, receiver: AnyRef): Normalizer =
    val declaredMethods = Option(receiver.getClass.getDeclaredMethods).getOrElse(fail(s"Missing methods for $label"))
    val methods         = declaredMethods.filter { method =>
      Option(method.getName).exists(_.endsWith("makeConsistentState")) && method.getParameterCount == 1
    }
    assertEquals(methods.length, 1, s"$label should declare one makeConsistentState(GameState)")
    val method = methods.head
    method.setAccessible(true)
    Normalizer(label, receiver, method)

  private def parseState(fen: String): GameState =
    FenParser.parse(fen).getOrElse(sys.error(s"Failed to parse test FEN: $fen"))

  private def withCache(state: GameState): GameState = state.copy(zobristKey = CachedKey)

  private def withMetadata(state: GameState): GameState =
    withCache(state.withDicePool(MetadataDice))

  private def invoke(normalizer: Normalizer, state: GameState): GameState =
    Option(normalizer.method.invoke(normalizer.receiver, state))
      .getOrElse(fail(s"${normalizer.label} normalizer returned null"))
      .asInstanceOf[GameState]

  private def snapshot(state: GameState): Snapshot =
    Snapshot(
      FenParser.serialize(state),
      state.mailbox.toArray.toVector,
      state.flags,
      state.fullMoveNumber,
      state.zobristKey
    )

  private def assertContract(
      normalizer: Normalizer,
      input: GameState,
      expected: GameState,
      context: String = ""
  ): GameState =
    val inputBefore = snapshot(input)
    val once        = invoke(normalizer, input)
    assertEquals(snapshot(input), inputBefore, s"${normalizer.label} mutated its input: $context")
    assertEquals(once, expected, s"${normalizer.label} result differs from the explicit expectation: $context")
    assertEquals(once.zobristKey, expected.zobristKey, s"${normalizer.label} changed the cached Zobrist key: $context")
    assertEquals(
      once.zobristKey,
      input.zobristKey,
      s"${normalizer.label} did not preserve the supplied cache: $context"
    )

    val onceBefore = snapshot(once)
    val twice      = invoke(normalizer, once)
    assertEquals(snapshot(once), onceBefore, s"${normalizer.label} mutated the normalized input: $context")
    assertEquals(twice, once, s"${normalizer.label} is not idempotent: $context")
    assertEquals(twice.zobristKey, once.zobristKey, s"${normalizer.label} changed the cache on a second pass: $context")
    once

  private def targetMask(squares: List[Square]): Bitboard =
    squares.foldLeft(Bitboard.empty)((mask, square) => mask.add(square))

  private def withTargets(state: GameState, targets: Bitboard, epFiles: Int): GameState =
    withCache(state.copy(enPassant = targets, flags = state.flags.withEnPassantFiles(epFiles)))

  private def pieceMatches(piece: Piece, pieceType: PieceType): Boolean =
    !piece.isEmpty && piece.pieceType == pieceType

  private def colorMatches(piece: Piece, color: Color): Boolean =
    !piece.isEmpty && piece.color == color

  private def updateMask(mask: Bitboard, square: Square, hadPiece: Boolean, hasPiece: Boolean): Bitboard =
    val cleared = if hadPiece then mask.remove(square) else mask
    if hasPiece then cleared.add(square) else cleared

  private def replacePiece(state: GameState, square: Square, replacement: Piece): GameState =
    val previous = state.mailbox(square)
    val mailbox  = state.mailbox.toArray
    mailbox(square.index) = replacement
    state.copy(
      whitePieces = updateMask(
        state.whitePieces,
        square,
        colorMatches(previous, Color.White),
        colorMatches(replacement, Color.White)
      ),
      blackPieces = updateMask(
        state.blackPieces,
        square,
        colorMatches(previous, Color.Black),
        colorMatches(replacement, Color.Black)
      ),
      pawns = updateMask(
        state.pawns,
        square,
        pieceMatches(previous, PieceType.Pawn),
        pieceMatches(replacement, PieceType.Pawn)
      ),
      knights = updateMask(
        state.knights,
        square,
        pieceMatches(previous, PieceType.Knight),
        pieceMatches(replacement, PieceType.Knight)
      ),
      bishops = updateMask(
        state.bishops,
        square,
        pieceMatches(previous, PieceType.Bishop),
        pieceMatches(replacement, PieceType.Bishop)
      ),
      rooks = updateMask(
        state.rooks,
        square,
        pieceMatches(previous, PieceType.Rook),
        pieceMatches(replacement, PieceType.Rook)
      ),
      queens = updateMask(
        state.queens,
        square,
        pieceMatches(previous, PieceType.Queen),
        pieceMatches(replacement, PieceType.Queen)
      ),
      kings = updateMask(
        state.kings,
        square,
        pieceMatches(previous, PieceType.King),
        pieceMatches(replacement, PieceType.King)
      ),
      mailbox = Mailbox.fromBuilder(mailbox)
    )

  private def invalidReplacements(home: HomePiece): List[Replacement] =
    val wrongColor = if home.piece.color.isWhite then Color.Black else Color.White
    val wrongType  = PieceType.Bishop
    List(
      Replacement("removed", Piece.Empty),
      Replacement("wrong-color", Piece(wrongColor, home.piece.pieceType)),
      Replacement("wrong-type", Piece(home.piece.color, wrongType))
    )

  private val castleFen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 17 42"

  normalizers.foreach { normalizer =>
    test(s"${normalizer.label}: valid complete castling layout preserves metadata and dice slots") {
      val input  = withMetadata(parseState(castleFen))
      val actual = assertContract(normalizer, input, input)
      assertEquals(actual.activeColor, Color.White)
      assertEquals(actual.flags.diceSlot1, 6)
      assertEquals(actual.flags.diceSlot2, 2)
      assertEquals(actual.flags.diceSlot3, 4)
      assertEquals(actual.halfMoveClock, 17)
      assertEquals(actual.fullMoveNumber, 42)
      assertEquals(actual.zobristKey, CachedKey)
    }

    test(s"${normalizer.label}: initial position remains exactly unchanged") {
      val input = withMetadata(parseState(FenParser.InitialPosition))
      assertContract(normalizer, input, input)
    }

    test(s"${normalizer.label}: back-rank pawns become same-color bishops only") {
      val input    = withMetadata(parseState("p2k3b/8/8/3P4/4p3/8/8/P2K3B w - - 7 9"))
      val expected = withMetadata(parseState("b2k3b/8/8/3P4/4p3/8/8/B2K3B w - - 7 9"))
      val actual   = assertContract(normalizer, input, expected)
      assertEquals(actual.pawns, input.pawns.remove(Square('a', 1)).remove(Square('a', 8)))
      assertEquals(actual.bishops, input.bishops.add(Square('a', 1)).add(Square('a', 8)))
    }

    test(s"${normalizer.label}: all valid castling-right masks are preserved") {
      val base = withMetadata(parseState(castleFen))
      for rights <- 0 to 15 do
        val input = withCache(base.copy(flags = base.flags.withCastlingRights(rights)))
        assertContract(normalizer, input, input)
    }

    test(s"${normalizer.label}: invalid home king or rook clears only its dependent rights") {
      val base = withMetadata(parseState(castleFen))
      for
        home        <- homePieces
        replacement <- invalidReplacements(home)
        rights      <- 0 to 15
      do
        val withRights     = withCache(base.copy(flags = base.flags.withCastlingRights(rights)))
        val input          = replacePiece(withRights, home.square, replacement.piece)
        val expectedRights = rights & ~home.rightsMask
        val expected       = input.copy(flags = input.flags.withCastlingRights(expectedRights))
        assertContract(normalizer, input, expected, s"${home.label} ${replacement.label}, rights=$rights")
    }

    test(s"${normalizer.label}: White en-passant keeps exactly valid rank-six targets from all 64 bits") {
      val base         = withMetadata(parseState("7k/8/8/pppppppp/8/8/8/K7 w - - 17 42"))
      val input        = withTargets(base, Bitboard.full, 0)
      val validRankSix = Bitboard(0x0000ff0000000000L)
      val expected     = withTargets(input, validRankSix, 0xff)
      val actual       = assertContract(normalizer, input, expected)
      assertEquals(actual.enPassant, validRankSix)
      assertEquals(actual.flags.enPassantFiles, 0xff)
    }

    test(s"${normalizer.label}: Black en-passant keeps exactly valid rank-three targets from all 64 bits") {
      val base           = withMetadata(parseState("k7/8/8/8/PPPPPPPP/8/8/7K b - - 23 78"))
      val input          = withTargets(base, Bitboard.full, 0)
      val validRankThree = Bitboard(0x0000000000ff0000L)
      val expected       = withTargets(input, validRankThree, 0xff)
      val actual         = assertContract(normalizer, input, expected)
      assertEquals(actual.enPassant, validRankThree)
      assertEquals(actual.flags.enPassantFiles, 0xff)
    }

    test(s"${normalizer.label}: White en-passant drops occupied, friendly, non-pawn, and empty-victim targets") {
      val base    = withMetadata(parseState("7k/8/4P3/3p1Pn1/8/8/8/K7 w - - 13 24"))
      val targets = targetMask(List(Square('d', 6), Square('e', 6), Square('f', 6), Square('g', 6), Square('h', 6)))
      val input   = withTargets(base, targets, 0xff)
      val onlyValidTarget = targetMask(List(Square('d', 6)))
      val expected        = withTargets(input, onlyValidTarget, 1 << 3)
      val actual          = assertContract(normalizer, input, expected)
      assertEquals(actual.enPassant, onlyValidTarget)
      assertEquals(actual.flags.enPassantFiles, 1 << 3)
    }

    test(s"${normalizer.label}: Black en-passant drops occupied, friendly, non-pawn, and empty-victim targets") {
      val base    = withMetadata(parseState("k7/8/8/8/3P1pN1/4p3/8/7K b - - 13 24"))
      val targets = targetMask(List(Square('d', 3), Square('e', 3), Square('f', 3), Square('g', 3), Square('h', 3)))
      val input   = withTargets(base, targets, 0xff)
      val onlyValidTarget = targetMask(List(Square('d', 3)))
      val expected        = withTargets(input, onlyValidTarget, 1 << 3)
      val actual          = assertContract(normalizer, input, expected)
      assertEquals(actual.enPassant, onlyValidTarget)
      assertEquals(actual.flags.enPassantFiles, 1 << 3)
    }

    test(s"${normalizer.label}: empty en-passant targets clear stale file flags") {
      val white = withTargets(withMetadata(parseState(castleFen)), Bitboard.empty, 0xff)
      val black = withTargets(
        withMetadata(parseState("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 17 42")),
        Bitboard.empty,
        0xff
      )
      for input <- List(white, black) do
        val expected = withTargets(input, Bitboard.empty, 0)
        val actual   = assertContract(normalizer, input, expected)
        assertEquals(actual.flags.enPassantFiles, 0)
    }
  }
