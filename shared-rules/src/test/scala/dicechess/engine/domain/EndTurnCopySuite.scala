// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

import dicechess.engine.search.TurnGenerator
import munit.FunSuite

/** Single-copy endTurn regressions for #445. The oracle filters targets by Square.rank and recomputes the hash. */
class EndTurnCopySuite extends FunSuite:
  private val base       = FenParser.parse("r3k2r/8/8/3pP3/3Pp3/8/8/R3K2R w KQkq - 37 12").toOption.get
  private val targetSets = List(
    Nil,
    List(Square('d', 3)),
    List(Square('d', 6)),
    ('a' to 'h').map(Square(_, 3)).toList,
    ('a' to 'h').map(Square(_, 6)).toList,
    List(Square('a', 3), Square('h', 3), Square('d', 6), Square('g', 6)),
    (for file <- 'a' to 'h'; rank <- List(3, 6) yield Square(file, rank)).toList,
    List(Square('a', 1), Square('h', 8), Square('b', 3), Square('c', 6))
  ).map(_.foldLeft(Bitboard.empty)(_.add(_)))

  private def withoutRank(targets: Bitboard, rank: Int): Bitboard =
    (0 until 64).foldLeft(Bitboard.empty) { (kept, index) =>
      val square = Square.fromIndex(index)
      if targets.contains(square) && square.rank != rank then kept.add(square) else kept
    }

  private def reference(state: GameState): GameState =
    state.copy(
      flags = state.flags.withActiveColor(state.activeColor.opponent).withDicePool(Nil),
      enPassant = withoutRank(state.enPassant, if state.activeColor.isWhite then 6 else 3),
      fullMoveNumber = state.fullMoveNumber + (if state.activeColor.isBlack then 1 else 0),
      zobristKey = 0L
    )

  test("one-copy finalization matches independent fields and hash across both colors and complete target bitboards"):
    for
      color     <- List(Color.White, Color.Black)
      dice      <- List(Nil, List(1), List(6, 4, 1))
      targets   <- targetSets
      populated <- List(false, true)
      packedEp  <- List(0, 0xa5)
      counter   <- List(1, 12, Int.MaxValue)
    do
      val raw = base.copy(
        flags = base.flags.withActiveColor(color).withDicePool(dice).withEnPassantFiles(packedEp),
        enPassant = targets,
        fullMoveNumber = counter,
        zobristKey = 0L
      )
      val state    = if populated then raw.copy(zobristKey = Zobrist.computeKey(raw)) else raw
      val before   = state.copy(mailbox = Mailbox.fromBuilder(state.mailbox.toArray))
      val expected = reference(state)
      val actual   = state.endTurn()
      assertEquals(actual, expected)
      assertEquals(actual.zobristKey, Zobrist.computeKey(expected))
      assertEquals(actual.zobristHash, Zobrist.computeKey(actual))
      assertEquals(state, before, "the input and its mailbox must remain immutable")
      assertEquals(state.zobristKey, before.zobristKey)
      assert(actual.mailbox.asInstanceOf[AnyRef] eq state.mailbox.asInstanceOf[AnyRef])

  test("clearEnPassant remains independently callable without changing flags, dice, color or counters"):
    for color <- List(Color.White, Color.Black); targets <- targetSets do
      val state = base.copy(
        flags = base.flags.withDicePool(List(6, 4, 1)).withEnPassantFiles(0xa5),
        enPassant = targets,
        zobristKey = 0L
      )
      val expected = state.copy(enPassant = withoutRank(targets, if color.isWhite then 3 else 6))
      val actual   = state.clearEnPassant(color)
      assertEquals(actual, expected)
      assertEquals(actual.zobristHash, Zobrist.computeKey(expected))

  test("two successive finalizations preserve packed flags and clear only the two expired target ranks"):
    for color <- List(Color.White, Color.Black); targets <- targetSets do
      val state = base.copy(
        flags = base.flags.withActiveColor(color).withDicePool(List(1, 6)),
        enPassant = targets,
        zobristKey = 0L
      )
      val actual   = state.endTurn().endTurn()
      val expected = reference(reference(state))
      assertEquals(actual, expected)
      assertEquals(actual.activeColor, color)
      assertEquals(actual.fullMoveNumber, state.fullMoveNumber + 1)
      assertEquals(actual.zobristHash, Zobrist.computeKey(expected))

  test("normal visitor results finalize once while king captures preserve the unfinalized state"):
    val fixtures = List(
      "8/8/8/8/8/4k3/4R3/4K3 w - - 7 12",
      "4k3/4r3/4K3/8/8/8/8/8 b - - 7 12"
    )
    for fen <- fixtures do
      val state    = FenParser.parse(fen).toOption.get.withDicePool(List(4, 4, 4))
      var normal   = 0
      var terminal = 0
      TurnGenerator.forEachLegalTurnState(state) { (moves, length, actual, capture) =>
        val raw      = moves.take(length).foldLeft(state)((position, move) => position.makeMove(move))
        val expected = if capture then raw else reference(raw)
        assertEquals(actual, expected)
        assertEquals(actual.zobristHash, Zobrist.computeKey(expected))
        if capture then terminal += 1 else normal += 1
        true
      }
      assert(normal > 0 && terminal > 0)
