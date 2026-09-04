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

  // Mutator for FEN strings — all operations are purely functional (no in-place array mutation)
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
            parts.dropRight(dropCount).mkString(" ")
          case "append" =>
            (parts ++ List("extra1", "extra2", "extra3")).mkString(" ")
          case "duplicate" =>
            (parts ++ parts).mkString(" ")
          case _ => base

      case 2 => // Corrupt piece placement field (purely functional — no in-place mutation)
        val parts = base.split(" ").toIndexedSeq
        if parts.nonEmpty then
          val ranks = parts(0).split("/").toIndexedSeq
          if ranks.nonEmpty then
            for rIdx <- Gen.choose(0, ranks.length - 1) yield
              val updatedRanks = ranks.updated(rIdx, ranks(rIdx) + "X")
              val updatedParts = parts.updated(0, updatedRanks.mkString("/"))
              updatedParts.mkString(" ")
          else Gen.const(base)
        else Gen.const(base)

      case 3 => // Corrupt numeric fields (clocks) — purely functional
        val parts = base.split(" ").toIndexedSeq
        if parts.length >= 6 then
          for
            hm <- Gen.oneOf("-1", "128", "256", "99999999999999999999", "abc")
            fm <- Gen.oneOf("0", "-5", "2147483648", "xyz")
          yield
            parts.updated(4, hm).updated(5, fm).mkString(" ")
        else Gen.const(base)

      case 4 => // Corrupt dice pool field — purely functional
        val parts = base.split(" ").toIndexedSeq
        if parts.length >= 7 then
          for pool <- Gen.oneOf("PPPP", "xyz", "123", "pnbq", "PPPN") yield
            parts.updated(6, pool).mkString(" ")
        else if parts.length == 6 then Gen.const((parts :+ "PPPP").mkString(" "))
        else Gen.const(base)

      case _ => // Random character swapping
        Gen.listOfN(base.length, Gen.asciiChar).map(_.mkString)
  yield mutated

  // --- Properties ---

  property("FenParser.parse never throws on arbitrary ASCII / Unicode / giant strings") {
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

  property("FenParser.parse never throws on mutated valid DFEN strings") {
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

  property("parse(serialize(parse(fen))) == parse(fen) for valid FENs (roundtrip idempotence)") {
    forAll(validFenGen) { (fen: String) =>
      FenParser.parse(fen) match
        case Right(state) =>
          val serialized   = FenParser.serialize(state)
          val roundTripped = FenParser.parse(serialized)
          assertEquals(roundTripped, Right(state))
          true
        case Left(_) =>
          // Not applicable for invalid FENs
          true
    }
  }
