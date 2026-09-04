package dicechess.engine.search

import dicechess.engine.domain.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class EvaluatorSymmetrySpec extends ScalaCheckSuite:

  // --- Helpers & generators ---

  /** Builds an internally consistent [[GameState]] (bitboards derived from the placement) with an empty en-passant set.
    */
  private def buildState(
      placement: List[(Square, Piece)],
      activeColor: Color,
      castlingRights: Int,
      halfMoveClock: Int,
      fullMoveNumber: Int
  ): GameState =
    val mb      = Array.fill[Piece](64)(Piece.Empty)
    var white   = Bitboard.empty
    var black   = Bitboard.empty
    var pawns   = Bitboard.empty
    var knights = Bitboard.empty
    var bishops = Bitboard.empty
    var rooks   = Bitboard.empty
    var queens  = Bitboard.empty
    var kings   = Bitboard.empty
    placement.foreach { (sq, p) =>
      mb(sq.index) = p
      val bb = Bitboard.fromSquare(sq)
      if p.color.isWhite then white = white | bb else black = black | bb
      p.pieceType match
        case PieceType.Pawn   => pawns = pawns | bb
        case PieceType.Knight => knights = knights | bb
        case PieceType.Bishop => bishops = bishops | bb
        case PieceType.Rook   => rooks = rooks | bb
        case PieceType.Queen  => queens = queens | bb
        case PieceType.King   => kings = kings | bb
        case _                => ()
    }
    GameState(
      white,
      black,
      pawns,
      knights,
      bishops,
      rooks,
      queens,
      kings,
      mailbox = Mailbox.fromBuilder(mb),
      flags = GameFlags.fromList(activeColor, castlingRights, 0, Nil, halfMoveClock),
      enPassant = Bitboard.empty,
      fullMoveNumber = fullMoveNumber
    )

  private def parseFen(fen: String): GameState =
    FenParser.parse(fen).fold(e => fail(s"bad FEN '$fen': $e"), identity)

  private val colorGen: Gen[Color]                = Gen.oneOf(Color.White, Color.Black)
  private val nonKingPieceTypeGen: Gen[PieceType] = Gen.oneOf(
    PieceType.Pawn,
    PieceType.Knight,
    PieceType.Bishop,
    PieceType.Rook,
    PieceType.Queen
  )

  private val pieceGen: Gen[Piece] = for
    c  <- colorGen
    pt <- nonKingPieceTypeGen
  yield Piece(c, pt)

  private val stateGen: Gen[GameState] = for
    n    <- Gen.choose(0, 22)
    idxs <- Gen.pick(n + 2, 0 to 63).map(_.toList)
    wKingIdx  = idxs.head
    bKingIdx  = idxs.tail.head
    otherIdxs = idxs.drop(2)
    pieces   <- Gen.listOfN(n, pieceGen)
    color    <- colorGen
    castling <- Gen.choose(0, 15)
    half     <- Gen.choose(0, 100)
    full     <- Gen.choose(1, 200)
    placement = (Square.fromIndex(wKingIdx), Piece(Color.White, PieceType.King)) ::
      (Square.fromIndex(bKingIdx), Piece(Color.Black, PieceType.King)) ::
      otherIdxs.map(Square.fromIndex).zip(pieces)
  yield buildState(placement, color, castling, half, full)

  private val noCastlingStateGen: Gen[GameState] = for
    n    <- Gen.choose(0, 22)
    idxs <- Gen.pick(n + 2, 0 to 63).map(_.toList)
    wKingIdx  = idxs.head
    bKingIdx  = idxs.tail.head
    otherIdxs = idxs.drop(2)
    pieces <- Gen.listOfN(n, pieceGen)
    color  <- colorGen
    half   <- Gen.choose(0, 100)
    full   <- Gen.choose(1, 200)
    placement = (Square.fromIndex(wKingIdx), Piece(Color.White, PieceType.King)) ::
      (Square.fromIndex(bKingIdx), Piece(Color.Black, PieceType.King)) ::
      otherIdxs.map(Square.fromIndex).zip(pieces)
  yield buildState(placement, color, 0, half, full)

  // --- Properties ---

  property("Evaluator.evaluate symmetry under colorFlip") {
    forAll(stateGen) { state =>
      assertEquals(
        Evaluator.evaluate(state, Color.White),
        Evaluator.evaluate(Symmetry.colorFlip(state), Color.Black)
      )
    }
  }

  property("Evaluator.evaluateAggressive symmetry under colorFlip") {
    forAll(stateGen) { state =>
      assertEquals(
        Evaluator.evaluateAggressive(state, Color.White),
        Evaluator.evaluateAggressive(Symmetry.colorFlip(state), Color.Black)
      )
    }
  }

  property("Evaluator.evaluate symmetry under horizontalMirror for positions with empty castling rights") {
    forAll(noCastlingStateGen, colorGen) { (state, color) =>
      assertEquals(
        Evaluator.evaluate(state, color),
        Evaluator.evaluate(Symmetry.horizontalMirror(state), color)
      )
    }
  }

  property("Evaluator.evaluateAggressive symmetry under horizontalMirror for positions with empty castling rights") {
    forAll(noCastlingStateGen, colorGen) { (state, color) =>
      assertEquals(
        Evaluator.evaluateAggressive(state, color),
        Evaluator.evaluateAggressive(Symmetry.horizontalMirror(state), color)
      )
    }
  }

  // --- Explicit Edge-Case Fixtures: Empty Board, Full Board, Pinned Pieces ---

  test("Evaluator symmetry on empty board") {
    val emptyState = buildState(Nil, Color.White, 0, 0, 1)
    assertEquals(
      Evaluator.evaluate(emptyState, Color.White),
      Evaluator.evaluate(Symmetry.colorFlip(emptyState), Color.Black)
    )
    assertEquals(
      Evaluator.evaluateAggressive(emptyState, Color.White),
      Evaluator.evaluateAggressive(Symmetry.colorFlip(emptyState), Color.Black)
    )
    assertEquals(
      Evaluator.evaluate(emptyState, Color.White),
      Evaluator.evaluate(Symmetry.horizontalMirror(emptyState), Color.White)
    )
    assertEquals(
      Evaluator.evaluateAggressive(emptyState, Color.White),
      Evaluator.evaluateAggressive(Symmetry.horizontalMirror(emptyState), Color.White)
    )
  }

  test("Evaluator symmetry on full board (initial position)") {
    val fullState           = parseFen(FenParser.InitialPosition)
    val fullStateNoCastling = fullState.copy(flags = fullState.flags.withCastlingRights(0))

    assertEquals(
      Evaluator.evaluate(fullState, Color.White),
      Evaluator.evaluate(Symmetry.colorFlip(fullState), Color.Black)
    )
    assertEquals(
      Evaluator.evaluateAggressive(fullState, Color.White),
      Evaluator.evaluateAggressive(Symmetry.colorFlip(fullState), Color.Black)
    )
    assertEquals(
      Evaluator.evaluate(fullStateNoCastling, Color.White),
      Evaluator.evaluate(Symmetry.horizontalMirror(fullStateNoCastling), Color.White)
    )
    assertEquals(
      Evaluator.evaluateAggressive(fullStateNoCastling, Color.White),
      Evaluator.evaluateAggressive(Symmetry.horizontalMirror(fullStateNoCastling), Color.White)
    )
  }

  test("Evaluator symmetry on pinned piece positions") {
    // Position with a absolute pin along vertical e-file (White Rook on e2 pinned by Black Rook on e8)
    val pinnedVertical = parseFen("4r3/8/8/8/8/8/4R3/4K3 w - - 0 1")
    assertEquals(
      Evaluator.evaluate(pinnedVertical, Color.White),
      Evaluator.evaluate(Symmetry.colorFlip(pinnedVertical), Color.Black)
    )
    assertEquals(
      Evaluator.evaluateAggressive(pinnedVertical, Color.White),
      Evaluator.evaluateAggressive(Symmetry.colorFlip(pinnedVertical), Color.Black)
    )
    assertEquals(
      Evaluator.evaluate(pinnedVertical, Color.White),
      Evaluator.evaluate(Symmetry.horizontalMirror(pinnedVertical), Color.White)
    )
    assertEquals(
      Evaluator.evaluateAggressive(pinnedVertical, Color.White),
      Evaluator.evaluateAggressive(Symmetry.horizontalMirror(pinnedVertical), Color.White)
    )

    // Position with a diagonal pin (White Pawn d2 pinned to White King e1 by Black Bishop c3)
    val pinnedDiagonal = parseFen("8/8/8/8/8/2b5/3P4/4K3 w - - 0 1")
    assertEquals(
      Evaluator.evaluate(pinnedDiagonal, Color.White),
      Evaluator.evaluate(Symmetry.colorFlip(pinnedDiagonal), Color.Black)
    )
    assertEquals(
      Evaluator.evaluateAggressive(pinnedDiagonal, Color.White),
      Evaluator.evaluateAggressive(Symmetry.colorFlip(pinnedDiagonal), Color.Black)
    )
    assertEquals(
      Evaluator.evaluate(pinnedDiagonal, Color.White),
      Evaluator.evaluate(Symmetry.horizontalMirror(pinnedDiagonal), Color.White)
    )
    assertEquals(
      Evaluator.evaluateAggressive(pinnedDiagonal, Color.White),
      Evaluator.evaluateAggressive(Symmetry.horizontalMirror(pinnedDiagonal), Color.White)
    )
  }
