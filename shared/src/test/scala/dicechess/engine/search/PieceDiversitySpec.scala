package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class PieceDiversitySpec extends FunSuite:

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  private val AsymmetricFen = "4k3/8/8/2bq4/2PNR3/8/8/4K3 w - - 0 1"

  test("the initial position has all five types for each side"):
    val state = parse(FenParser.InitialPosition)
    for color <- List(Color.White, Color.Black) do
      assertEquals(PieceDiversity.count(state, color), 5)
      assertEquals(PieceDiversity.difference(state, color), 0)

  test("kings alone contribute no diversity"):
    val state = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    for color <- List(Color.White, Color.Black) do
      assertEquals(PieceDiversity.count(state, color), 0)
      assertEquals(PieceDiversity.difference(state, color), 0)

  test("an empty board has no diversity"):
    val state = parse("8/8/8/8/8/8/8/8 w - - 0 1")
    for color <- List(Color.White, Color.Black) do
      assertEquals(PieceDiversity.count(state, color), 0)
      assertEquals(PieceDiversity.difference(state, color), 0)

  test("a synthetic fully occupied board still counts each type only once"):
    val state = parse("rnbqkbnr/pppppppp/nnnnnnnn/rrrrrrrr/RRRRRRRR/NNNNNNNN/PPPPPPPP/RNBQKBNR w - - 0 1")
    for color <- List(Color.White, Color.Black) do
      assertEquals(PieceDiversity.count(state, color), 5)
      assertEquals(PieceDiversity.difference(state, color), 0)

  test("all five types against a bare king reach both difference bounds"):
    val state = parse("7k/8/8/8/PNBRQ3/8/8/4K3 w - - 0 1")
    assertEquals(PieceDiversity.count(state, Color.White), 5)
    assertEquals(PieceDiversity.count(state, Color.Black), 0)
    assertEquals(PieceDiversity.difference(state, Color.White), 5)
    assertEquals(PieceDiversity.difference(state, Color.Black), -5)

  for piece <- List('P', 'N', 'B', 'R', 'Q') do
    test(s"a $piece contributes once regardless of duplicates, for either side"):
      for color <- List(Color.White, Color.Black) do
        val symbol    = if color.isWhite then piece else piece.toLower
        val single    = parse(s"4k3/8/8/8/3${symbol}4/8/8/4K3 w - - 0 1")
        val duplicate = parse(s"4k3/8/8/8/2${symbol}${symbol}4/8/8/4K3 w - - 0 1")

        assertEquals(PieceDiversity.count(single, color), 1)
        assertEquals(PieceDiversity.count(duplicate, color), 1)
        assertEquals(PieceDiversity.count(single, color.opponent), 0)
        assertEquals(PieceDiversity.count(duplicate, color.opponent), 0)

  test("capturing one of two knights preserves diversity until the last knight is captured"):
    val before   = parse("r3k3/8/8/8/NN6/8/8/4K3 b - - 0 1 rr")
    val oneLeft  = before.makeMove(Move(Square('a', 8), Square('a', 4), Move.Capture))
    val noneLeft = oneLeft.makeMove(Move(Square('a', 4), Square('b', 4), Move.Capture))

    assertEquals(PieceDiversity.count(before, Color.White), 1)
    assertEquals(PieceDiversity.count(oneLeft, Color.White), 1)
    assertEquals(PieceDiversity.count(noneLeft, Color.White), 0)
    assertEquals(PieceDiversity.count(noneLeft, Color.Black), 1)

  test("difference uses the requested perspective rather than the active color"):
    val state = parse(AsymmetricFen)
    for activeColor <- List(Color.White, Color.Black) do
      val position = state.withActiveColor(activeColor)
      assertEquals(PieceDiversity.count(position, Color.White), 3)
      assertEquals(PieceDiversity.count(position, Color.Black), 2)
      assertEquals(PieceDiversity.difference(position, Color.White), 1)
      assertEquals(PieceDiversity.difference(position, Color.Black), -1)

  test("color flip exchanges counts and reverses the difference from a fixed perspective"):
    val state   = parse(AsymmetricFen)
    val flipped = Symmetry.colorFlip(state)
    for color <- List(Color.White, Color.Black) do
      assertEquals(PieceDiversity.count(flipped, color), PieceDiversity.count(state, color.opponent))
      assertEquals(PieceDiversity.difference(flipped, color), -PieceDiversity.difference(state, color))

  test("piece relocation and the dice pool do not change diversity"):
    val state     = parse(AsymmetricFen)
    val relocated = parse("4k3/2b5/3q4/8/8/2P1R3/3N4/4K3 w - - 0 1")
    for
      position <- List(state, relocated)
      dice     <- List(Nil, List(1), List(2, 2, 2), List(4, 5, 6))
      color    <- List(Color.White, Color.Black)
    do
      val rolled = position.withDicePool(dice)
      assertEquals(PieceDiversity.count(rolled, color), PieceDiversity.count(state, color))
      assertEquals(PieceDiversity.difference(rolled, color), PieceDiversity.difference(state, color))

  test("a blocked pawn still contributes its type"):
    val blocked   = parse("4k3/8/8/8/8/N7/P7/4K3 w - - 0 1")
    val unblocked = parse("4k3/8/8/8/8/1N6/P7/4K3 w - - 0 1")
    assertEquals(PieceDiversity.count(blocked, Color.White), 2)
    assertEquals(PieceDiversity.count(unblocked, Color.White), 2)

  test("a knight on a king-rook ray contributes the same type when moved off the ray"):
    val pinned   = parse("k3r3/8/8/8/8/8/4N3/4K3 w - - 0 1")
    val unpinned = parse("k3r3/8/8/8/8/8/3N4/4K3 w - - 0 1")
    for
      state <- List(pinned, unpinned)
      color <- List(Color.White, Color.Black)
    do
      assertEquals(PieceDiversity.count(state, color), 1)
      assertEquals(PieceDiversity.difference(state, color), 0)

  for
    anotherPawn   <- List(false, true)
    existingQueen <- List(false, true)
  do
    test(s"promotion accounts for remaining pawns ($anotherPawn) and an existing queen ($existingQueen)"):
      val pawnRank       = if anotherPawn then "1P6" else "8"
      val backRank       = if existingQueen then "3QK3" else "4K3"
      val before         = parse(s"7k/P7/8/8/8/8/$pawnRank/$backRank w - - 0 1 p")
      val after          = before.makeMove(Move(Square('a', 7), Square('a', 8), Move.QueenPromotion))
      val expectedBefore = if existingQueen then 2 else 1
      val expectedAfter  = if anotherPawn then 2 else 1

      assertEquals(PieceDiversity.count(before, Color.White), expectedBefore)
      assertEquals(PieceDiversity.count(after, Color.White), expectedAfter)
      assertEquals(PieceDiversity.count(after, Color.Black), 0)

  test("capturing a king does not change either diversity count"):
    val before = parse("1R5k/8/8/8/8/8/8/K7 w - - 0 1 r")
    val after  = before.makeMove(Move(Square('b', 8), Square('h', 8), Move.Capture))
    for color <- List(Color.White, Color.Black) do
      assertEquals(PieceDiversity.count(after, color), PieceDiversity.count(before, color))
      assertEquals(PieceDiversity.difference(after, color), PieceDiversity.difference(before, color))

  test("extracting either perspective preserves the input position"):
    val state      = parse(AsymmetricFen).withDicePool(List(1, 2, 4))
    val before     = FenParser.serialize(state)
    val beforeHash = state.zobristHash
    for color <- List(Color.White, Color.Black) do
      PieceDiversity.count(state, color)
      PieceDiversity.difference(state, color)
    assertEquals(FenParser.serialize(state), before)
    assertEquals(state.zobristHash, beforeHash)
