// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** Independent square-scan oracle and consumer rank-bonus parity for the existing PassedPawns contract (#396). */
class PassedPawnsContractSpec extends ScalaCheckSuite:

  private val colors      = List(Color.White, Color.Black)
  private val pawnSquares = (8 until 56).toList
  private val empty       = FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get

  private def position(pieces: List[(Int, Piece)]): GameState =
    val mailbox = Array.fill(64)(Piece.Empty)
    pieces.foreach { case (index, piece) => mailbox(index) = piece }
    def board(select: Piece => Boolean): Bitboard =
      Bitboard(pieces.foldLeft(0L) { case (bits, (index, piece)) =>
        if select(piece) then bits | (1L << index) else bits
      })
    empty.copy(
      whitePieces = board(_.color.isWhite),
      blackPieces = board(_.color.isBlack),
      pawns = board(_.pieceType == PieceType.Pawn),
      knights = board(_.pieceType == PieceType.Knight),
      bishops = board(_.pieceType == PieceType.Bishop),
      rooks = board(_.pieceType == PieceType.Rook),
      queens = board(_.pieceType == PieceType.Queen),
      kings = board(_.pieceType == PieceType.King),
      mailbox = Mailbox.fromBuilder(mailbox),
      zobristKey = 0L
    )

  private val stateGen: Gen[GameState] = for
    size    <- Gen.choose(0, 32)
    squares <- Gen.pick(size, pawnSquares).map(_.toList)
    pieces  <- Gen.listOfN(
      size,
      for
        color <- Gen.oneOf(colors)
        kind  <- Gen.frequency(4 -> Gen.const(PieceType.Pawn), 1 -> Gen.oneOf(PieceType.all))
      yield Piece(color, kind)
    )
  yield position(squares.zip(pieces))

  // No production pawn bitboards, front-span masks, or bit iteration: inspect every mailbox square.
  private def referenceSquares(state: GameState, color: Color): List[Int] =
    (0 until 64).filter { index =>
      val piece = state.mailbox(Square.fromIndex(index))
      !piece.isEmpty && piece.color == color && piece.pieceType == PieceType.Pawn &&
      !(0 until 64).exists { other =>
        val blocker = state.mailbox(Square.fromIndex(other))
        val ahead   = if color.isWhite then other / 8 > index / 8 else other / 8 < index / 8
        !blocker.isEmpty && blocker.color == color.opponent && blocker.pieceType == PieceType.Pawn &&
        math.abs(other % 8 - index % 8) <= 1 && ahead
      }
    }.toList

  private def referenceBoard(squares: List[Int]): Bitboard =
    Bitboard(squares.foldLeft(0L)((bits, index) => bits | (1L << index)))

  private def advancement(index: Int, color: Color): Int =
    if color.isWhite then index / 8 else 7 - index / 8

  private def checkContract(state: GameState, color: Color): Unit =
    val expected = referenceSquares(state, color)
    val max      = expected.map(advancement(_, color)).maxOption.getOrElse(0)
    assertEquals(PassedPawns.bitboard(state, color), referenceBoard(expected))
    assertEquals(PassedPawns.count(state, color), expected.size)
    assertEquals(PassedPawns.maxRank(state, color), max)
    assertEquals(PassedPawns.countAndMaxRank(state, color), (expected.size, max))

  // Same consumer-owned adapter as the compiled API example and the migration guide.
  private def passedPawnBonus(state: GameState, color: Color, bonusByAdvancement: IArray[Int]): Int =
    var p     = PassedPawns.bitboard(state, color).value
    var score = 0
    while p != 0L do
      val square = java.lang.Long.numberOfTrailingZeros(p)
      val rank   = square / 8 + 1
      val adv    = if color.isWhite then rank - 1 else 8 - rank
      score += bonusByAdvancement(adv)
      p &= p - 1L
    score

  test("all files and pawn ranks agree with the reference for every single opposing pawn square"):
    for
      color <- colors
      own   <- pawnSquares
      other <- pawnSquares if other != own
    do
      val state = position(List(own -> Piece(color, PieceType.Pawn), other -> Piece(color.opponent, PieceType.Pawn)))
      checkContract(state, color)

  test("friendly pawns and non-pawn pieces ahead do not disqualify a passed pawn"):
    for
      color        <- colors
      file         <- 0 until 8
      kind         <- PieceType.all
      blockerColor <- colors if blockerColor == color || kind != PieceType.Pawn
    do
      val own   = 3 * 8 + file
      val ahead = (if color.isWhite then 4 else 2) * 8 + file
      val state = position(List(own -> Piece(color, PieceType.Pawn), ahead -> Piece(blockerColor, kind)))
      checkContract(state, color)
      assert(PassedPawns.bitboard(state, color).contains(Square.fromIndex(own)))

  test("a forward opposing pawn can disqualify only the rear member of doubled pawns"):
    val state = position(
      List(
        19 -> Piece(Color.White, PieceType.Pawn), // d3
        43 -> Piece(Color.White, PieceType.Pawn), // d6
        34 -> Piece(Color.Black, PieceType.Pawn)  // c5
      )
    )
    for (s, color) <- List(state -> Color.White, Symmetry.colorFlip(state) -> Color.Black) do
      checkContract(s, color)
      assertEquals(PassedPawns.countAndMaxRank(s, color), (1, 5))

  property("all four APIs agree with the independent scan on mixed multi-pawn boards"):
    forAll(stateGen) { (state: GameState) =>
      colors.foreach(checkContract(state, _))
      true
    }

  property("explicit perspective ignores active color and dice, and every API leaves the input intact"):
    forAll(stateGen) { (state: GameState) =>
      val serialized = FenParser.serialize(state)
      val hash       = state.zobristHash
      for
        perspective <- colors
        active      <- colors
        dice        <- List(Nil, List(1), List(6, 6), List(1, 2, 6))
      do
        val changed     = state.withActiveColor(active).withDicePool(dice)
        val changedDfen = FenParser.serialize(changed)
        val changedHash = changed.zobristHash
        checkContract(changed, perspective)
        assertEquals(PassedPawns.bitboard(changed, perspective), PassedPawns.bitboard(state, perspective))
        assertEquals(FenParser.serialize(changed), changedDfen)
        assertEquals(changed.zobristHash, changedHash)
      assertEquals(FenParser.serialize(state), serialized)
      assertEquals(state.zobristHash, hash)
      true
    }

  property("color flip mirrors the exact set and preserves advancement, while file mirror preserves ranks"):
    forAll(stateGen) { (state: GameState) =>
      for color <- colors do
        val expected = referenceSquares(state, color)
        val flipped  = Symmetry.colorFlip(state)
        val mirrored = Symmetry.horizontalMirror(state)
        assertEquals(PassedPawns.bitboard(flipped, color.opponent), referenceBoard(expected.map(_ ^ 56)))
        assertEquals(PassedPawns.bitboard(mirrored, color), referenceBoard(expected.map(_ ^ 7)))
        assertEquals(PassedPawns.countAndMaxRank(flipped, color.opponent), PassedPawns.countAndMaxRank(state, color))
        assertEquals(PassedPawns.countAndMaxRank(mirrored, color), PassedPawns.countAndMaxRank(state, color))
      true
    }

  property("the adapter preserves the reference score for arbitrary signed, non-monotone caller tables and weights"):
    val tableGen = Gen.listOfN(7, Gen.choose(-1000, 1000)).map(values => IArray.from(values))
    forAll(stateGen, tableGen, Gen.choose(-10, 10)) { (state: GameState, table: IArray[Int], weight: Int) =>
      for color <- colors do
        val expected = referenceSquares(state, color).map(index => table(advancement(index, color))).sum
        assertEquals(passedPawnBonus(state, color, table) * weight, expected * weight)
        assertEquals(passedPawnBonus(Symmetry.colorFlip(state), color.opponent, table), expected)
      true
    }

  test("the adapter indexes every advancement exactly once, including both endpoints and an empty set"):
    val table = IArray(999, -3, 11, 0, 7, -5, 23)
    for color <- colors do
      for rank <- 2 to 7 do
        val state = position(List(((rank - 1) * 8) -> Piece(color, PieceType.Pawn)))
        val adv   = if color.isWhite then rank - 1 else 8 - rank
        assertEquals(passedPawnBonus(state, color, table), table(adv))
      val doubled = position(List(16 -> Piece(color, PieceType.Pawn), 24 -> Piece(color, PieceType.Pawn)))
      assertEquals(
        passedPawnBonus(doubled, color, table),
        table(advancement(16, color)) + table(advancement(24, color))
      )
      assertEquals(passedPawnBonus(empty, color, table), 0)
