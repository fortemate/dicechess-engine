package dicechess.engine.domain

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import org.scalacheck.Arbitrary

class PropertySpec extends ScalaCheckSuite:

  // --- Generators ---

  val colorGen: Gen[Color] = Gen.oneOf(Color.White, Color.Black)

  val pieceTypeGen: Gen[PieceType] = Gen.oneOf(PieceType.all)

  val pieceGen: Gen[Piece] = for
    color <- colorGen
    pt    <- pieceTypeGen
  yield Piece(color, pt)

  val squareGen: Gen[Square] = Gen.choose(0, 63).map(Square.fromIndex)

  val bitboardGen: Gen[Bitboard] = Gen.long.map(Bitboard.apply)

  // A generator for internally consistent GameState instances
  val gameStateGen: Gen[GameState] = for
    activeColor <- colorGen

    // Choose how many pieces on the board (1 to 32)
    numPieces <- Gen.choose(1, 32)

    // Choose distinct squares
    squaresIndices <- Gen.pick(numPieces, 0 to 63).map(_.toList)

    // Generate pieces for these squares
    pieces <- Gen.listOfN(numPieces, pieceGen)

    mailboxMap = squaresIndices
      .zip(pieces)
      .map { case (idx, piece) =>
        Square.fromIndex(idx) -> piece
      }
      .toMap

    // Build Bitboards from Mailbox
    whitePieces = mailboxMap
      .collect { case (sq, p) if p.color.isWhite => sq }
      .foldLeft(Bitboard.empty)((bb, sq) => bb | Bitboard.fromSquare(sq))
    blackPieces = mailboxMap
      .collect { case (sq, p) if p.color.isBlack => sq }
      .foldLeft(Bitboard.empty)((bb, sq) => bb | Bitboard.fromSquare(sq))
    pawns = mailboxMap
      .collect { case (sq, p) if p.pieceType == PieceType.Pawn => sq }
      .foldLeft(Bitboard.empty)((bb, sq) => bb | Bitboard.fromSquare(sq))
    knights = mailboxMap
      .collect { case (sq, p) if p.pieceType == PieceType.Knight => sq }
      .foldLeft(Bitboard.empty)((bb, sq) => bb | Bitboard.fromSquare(sq))
    bishops = mailboxMap
      .collect { case (sq, p) if p.pieceType == PieceType.Bishop => sq }
      .foldLeft(Bitboard.empty)((bb, sq) => bb | Bitboard.fromSquare(sq))
    rooks = mailboxMap
      .collect { case (sq, p) if p.pieceType == PieceType.Rook => sq }
      .foldLeft(Bitboard.empty)((bb, sq) => bb | Bitboard.fromSquare(sq))
    queens = mailboxMap
      .collect { case (sq, p) if p.pieceType == PieceType.Queen => sq }
      .foldLeft(Bitboard.empty)((bb, sq) => bb | Bitboard.fromSquare(sq))
    kings = mailboxMap
      .collect { case (sq, p) if p.pieceType == PieceType.King => sq }
      .foldLeft(Bitboard.empty)((bb, sq) => bb | Bitboard.fromSquare(sq))

    // Generate castling string from a subset of K, Q, k, q
    castlingChars <- Gen.someOf(List('K', 'Q', 'k', 'q'))
    castlingRights = if castlingChars.isEmpty then "-" else castlingChars.mkString("")

    // Generate 0 to 3 enPassant squares on rank 3 or 6
    numEP     <- Gen.choose(0, 3)
    epSquares <- Gen.listOfN(
      numEP,
      for
        file <- Gen.choose('a', 'h')
        rank <- Gen.oneOf(3, 6)
      yield Square(file, rank)
    )
    enPassantBB = epSquares.foldLeft(Bitboard.empty)((bb, sq) => bb.add(sq))

    numDice <- Gen.choose(0, 3)
    dice    <- Gen.listOfN(numDice, Gen.choose(1, 6))
    dicePool = dice.sorted

    halfMoveClock  <- Gen.choose(0, 100)
    fullMoveNumber <- Gen.choose(1, 500)
  yield
    var castlingInt = 0
    if castlingRights.contains('K') then castlingInt |= 1
    if castlingRights.contains('Q') then castlingInt |= 2
    if castlingRights.contains('k') then castlingInt |= 4
    if castlingRights.contains('q') then castlingInt |= 8

    var epFiles = 0
    var epV     = enPassantBB.value
    while epV != 0 do {
      val fileIdx = java.lang.Long.numberOfTrailingZeros(epV) % 8
      epFiles |= (1 << fileIdx)
      epV &= epV - 1
    }

    val mbBuilder = Array.fill(64)(Piece.Empty)
    mailboxMap.foreach { case (sq, p) => mbBuilder(sq.index) = p }
    val mailbox = Mailbox.fromBuilder(mbBuilder)

    val flags = GameFlags.fromList(
      color = activeColor,
      castlingRights = castlingInt,
      enPassantFiles = epFiles,
      dicePool = dicePool,
      halfMoveClock = halfMoveClock
    )

    GameState(
      whitePieces = whitePieces,
      blackPieces = blackPieces,
      pawns = pawns,
      knights = knights,
      bishops = bishops,
      rooks = rooks,
      queens = queens,
      kings = kings,
      mailbox = mailbox,
      flags = flags,
      enPassant = enPassantBB,
      fullMoveNumber = fullMoveNumber
    )

  // Custom implicits for ScalaCheck
  given Arbitrary[Color]     = Arbitrary(colorGen)
  given Arbitrary[PieceType] = Arbitrary(pieceTypeGen)
  given Arbitrary[Piece]     = Arbitrary(pieceGen)
  given Arbitrary[Square]    = Arbitrary(squareGen)
  given Arbitrary[Bitboard]  = Arbitrary(bitboardGen)
  given Arbitrary[GameState] = Arbitrary(gameStateGen)

  // --- Properties ---

  property("Square index mapping is perfectly bidirectional") {
    forAll(Gen.choose(0, 63)) { (idx: Int) =>
      val sq = Square.fromIndex(idx)
      sq.index == idx
    }
  }

  property("Square notation mapping is perfectly bidirectional") {
    forAll(squareGen) { (sq: Square) =>
      Square.fromNotation(sq.toNotation) == Some(sq)
    }
  }

  property("Piece packing roundtrips color and pieceType") {
    forAll(colorGen, pieceTypeGen) { (c: Color, pt: PieceType) =>
      val p = Piece(c, pt)
      p.color == c && p.pieceType == pt
    }
  }

  property("MicroMove packing roundtrips origin, destination, and promotion") {
    forAll(squareGen, squareGen, Gen.option(pieceTypeGen)) { (from: Square, to: Square, promo: Option[PieceType]) =>
      val mm = MicroMove(from, to, promo)
      mm.from == from && mm.to == to && mm.promotion == promo
    }
  }

  property("Bitboard addition and removal contains target square") {
    forAll(bitboardGen, squareGen) { (bb: Bitboard, sq: Square) =>
      val added = bb.add(sq)
      assert(added.contains(sq))

      val removed = added.remove(sq)
      assert(!removed.contains(sq))
    }
  }

  property("Bitboard popcount count equals elements in contains") {
    forAll(bitboardGen) { (bb: Bitboard) =>
      val squaresCount = (0 until 64).count(idx => bb.contains(Square.fromIndex(idx)))
      bb.count == squaresCount
    }
  }

  property("Bitboard bitwise operations correspond to boolean algebra") {
    forAll(bitboardGen, bitboardGen) { (bb1: Bitboard, bb2: Bitboard) =>
      val and = bb1 & bb2
      val or  = bb1 | bb2
      val xor = bb1 ^ bb2

      (0 until 64).forall { idx =>
        val sq = Square.fromIndex(idx)
        (and.contains(sq) == (bb1.contains(sq) && bb2.contains(sq))) &&
        (or.contains(sq) == (bb1.contains(sq) || bb2.contains(sq))) &&
        (xor.contains(sq) == (bb1.contains(sq) ^ bb2.contains(sq)))
      }
    }
  }

  property("FEN round-trip serialization and parsing yields identical GameState") {
    forAll(gameStateGen) { (state: GameState) =>
      val serialized = FenParser.serialize(state)
      val parsed     = FenParser.parse(serialized)
      parsed == Right(state)
    }
  }
