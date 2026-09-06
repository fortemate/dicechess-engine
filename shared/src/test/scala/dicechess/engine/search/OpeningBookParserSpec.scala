package dicechess.engine.search

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

class OpeningBookParserSpec extends ScalaCheckSuite:

  private val printableCharGen: Gen[Char] = Gen.choose(32.toChar, 126.toChar)

  // String generator for property 1 (Total function) bounded ≤ 3000 chars
  private val arbitraryStringGen: Gen[String] = Gen.oneOf(
    Gen.const(""),
    Gen.asciiStr.map(_.take(3000)),
    Gen.listOf(Gen.choose(0.toChar, 255.toChar)).map(_.take(3000).mkString),
    Gen.listOf(Gen.choose(0.toChar, 0xd7ff.toChar)).map(_.take(3000).mkString), // Unicode plane 0 chars
    Gen.listOfN(2000, Gen.oneOf('\t', '\n', '\r', ' ', 'a', 'b', '1', '2', 'ñ', 'x')).map(_.mkString),
    Gen.listOfN(3000, printableCharGen).map(_.mkString)
  )

  // Generator for keys and values: non-empty after trim, containing no \t, \n, or \r
  private val validPartGen: Gen[String] = for
    s <- Gen
      .nonEmptyListOf(Gen.oneOf(printableCharGen, Gen.choose('a', 'z'), Gen.choose('0', '9')))
      .map(_.mkString)
      .filter(str => !str.exists(c => c == '\t' || c == '\n' || c == '\r'))
    if s.trim.nonEmpty
  yield s.trim

  // Generator for valid non-empty Map[String, String] (1 to 50 entries)
  private val validMapGen: Gen[Map[String, String]] = for
    size  <- Gen.choose(1, 50)
    pairs <- Gen.listOfN(size, for k <- validPartGen; v <- validPartGen yield (k, v))
  yield pairs.toMap

  // Generator for malformed lines
  private val malformedLineGen: Gen[String] = Gen.oneOf(
    // No tab
    validPartGen.map(s => s"$s $s"),
    // Two or more tabs
    for k <- validPartGen; v1 <- validPartGen; v2 <- validPartGen yield s"$k\t$v1\t$v2",
    // Blank key
    for v <- validPartGen yield s"\t$v",
    for v <- validPartGen yield s"   \t$v",
    // Blank value
    for k <- validPartGen yield s"$k\t",
    for k <- validPartGen yield s"$k\t   "
  )

  // --- Property 1: Total function ---

  property("Property 1: OpeningBookParser.parse never throws for arbitrary input") {
    forAll(arbitraryStringGen) { (input: String) =>
      val result = OpeningBookParser.parse(input)
      assert(result.isLeft || result.isRight)
      true
    }
  }

  // --- Property 2: Round-trip ---

  property("Property 2a: Standard round-trip with \\n line endings") {
    forAll(validMapGen) { (map: Map[String, String]) =>
      val tsv    = map.map((k, v) => s"$k\t$v").mkString("\n")
      val parsed = OpeningBookParser.parse(tsv)
      assertEquals(parsed, Right(map))
    }
  }

  property("Property 2b: Round-trip with \\r\\n line endings") {
    forAll(validMapGen) { (map: Map[String, String]) =>
      val tsv    = map.map((k, v) => s"$k\t$v").mkString("\r\n")
      val parsed = OpeningBookParser.parse(tsv)
      assertEquals(parsed, Right(map))
    }
  }

  property("Property 2c: Round-trip with interleaved blank or whitespace-only lines") {
    val blankLineGen   = Gen.oneOf("", "  ", "\t", " \t \r", "\r")
    val interleavedGen = for
      map        <- validMapGen
      blankLines <- Gen.listOf(blankLineGen)
      validLines = map.map((k, v) => s"$k\t$v").toList
      combined <- Gen.pick(validLines.size + blankLines.size, validLines ++ blankLines)
    yield (map, combined.mkString("\n"))

    forAll(interleavedGen) { case (map, tsv) =>
      val parsed = OpeningBookParser.parse(tsv)
      assertEquals(parsed, Right(map))
    }
  }

  property("Property 2d: Round-trip with leading and trailing spaces around keys and values") {
    val spacesGen = Gen.oneOf("", " ", "  ", "\t").map(_.replace('\t', ' ')) // whitespace spaces only
    forAll(validMapGen, spacesGen, spacesGen, spacesGen, spacesGen) { (map, padK1, padK2, padV1, padV2) =>
      val tsv    = map.map((k, v) => s"$padK1$k$padK2\t$padV1$v$padV2").mkString("\n")
      val parsed = OpeningBookParser.parse(tsv)
      assertEquals(parsed, Right(map))
    }
  }

  // --- Property 3: Malformed lines are all reported ---

  property("Property 3: Malformed lines are all reported when n >= 1 malformed lines are injected") {
    val injectedGen = for
      map            <- validMapGen
      numMalformed   <- Gen.choose(1, 10)
      malformedLines <- Gen.listOfN(numMalformed, malformedLineGen)
      validLines = map.map((k, v) => s"$k\t$v").toList
      allLines   = validLines ++ malformedLines
      shuffled <- Gen.pick(allLines.size, allLines)
    yield (numMalformed, shuffled.mkString("\n"))

    forAll(injectedGen) { case (n, tsv) =>
      OpeningBookParser.parse(tsv) match
        case Left(err) =>
          val count = err.getMessage.sliding("Malformed line".length).count(_ == "Malformed line")
          assertEquals(count, n, s"Expected $n 'Malformed line' occurrences in error: ${err.getMessage}")
        case Right(res) =>
          fail(s"Expected Left error for $n injected malformed lines, but got Right($res)")
    }
  }

  // --- Property 4: Duplicate keys pin current behavior ---

  // Behaviour pin: when the same key appears twice, the later line wins (toMap semantics).
  property("Property 4: Pin current behaviour: when duplicate keys occur, the later line wins") {
    forAll(validPartGen, validPartGen, validPartGen) { (key, val1, val2) =>
      val tsv    = s"$key\t$val1\n$key\t$val2"
      val parsed = OpeningBookParser.parse(tsv)
      assertEquals(parsed, Right(Map(key -> val2)))
    }
  }

  // --- Unit Tests for OpeningBookParser.parse ---

  test("parse handles valid single and multi-line TSV data") {
    val input =
      """rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - BPR	e2e4,f1c4
        |rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - BPR	e7e5,g8f6""".stripMargin

    val expected = Map(
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - BPR"   -> "e2e4,f1c4",
      "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - BPR" -> "e7e5,g8f6"
    )

    assertEquals(OpeningBookParser.parse(input), Right(expected))
  }

  test("parse returns empty map for empty string or whitespace-only input") {
    assertEquals(OpeningBookParser.parse(""), Right(Map.empty[String, String]))
    assertEquals(OpeningBookParser.parse("   \n\t\n  \r\n  "), Right(Map.empty[String, String]))
  }

  test("parse trims leading and trailing whitespace from keys and values") {
    val input    = "  posKey1   \t  e2e4,f1c4   \n   posKey2\t   e7e5  "
    val expected = Map(
      "posKey1" -> "e2e4,f1c4",
      "posKey2" -> "e7e5"
    )
    assertEquals(OpeningBookParser.parse(input), Right(expected))
  }

  test("parse handles Windows CRLF line endings") {
    val input    = "key1\tval1\r\nkey2\tval2\r\n"
    val expected = Map(
      "key1" -> "val1",
      "key2" -> "val2"
    )
    assertEquals(OpeningBookParser.parse(input), Right(expected))
  }

  test("parse overwrites earlier entry when duplicate key appears") {
    val input = "key1\tfirst\nkey1\tsecond"
    assertEquals(OpeningBookParser.parse(input), Right(Map("key1" -> "second")))
  }

  test("parse returns Left with exception when line is missing tab delimiter") {
    val result = OpeningBookParser.parse("invalid_line_without_tab")
    assert(result.isLeft)
    val err = result.left.toOption.get
    assert(err.isInstanceOf[IllegalArgumentException])
    assertEquals(err.getMessage, "Malformed line: invalid_line_without_tab")
  }

  test("parse returns Left when key is empty or whitespace-only") {
    val result = OpeningBookParser.parse("\tval1")
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.getMessage, "Malformed line: \tval1")

    val wsResult = OpeningBookParser.parse("   \tval1")
    assert(wsResult.isLeft)
    assertEquals(wsResult.left.toOption.get.getMessage, "Malformed line:    \tval1")
  }

  test("parse returns Left when value is empty or whitespace-only") {
    val result = OpeningBookParser.parse("key1\t")
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.getMessage, "Malformed line: key1\t")

    val wsResult = OpeningBookParser.parse("key1\t   ")
    assert(wsResult.isLeft)
    assertEquals(wsResult.left.toOption.get.getMessage, "Malformed line: key1\t   ")
  }

  test("parse returns Left when line contains extra tab columns") {
    val result = OpeningBookParser.parse("key1\tval1\textra")
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.getMessage, "Malformed line: key1\tval1\textra")
  }

  test("parse aggregates multiple malformed line errors separated by semicolon") {
    val input = "key1\tval1\nbad1\nkey2\tval2\nbad2\textra1\textra2"

    val result = OpeningBookParser.parse(input)
    assert(result.isLeft)
    val msg = result.left.toOption.get.getMessage
    assertEquals(msg, "Malformed line: bad1; Malformed line: bad2\textra1\textra2")
  }
