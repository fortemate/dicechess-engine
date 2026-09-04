package dicechess.engine.domain

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen

class FenParserRobustnessSpec extends ScalaCheckSuite:

  // --- Sample Valid FEN Base Strings ---

  private val validFens: List[String] = List(
    FenParser.InitialPosition,
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "8/8/8/8/8/8/8/8 w - - 0 1",
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PPP",
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1 pnb",
    "rnbqkbnr/pppppppp/8/8/P1P1P3/8/1P1P1PPP/RNBQKBNR b KQkq a3c3e3 0 1",
    "rnbqkbnr/pppppppp/rnbqkbnr/pppppppp/PPPPPPPP/RNBQKBNR/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  )

  private val validFenGen: Gen[String] = Gen.oneOf(validFens)

  // --- Generators for Negative Fuzzing ---

  private val printableCharGen: Gen[Char] = Gen.choose(32.toChar, 126.toChar)

  // Arbitrary string generator including empty, printable, ascii, whitespace, and giant strings
  private val arbitraryStringGen: Gen[String] = Gen.oneOf(
    Gen.const(""),
    Gen.asciiStr,
    Gen.alphaNumStr,
    Gen.listOf(Gen.choose(0.toChar, 255.toChar)).map(_.mkString),
    Gen.listOfN(2000, Gen.asciiChar).map(_.mkString),   // Giant ASCII string (~2KB)
    Gen.listOfN(5000, printableCharGen).map(_.mkString) // Giant string (~5KB)
  )

  // Mutator for FEN strings
  private val mutatedFenGen: Gen[String] = for
    base         <- validFenGen
    mutationType <- Gen.choose(0, 5)
    mutated      <- mutationType match
      case 0 => // Single character insertion, deletion, or substitution
        for
          idx    <- Gen.choose(0, math.max(0, base.length - 1))
          c      <- printableCharGen
          action <- Gen.oneOf("delete", "insert", "substitute")
        yield action match
          case "delete" if base.nonEmpty     => base.patch(idx, "", 1)
          case "insert"                      => base.patch(idx, c.toString, 0)
          case "substitute" if base.nonEmpty => base.patch(idx, c.toString, 1)
          case _                             => base

      case 1 => // Drop or append fields
        val parts = base.split(" ").toList
        for
          action    <- Gen.oneOf("drop", "append", "duplicate")
          dropCount <- if parts.length > 1 then Gen.choose(1, parts.length - 1) else Gen.const(1)
        yield action match
          case "drop" if parts.length > 1 =>
            parts.take(dropCount).mkString(" ")
          case "append" =>
            (parts ++ List("extra1", "extra2", "extra3")).mkString(" ")
          case "duplicate" =>
            (parts ++ parts).mkString(" ")
          case _ => base

      case 2 => // Corrupt piece placement field
        val parts = base.split(" ").toArray
        if parts.nonEmpty then
          val ranks = parts(0).split("/")
          if ranks.nonEmpty then
            for rIdx <- Gen.choose(0, ranks.length - 1) yield
              val corruptedRank = ranks(rIdx) + "X"
              ranks(rIdx) = corruptedRank
              parts(0) = ranks.mkString("/")
              parts.mkString(" ")
          else Gen.const(base)
        else Gen.const(base)

      case 3 => // Corrupt numeric fields (clocks)
        val parts = base.split(" ").toArray
        if parts.length >= 6 then
          for
            hm <- Gen.oneOf("-1", "128", "256", "99999999999999999999", "abc")
            fm <- Gen.oneOf("0", "-5", "2147483648", "xyz")
          yield
            parts(4) = hm
            parts(5) = fm
            parts.mkString(" ")
        else Gen.const(base)

      case 4 => // Corrupt dice pool field
        val parts = base.split(" ").toArray
        if parts.length >= 7 then
          for pool <- Gen.oneOf("PPPP", "xyz", "123", "pnbq", "PPPN") yield
            parts(6) = pool
            parts.mkString(" ")
        else if parts.length == 6 then Gen.const((parts :+ "PPPP").mkString(" "))
        else Gen.const(base)

      case _ => // Random character swapping
        Gen.listOfN(base.length, Gen.asciiChar).map(_.mkString)
  yield mutated

  // --- Properties ---

  property("Property 1: FenParser.parse never throws on arbitrary ASCII / Unicode / giant strings") {
    forAll(arbitraryStringGen) { (input: String) =>
      val result = FenParser.parse(input)
      result match
        case Left(msg) =>
          assert(msg.nonEmpty, "Failure message must be non-empty")
          true
        case Right(state) =>
          // If parse succeeded, serialization must also execute without throwing
          val serialized = FenParser.serialize(state)
          assert(serialized.nonEmpty)
          true
    }
  }

  property("Property 2: FenParser.parse never throws on mutated valid DFEN strings") {
    forAll(mutatedFenGen) { (input: String) =>
      val result = FenParser.parse(input)
      result match
        case Left(msg) =>
          assert(msg.nonEmpty, "Failure message must be non-empty")
          true
        case Right(state) =>
          val serialized = FenParser.serialize(state)
          assert(serialized.nonEmpty)
          true
    }
  }

  // --- Property 3: Explicit Boundary Inputs Return Informative Left or Valid Right ---

  test("Property 3a: Half-move clock boundary and overflow inputs return informative Left") {
    val invalidClocks = List("-1", "128", "200", "2147483648", "99999999999999999999")
    val base          = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"

    for clock <- invalidClocks do
      val fen = s"$base $clock 1"
      FenParser.parse(fen) match
        case Left(msg) =>
          assert(msg.nonEmpty, s"Expected non-empty Left error for half-move clock '$clock'")
          assert(msg.contains("half-move clock"), s"Error message should mention half-move clock, got: $msg")
        case Right(state) =>
          fail(s"Expected Left error for invalid half-move clock '$clock', but got Right($state)")
  }

  test("Property 3b: Full-move number boundary and overflow inputs return informative Left") {
    val invalidClocks = List("0", "-1", "-100", "2147483648", "99999999999999999999")
    val base          = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0"

    for clock <- invalidClocks do
      val fen = s"$base $clock"
      FenParser.parse(fen) match
        case Left(msg) =>
          assert(msg.nonEmpty, s"Expected non-empty Left error for full-move number '$clock'")
          assert(msg.contains("full-move"), s"Error message should mention full-move number, got: $msg")
        case Right(state) =>
          fail(s"Expected Left error for invalid full-move number '$clock', but got Right($state)")
  }

  test("Property 3c: Rank file overflow and underflow inputs return informative Left") {
    val overflowFen = "ppppppppp/8/8/8/8/8/8/8 w - - 0 1" // 9 pawns on rank 8
    FenParser.parse(overflowFen) match
      case Left(msg) =>
        assert(msg.contains("overflows 8 files") || msg.contains("must have 8 files"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left error for rank overflow, got Right($state)")

    val underflowFen = "7/8/8/8/8/8/8/8 w - - 0 1" // 7 files on rank 8
    FenParser.parse(underflowFen) match
      case Left(msg) =>
        assert(msg.contains("must have 8 files"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left error for rank underflow, got Right($state)")

    val wrongRanksFen = "8/8/8/8/8/8/8 w - - 0 1" // 7 ranks
    FenParser.parse(wrongRanksFen) match
      case Left(msg) =>
        assert(msg.contains("board must have 8 ranks"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left error for wrong ranks count, got Right($state)")
  }

  test("Property 3d: Field count boundaries (<4 or >7 fields) return informative Left") {
    val insufficientFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq"
    FenParser.parse(insufficientFen) match
      case Left(msg) =>
        assert(msg.contains("insufficient parts"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left for insufficient fields, got Right($state)")

    val tooManyFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PNB extra"
    FenParser.parse(tooManyFen) match
      case Left(msg) =>
        assert(msg.contains("at most 7 fields"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left for too many fields, got Right($state)")
  }

  test("Property 3e: Dice pool boundary and invalid character inputs return informative Left") {
    val overlongDiceFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PPNB"
    FenParser.parse(overlongDiceFen) match
      case Left(msg) =>
        assert(msg.contains("dice-pool"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left for overlong dice pool, got Right($state)")

    val invalidCharDiceFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PX"
    FenParser.parse(invalidCharDiceFen) match
      case Left(msg) =>
        assert(msg.contains("dice-pool character"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left for invalid dice pool char, got Right($state)")
  }

  test("Property 3f: Castling field corruptions return informative Left") {
    val duplicateCastlingFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KK - 0 1"
    FenParser.parse(duplicateCastlingFen) match
      case Left(msg) =>
        assert(msg.contains("Duplicate castling character"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left for duplicate castling, got Right($state)")

    val invalidCastlingFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w X - 0 1"
    FenParser.parse(invalidCastlingFen) match
      case Left(msg) =>
        assert(msg.contains("Invalid castling character"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left for invalid castling char, got Right($state)")

    val tooLongCastlingFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkqK - 0 1"
    FenParser.parse(tooLongCastlingFen) match
      case Left(msg) =>
        assert(msg.contains("Invalid castling field length"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left for too long castling, got Right($state)")
  }

  test("Property 3g: En-passant field corruptions return informative Left") {
    val invalidEpFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - z9 0 1"
    FenParser.parse(invalidEpFen) match
      case Left(msg) =>
        assert(msg.contains("Invalid en-passant notation"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left for invalid en-passant, got Right($state)")

    val duplicateEpFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - e3e3 0 1"
    FenParser.parse(duplicateEpFen) match
      case Left(msg) =>
        assert(msg.contains("Duplicate en-passant square"), s"Got msg: $msg")
      case Right(state) =>
        fail(s"Expected Left for duplicate en-passant square, got Right($state)")
  }

  test("Property 3h: Boards without kings and max-piece boards parse safely without throwing") {
    val emptyBoardFen = "8/8/8/8/8/8/8/8 w - - 0 1"
    val emptyParsed   = FenParser.parse(emptyBoardFen)
    assert(emptyParsed.isRight)
    assertEquals(FenParser.serialize(emptyParsed.toOption.get), emptyBoardFen)

    val maxPieceFen = "rnbqkbnr/pppppppp/rnbqkbnr/pppppppp/PPPPPPPP/RNBQKBNR/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    val maxParsed   = FenParser.parse(maxPieceFen)
    assert(maxParsed.isRight)
    assertEquals(maxParsed.toOption.get.mailbox.toArray.count(!_.isEmpty), 64)
  }
