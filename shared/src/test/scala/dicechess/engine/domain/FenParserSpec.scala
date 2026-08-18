package dicechess.engine.domain

import munit.FunSuite

class FenParserSpec extends FunSuite:

  test("FenParser should correctly parse the standard initial position") {
    val fen    = FenParser.InitialPosition
    val parsed = FenParser.parse(fen)

    assert(parsed.isRight)
    val state = parsed.getOrElse(sys.error("Parsing failed"))

    assertEquals(state.activeColor, Color.White)
    assertEquals(state.castlingRights, "KQkq")
    assertEquals(state.enPassant, Bitboard.empty)
    assertEquals(state.halfMoveClock, 0)
    assertEquals(state.fullMoveNumber, 1)

    // Bitboard property checks
    assertEquals(state.whitePieces.count, 16)
    assertEquals(state.blackPieces.count, 16)
    assertEquals(state.pawns.count, 16) // 8 white + 8 black
    assertEquals(state.kings.count, 2)
    assertEquals(state.mailbox.toArray.count(!_.isEmpty), 32)
  }

  test("FenParser should serialize the initial position back to identical FEN") {
    val fen        = FenParser.InitialPosition
    val parsed     = FenParser.parse(fen).toOption.get
    val serialized = FenParser.serialize(parsed)

    assertEquals(serialized, fen)
  }

  test("FenParser should correctly parse various castling rights") {
    val base = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w "
    val tail = " - 0 1"

    val rights = List("KQkq", "KQ", "kq", "Kk", "Q", "-", "q")
    for r <- rights do
      val fen    = base + r + tail
      val parsed = FenParser.parse(fen).toOption.get

      assertEquals(FenParser.serialize(parsed), fen)

      val expectedInt = r.foldLeft(0) { (acc, c) =>
        c match
          case 'K' => acc | 1
          case 'Q' => acc | 2
          case 'k' => acc | 4
          case 'q' => acc | 8
          case _   => acc
      }
      assertEquals(parsed.flags.castlingRights, expectedInt)
  }

  test("return Left for duplicate castling characters (KK)") {
    val result = FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KK - 0 1")
    assertEquals(result, Left("Duplicate castling character 'K'"))
  }

  test("return Left for duplicate castling characters (KQqQ)") {
    val result = FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQqQ - 0 1")
    assertEquals(result, Left("Duplicate castling character 'Q'"))
  }

  test("FenParser should return Left for invalid castling characters") {
    val invalidFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQx - 0 1"
    val parsed     = FenParser.parse(invalidFen)

    assert(parsed.isLeft)
    assert(parsed.left.toOption.get.contains("Invalid castling character 'x'"))

    val hyphenInsideFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w K-q - 0 1"
    val parsedHyphen    = FenParser.parse(hyphenInsideFen)
    assert(parsedHyphen.isLeft)
    assert(parsedHyphen.left.toOption.get.contains("Invalid castling character '-'"))
  }

  test("FenParser should return Left for invalid castling field length") {
    val emptyCastlingFen =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w  - 0 1" // Notice the double space making an empty field
    val emptyParsed = FenParser.parse(emptyCastlingFen)
    assert(emptyParsed.isLeft)
    assert(emptyParsed.left.toOption.get.contains("Invalid castling field length"))

    val tooLongFen    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkqK - 0 1"
    val tooLongParsed = FenParser.parse(tooLongFen)
    assert(tooLongParsed.isLeft)
    assert(tooLongParsed.left.toOption.get.contains("Invalid castling field length: 5"))
  }

  test("FenParser should correctly handle complex mid-game positions") {
    val complexFen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
    val parsed     = FenParser.parse(complexFen)

    assert(parsed.isRight)
    val state      = parsed.toOption.get
    val serialized = FenParser.serialize(state)

    assertEquals(serialized, complexFen)
  }

  test("FenParser should correctly parse en passant targets") {
    val fen    = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    val parsed = FenParser.parse(fen).toOption.get

    assertEquals(parsed.enPassant, Bitboard.fromSquare(Square('e', 3)))
    assertEquals(FenParser.serialize(parsed), fen)
  }

  test("FenParser should correctly parse and serialize multiple en passant targets") {
    val fen    = "rnbqkbnr/pppppppp/8/8/P1P1P3/8/1P1P1PPP/RNBQKBNR b KQkq a3c3e3 0 1"
    val parsed = FenParser.parse(fen)

    assert(parsed.isRight)
    val state = parsed.toOption.get
    assertEquals(
      state.enPassant,
      Bitboard.fromSquare(Square('a', 3)) | Bitboard.fromSquare(Square('c', 3)) | Bitboard.fromSquare(Square('e', 3))
    )
    assertEquals(FenParser.serialize(state), fen)
  }

  test("FenParser should return Left for an invalid board layout") {
    // 9 files on the first rank
    val invalidFen = "rnbqkbnrP/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    val parsed     = FenParser.parse(invalidFen)

    assert(parsed.isLeft)
    assert(parsed.left.toOption.get.contains("overflows 8 files"))
  }

  test("FenParser should return Left for unknown piece characters") {
    val invalidFen = "rnbqkbnr/ppppXppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    val parsed     = FenParser.parse(invalidFen)

    assert(parsed.isLeft)
    assert(parsed.left.toOption.get.contains("Unknown piece character 'X'"))
  }

  test("FenParser should correctly parse and serialize DFEN with 7th field dice pool") {
    val fen    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PPP"
    val parsed = FenParser.parse(fen)

    assert(parsed.isRight)
    val state = parsed.toOption.get
    assertEquals(state.dicePool, List(1, 1, 1))
    assertEquals(FenParser.serialize(state), fen)
  }

  test("FenParser should support backward compatibility for 6-field standard FEN and default dicePool to Nil") {
    val fen    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    val parsed = FenParser.parse(fen)

    assert(parsed.isRight)
    val state = parsed.toOption.get
    assertEquals(state.dicePool, Nil)
    assertEquals(FenParser.serialize(state), fen)
  }

  test("FenParser should parse 7-field FEN with empty dice pool '-' and serialize to 6-field FEN") {
    val fen    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 -"
    val parsed = FenParser.parse(fen)

    assert(parsed.isRight)
    val state = parsed.toOption.get
    assertEquals(state.dicePool, Nil)
    assertEquals(FenParser.serialize(state), "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
  }

  // Regression, #551: every field these cover used to parse as a Right holding a value that was not what
  // came in — an over-long dice pool arrived shortened, an over-large number arrived wrapped. The bug was
  // never "a weird FEN yields a weird state"; it was the parser reporting success either way, which is how
  // malformed input from an external ingest source reaches analytics unremarked.
  private val boundsBase = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"

  test("return Left for a field after the dice pool instead of dropping it (#551)") {
    // An eighth token parsed fine and then vanished from serialize — the same shape as the two truncations
    // below, one field further out.
    val parsed = FenParser.parse(s"$boundsBase 0 1 PNB extra")

    assert(parsed.isLeft, s"expected a Left, got $parsed")
    assert(parsed.left.toOption.get.contains("at most 7 fields"), parsed.left.toOption.get)

    // Exactly seven is the format, and stays legal.
    assert(FenParser.parse(s"$boundsBase 0 1 PNB").isRight)
  }

  test("return Left for a dice pool longer than the three slots a turn can hold (#551)") {
    // Ten dice in used to come back as three: GameFlags.fromList keeps the first three and drops the rest.
    val parsed = FenParser.parse(s"$boundsBase 0 1 PPPPPPPPPP")

    assert(parsed.isLeft, s"expected a Left, got $parsed")
    val message = parsed.left.toOption.get
    assert(message.contains("dice-pool"), s"the message must name the field, got: $message")
    assert(message.contains("PPPPPPPPPP"), s"the message must quote the offending field, got: $message")

    // One past the bound, not just wildly over it.
    assert(FenParser.parse(s"$boundsBase 0 1 PNBR").isLeft)
  }

  test("a full three-dice pool is still accepted and round-trips through serialize (#551)") {
    // The guard against "reject everything": the fix must not narrow what the format legitimately allows.
    for pool <- List("P", "PN", "PNB", "QK", "PPP", "KKK") do
      val fen    = s"$boundsBase 0 1 $pool"
      val parsed = FenParser.parse(fen)

      assert(parsed.isRight, s"pool '$pool' should parse, got $parsed")
      assertEquals(FenParser.serialize(parsed.toOption.get), fen, s"pool '$pool' should round-trip")
  }

  test("return Left for a half-move clock past what the 7-bit field holds (#551)") {
    // 128 was masked to 0 and 200 to 72 — plausible numbers with no relation to the input.
    assertEquals(
      FenParser.parse(s"$boundsBase 128 1"),
      Left(s"Invalid half-move clock '128': expected 0-${GameFlags.MaxHalfMoveClock}")
    )
    assert(FenParser.parse(s"$boundsBase 200 1").isLeft)

    // The boundary value itself stays legal.
    val atMax = FenParser.parse(s"$boundsBase ${GameFlags.MaxHalfMoveClock} 1")
    assert(atMax.isRight, s"expected the maximum clock to parse, got $atMax")
    assertEquals(atMax.toOption.get.flags.halfMoveClock, GameFlags.MaxHalfMoveClock)
  }

  test("return Left for numeric fields too large to hold instead of a wrapped value (#551)") {
    // Int accumulation wrapped in silence: 99999999999 came out as 127 after masking, and 4294967296 — a
    // clean multiple of 2^32 — as 0, which slipped past the old `v >= 0` check precisely because it wrapped
    // to something non-negative.
    assert(FenParser.parse(s"$boundsBase 99999999999 1").isLeft)
    assert(FenParser.parse(s"$boundsBase 4294967296 1").isLeft)

    // Same accumulator, and the full-move number has no field bound of its own to fall back on.
    assert(FenParser.parse(s"$boundsBase 0 4294967297").isLeft)
    assert(FenParser.parse(s"$boundsBase 0 99999999999").isLeft)

    // A full-move number that merely looks big is fine — the bound is Int.MaxValue, not a digit count.
    val large = FenParser.parse(s"$boundsBase 0 2000000000")
    assert(large.isRight, s"expected a large but representable full-move number to parse, got $large")
    assertEquals(large.toOption.get.fullMoveNumber, 2000000000)
  }
