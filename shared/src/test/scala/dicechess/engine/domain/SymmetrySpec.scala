package dicechess.engine.domain

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class SymmetrySpec extends ScalaCheckSuite:

  // --- Helpers & generators ---

  /** Builds an internally consistent [[GameState]] (bitboards derived from the placement) with an empty en-passant set;
    * en-passant interactions are exercised separately through curated FENs.
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

  private val colorGen: Gen[Color] = Gen.oneOf(Color.White, Color.Black)
  private val pieceGen: Gen[Piece] = for
    c  <- colorGen
    pt <- Gen.oneOf(PieceType.all)
  yield Piece(c, pt)

  private val stateGen: Gen[GameState] = for
    n        <- Gen.choose(2, 24)
    idxs     <- Gen.pick(n, 0 to 63).map(_.toList)
    pieces   <- Gen.listOfN(n, pieceGen)
    color    <- colorGen
    castling <- Gen.choose(0, 15)
    half     <- Gen.choose(0, 100)
    full     <- Gen.choose(1, 200)
  yield buildState(idxs.map(Square.fromIndex).zip(pieces), color, castling, half, full)

  private def parseFen(fen: String): GameState =
    FenParser.parse(fen).fold(e => fail(s"bad FEN '$fen': $e"), identity)

  // States with no castling rights — the only ones where horizontal mirror is a win-probability symmetry.
  private val noCastlingStateGen: Gen[GameState] = for
    n      <- Gen.choose(2, 24)
    idxs   <- Gen.pick(n, 0 to 63).map(_.toList)
    pieces <- Gen.listOfN(n, pieceGen)
    color  <- colorGen
    half   <- Gen.choose(0, 100)
    full   <- Gen.choose(1, 200)
  yield buildState(idxs.map(Square.fromIndex).zip(pieces), color, 0, half, full)

  // --- Structural properties (any internally consistent state) ---

  property("colorFlip is an involution") {
    forAll(stateGen)(s => assertEquals(Symmetry.colorFlip(Symmetry.colorFlip(s)), s))
  }

  property("colorFlip flips the side to move") {
    forAll(stateGen)(s => assertEquals(Symmetry.colorFlip(s).activeColor, s.activeColor.opponent))
  }

  property("colorFlip yields a FEN-consistent state (survives serialize then parse)") {
    forAll(stateGen) { s =>
      val flipped  = Symmetry.colorFlip(s)
      val reparsed = FenParser.parse(FenParser.serialize(flipped)).fold(e => fail(e), identity)
      assertEquals(reparsed, flipped)
    }
  }

  property("canonical is always white to move and idempotent") {
    forAll(stateGen) { s =>
      val c = Symmetry.canonical(s)
      assert(c.activeColor.isWhite)
      assertEquals(Symmetry.canonical(c), c)
    }
  }

  property("a position and its colorFlip share one canonical key") {
    forAll(stateGen)(s => assertEquals(Symmetry.canonicalKey(s), Symmetry.canonicalKey(Symmetry.colorFlip(s))))
  }

  property("horizontalMirror is an involution") {
    forAll(stateGen)(s => assertEquals(Symmetry.horizontalMirror(Symmetry.horizontalMirror(s)), s))
  }

  property("horizontalMirror keeps the side to move") {
    forAll(stateGen)(s => assertEquals(Symmetry.horizontalMirror(s).activeColor, s.activeColor))
  }

  property("canonical folds horizontal mirror when there are no castling rights") {
    forAll(noCastlingStateGen) { s =>
      assertEquals(Symmetry.canonicalKey(s), Symmetry.canonicalKey(Symmetry.horizontalMirror(s)))
    }
  }

  // --- Example-based checks for the tricky remaps ---

  test("colorFlip swaps castling rights between colours, keeping the side") {
    assertEquals(Symmetry.colorFlip(parseFen("r3k2r/8/8/8/8/8/8/R3K2R w KQ - 0 1")).castlingRights, "kq")
    assertEquals(Symmetry.colorFlip(parseFen("r3k2r/8/8/8/8/8/8/R3K2R w Kq - 0 1")).castlingRights, "Qk")
  }

  test("colorFlip mirrors the en-passant target rank, keeping the file") {
    val flipped = Symmetry.colorFlip(parseFen("rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"))
    assert(flipped.activeColor.isBlack)
    assert(flipped.enPassant.contains(Square('d', 3)))
  }

  test("canonical leaves a white-to-move position unchanged") {
    val s = parseFen(FenParser.InitialPosition)
    assertEquals(Symmetry.canonical(s), s)
  }

  // Edge cases: an empty board and a full (32-piece) board. Pins are not relevant here — colorFlip is
  // a pure board transform and never consults move legality.

  test("colorFlip on an empty board only flips the side to move") {
    val empty = buildState(Nil, Color.White, 0, 0, 1)
    assertEquals(Symmetry.colorFlip(empty).activeColor, Color.Black)
    assertEquals(Symmetry.colorFlip(Symmetry.colorFlip(empty)), empty)
  }

  test("colorFlip is an involution on a full board (initial position)") {
    val full = parseFen(FenParser.InitialPosition)
    assertEquals(Symmetry.colorFlip(Symmetry.colorFlip(full)), full)
  }

  test("horizontalMirror swaps king-side and queen-side castling rights") {
    assertEquals(Symmetry.horizontalMirror(parseFen("r3k2r/8/8/8/8/8/8/R3K2R w K - 0 1")).castlingRights, "Q")
    assertEquals(Symmetry.horizontalMirror(parseFen("r3k2r/8/8/8/8/8/8/R3K2R w q - 0 1")).castlingRights, "k")
  }

  test("horizontalMirror reflects piece files (a <-> h)") {
    // Black bishop h2, white king e5, black knight f7.
    val mirrored = Symmetry.horizontalMirror(parseFen("8/5n2/8/4K3/8/8/7b/8 b - - 0 1"))
    assertEquals(mirrored.mailbox(Square('a', 2)), Piece(Color.Black, PieceType.Bishop)) // h2 -> a2
    assertEquals(mirrored.mailbox(Square('d', 5)), Piece(Color.White, PieceType.King))   // e5 -> d5
    assertEquals(mirrored.mailbox(Square('c', 7)), Piece(Color.Black, PieceType.Knight)) // f7 -> c7
  }
