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
