package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class PassedPawnsSpec extends FunSuite:

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  test("initial position has zero passed pawns for both colors"):
    val state = parse(FenParser.InitialPosition)
    for color <- List(Color.White, Color.Black) do
      assertEquals(PassedPawns.count(state, color), 0)
      assertEquals(PassedPawns.maxRank(state, color), 0)
      assertEquals(PassedPawns.countAndMaxRank(state, color), (0, 0))
      assertEquals(PassedPawns.bitboard(state, color), Bitboard.empty)

  test("bare kings position has zero passed pawns"):
    val state = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    for color <- List(Color.White, Color.Black) do
      assertEquals(PassedPawns.count(state, color), 0)
      assertEquals(PassedPawns.maxRank(state, color), 0)

  test("white pawn is blocked by an opposing pawn on the same file ahead"):
    val state = parse("4k3/8/8/4p3/8/4P3/8/4K3 w - - 0 1") // White e3, Black e5
    assertEquals(PassedPawns.count(state, Color.White), 0)
    assertEquals(PassedPawns.maxRank(state, Color.White), 0)

  test("white pawn is blocked by an opposing pawn on adjacent files ahead"):
    val leftBlocked  = parse("4k3/8/8/3p4/8/4P3/8/4K3 w - - 0 1") // White e3, Black d5
    val rightBlocked = parse("4k3/8/8/5p2/8/4P3/8/4K3 w - - 0 1") // White e3, Black f5
    assertEquals(PassedPawns.count(leftBlocked, Color.White), 0)
    assertEquals(PassedPawns.count(rightBlocked, Color.White), 0)

  test("white pawn is passed when opposing pawns are on adjacent files behind or on the same rank"):
    val behind = parse("4k3/8/8/8/4P3/3p4/8/4K3 w - - 0 1") // White e4, Black d3
    assertEquals(PassedPawns.count(behind, Color.White), 1)
    assertEquals(PassedPawns.maxRank(behind, Color.White), 3) // rank 4 - 1 = 3

    val sameRank = parse("4k3/8/8/8/3pP3/8/8/4K3 w - - 0 1") // White e4, Black d4
    assertEquals(PassedPawns.count(sameRank, Color.White), 1)
    assertEquals(PassedPawns.maxRank(sameRank, Color.White), 3)

  test("opposing pawn two files away does not block a passed pawn"):
    val state = parse("4k3/8/8/2p5/4P3/8/8/4K3 w - - 0 1") // White e4, Black c5 (c-file is 2 away from e-file)
    assertEquals(PassedPawns.count(state, Color.White), 1)
    assertEquals(PassedPawns.maxRank(state, Color.White), 3)

  test("edge files correctly respect board boundaries for adjacent spans"):
    // a-file pawn blocked by b-file
    val aBlocked = parse("4k3/8/8/1p6/P7/8/8/4K3 w - - 0 1") // White a4, Black b5
    assertEquals(PassedPawns.count(aBlocked, Color.White), 0)

    // a-file pawn passed when b-file opposing pawn is behind
    val aPassed = parse("4k3/8/8/8/P7/1p6/8/4K3 w - - 0 1") // White a4, Black b3
    assertEquals(PassedPawns.count(aPassed, Color.White), 1)
    assertEquals(PassedPawns.maxRank(aPassed, Color.White), 3)

    // h-file pawn blocked by g-file
    val hBlocked = parse("4k3/8/8/6p1/7P/8/8/4K3 w - - 0 1") // White h4, Black g5
    assertEquals(PassedPawns.count(hBlocked, Color.White), 0)

    // h-file pawn passed when g-file opposing pawn is behind
    val hPassed = parse("4k3/8/8/8/7P/6p1/8/4K3 w - - 0 1") // White h4, Black g3
    assertEquals(PassedPawns.count(hPassed, Color.White), 1)
    assertEquals(PassedPawns.maxRank(hPassed, Color.White), 3)

  test("advancement rank maps 2-7 to 1-6 from each side's back rank"):
    // White on rank 2 and 7
    val whiteR2 = parse("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1")
    assertEquals(PassedPawns.maxRank(whiteR2, Color.White), 1) // 2 - 1 = 1

    val whiteR7 = parse("4k3/4P3/8/8/8/8/8/4K3 w - - 0 1")
    assertEquals(PassedPawns.maxRank(whiteR7, Color.White), 6) // 7 - 1 = 6

    // Black on rank 7 and 2
    val blackR7 = parse("4k3/4p3/8/8/8/8/8/4K3 w - - 0 1")
    assertEquals(PassedPawns.maxRank(blackR7, Color.Black), 1) // 8 - 7 = 1

    val blackR2 = parse("4k3/8/8/8/8/8/4p3/4K3 w - - 0 1")
    assertEquals(PassedPawns.maxRank(blackR2, Color.Black), 6) // 8 - 2 = 6

  test("multiple passed pawns return exact count and maximum rank"):
    // White has passed pawns on b3 (rank 3 -> 2) and e6 (rank 6 -> 5)
    val state = parse("4k3/8/4P3/8/8/1P6/8/4K3 w - - 0 1")
    assertEquals(PassedPawns.count(state, Color.White), 2)
    assertEquals(PassedPawns.maxRank(state, Color.White), 5)
    assertEquals(PassedPawns.countAndMaxRank(state, Color.White), (2, 5))

  test("doubled pawns on the same file without opposing blockers are both passed"):
    val state = parse("4k3/8/8/8/4P3/4P3/8/4K3 w - - 0 1") // White e3, e4
    assertEquals(PassedPawns.count(state, Color.White), 2)
    assertEquals(PassedPawns.maxRank(state, Color.White), 3)

  test("color-flip symmetry preserves passed pawn count and advancement"):
    val fens = List(
      "4k3/8/4P3/8/8/1P6/8/4K3 w - - 0 1",
      "4k3/8/8/2p5/4P3/8/8/4K3 w - - 0 1",
      "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
      "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"
    )
    for
      fen   <- fens
      color <- List(Color.White, Color.Black)
    do
      val state   = parse(fen)
      val flipped = Symmetry.colorFlip(state)
      assertEquals(
        PassedPawns.count(state, color),
        PassedPawns.count(flipped, color.opponent),
        s"Count symmetry failed for $fen, color: $color"
      )
      assertEquals(
        PassedPawns.maxRank(state, color),
        PassedPawns.maxRank(flipped, color.opponent),
        s"Max rank symmetry failed for $fen, color: $color"
      )

  test("passed pawn detection is invariant to dice pool and preserves state"):
    val state      = parse("4k3/8/4P3/8/8/1P6/8/4K3 w - - 0 1").withDicePool(List(1, 2))
    val serialized = FenParser.serialize(state)
    val hash       = state.zobristHash
    PassedPawns.count(state, Color.White)
    PassedPawns.maxRank(state, Color.White)
    assertEquals(FenParser.serialize(state), serialized)
    assertEquals(state.zobristHash, hash)
