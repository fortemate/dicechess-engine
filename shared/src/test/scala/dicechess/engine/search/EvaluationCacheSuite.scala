// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Exact state identity, per-invocation ownership and telemetry regressions for #295. */
class EvaluationCacheSuite extends FunSuite:
  private def position: GameState =
    FenParser.parse("r3k2r/8/8/8/8/8/P7/R3K2R w KQkq - 0 1").fold(fail(_), identity)

  test("successful values including zero and integer boundaries are cached exactly") {
    List(0, -17, Int.MinValue, Int.MaxValue).foreach { value =>
      var actualCalls = 0
      val cache       = new EvaluationCache((_, _) => { actualCalls += 1; value })
      val state       = position
      assertEquals(cache.evaluatorCalls, 0L)
      assertEquals(cache.cacheHits, 0L)
      assertEquals(cache.entryCount, 0)
      assertEquals(cache(state, Color.White), value)
      assertEquals(cache(state, Color.White), value)
      assertEquals(cache(state, Color.White), value)
      assertEquals(actualCalls, 1)
      assertEquals(cache.evaluatorCalls, 1L)
      assertEquals(cache.cacheHits, 2L)
      assertEquals(cache.entryCount, 1)
    }
  }

  test("separately allocated equal mailboxes share an entry") {
    val state = position
    val equal = state.copy(mailbox = Mailbox.fromBuilder(state.mailbox.toArray))
    assert(!(state.mailbox.asInstanceOf[AnyRef] eq equal.mailbox.asInstanceOf[AnyRef]))
    assertEquals(state, equal)
    val cache = new EvaluationCache(Evaluator.evaluateMaterial)
    assertEquals(cache(state, Color.White), cache(equal, Color.White))
    assertEquals(cache.evaluatorCalls, 1L)
    assertEquals(cache.cacheHits, 1L)
    assertEquals(cache.entryCount, 1)
  }

  test("the evaluation perspective is part of the key") {
    val state = position
    val cache = new EvaluationCache((_, color) => if color.isWhite then 7 else -7)
    assertEquals(cache(state, Color.White), 7)
    assertEquals(cache(state, Color.Black), -7)
    assertEquals(cache(state, Color.White), 7)
    assertEquals(cache(state, Color.Black), -7)
    assertEquals(cache.evaluatorCalls, 2L)
    assertEquals(cache.cacheHits, 2L)
    assertEquals(cache.entryCount, 2)
  }

  test("every GameState equality dimension distinguishes entries") {
    val state          = position
    val bit            = Bitboard.fromSquare(Square('d', 4))
    val changedMailbox = state.mailbox.toArray
    changedMailbox(Square('d', 4).index) = Piece(Color.White, PieceType.Knight)
    // Independent synthetic field changes pin the equality contract, rather than move legality.
    val variants = List(
      state.copy(whitePieces = state.whitePieces ^ bit, zobristKey = 0L),
      state.copy(blackPieces = state.blackPieces ^ bit, zobristKey = 0L),
      state.copy(pawns = state.pawns ^ bit, zobristKey = 0L),
      state.copy(knights = state.knights ^ bit, zobristKey = 0L),
      state.copy(bishops = state.bishops ^ bit, zobristKey = 0L),
      state.copy(rooks = state.rooks ^ bit, zobristKey = 0L),
      state.copy(queens = state.queens ^ bit, zobristKey = 0L),
      state.copy(kings = state.kings ^ bit, zobristKey = 0L),
      state.copy(mailbox = Mailbox.fromBuilder(changedMailbox), zobristKey = 0L),
      state.withActiveColor(Color.Black),
      state.withDicePool(List(1)),
      state.withDicePool(List(1, 2)),
      state.withDicePool(List(2, 1)),
      state.copy(flags = state.flags.withCastlingRights(0), zobristKey = 0L),
      state.copy(flags = state.flags.withEnPassantFiles(8), zobristKey = 0L),
      state.copy(flags = state.flags.withHalfMoveClock(1), zobristKey = 0L),
      state.copy(enPassant = Bitboard.fromSquare(Square('d', 3)), zobristKey = 0L),
      state.copy(fullMoveNumber = 2)
    )
    val states = state :: variants
    assertEquals(states.distinct.size, states.size)
    val cache = new EvaluationCache((s, _) => states.indexOf(s))
    states.zipWithIndex.foreach((s, index) => assertEquals(cache(s, Color.White), index))
    states.zipWithIndex.foreach((s, index) => assertEquals(cache(s, Color.White), index))
    assertEquals(cache.evaluatorCalls, states.size.toLong)
    assertEquals(cache.cacheHits, states.size.toLong)
    assertEquals(cache.entryCount, states.size)
  }

  test("full en-passant targets distinguish states even when packed file flags match") {
    val state = position
    val flags = state.flags.withEnPassantFiles(8)
    val rank3 = state.copy(flags = flags, enPassant = Bitboard.fromSquare(Square('d', 3)), zobristKey = 0L)
    val rank6 = state.copy(flags = flags, enPassant = Bitboard.fromSquare(Square('d', 6)), zobristKey = 0L)
    assertEquals(rank3.flags, rank6.flags)
    val cache = new EvaluationCache((s, _) => if s.enPassant == rank3.enPassant then 3 else 6)
    assertEquals(cache(rank3, Color.White), 3)
    assertEquals(cache(rank6, Color.White), 6)
    assertEquals(cache.entryCount, 2)
  }

  test("the derived Zobrist field neither splits equal states nor merges unequal ones") {
    val state               = position
    val differentDerivedKey = state.copy(zobristKey = state.zobristHash ^ 1L)
    assertEquals(state, differentDerivedKey)
    val counterVariant = state.copy(fullMoveNumber = state.fullMoveNumber + 1, zobristKey = state.zobristHash)
    assertNotEquals(state, counterVariant)
    assertEquals(state.zobristHash, counterVariant.zobristHash)
    val cache = new EvaluationCache((s, _) => s.fullMoveNumber)
    assertEquals(cache(state, Color.White), 1)
    assertEquals(cache(differentDerivedKey, Color.White), 1)
    assertEquals(cache(counterVariant, Color.White), 2)
    assertEquals(cache.evaluatorCalls, 2L)
    assertEquals(cache.cacheHits, 1L)
    assertEquals(cache.entryCount, 2)
  }

  test("real GameState and tuple hash collisions are resolved by equality") {
    val first = position
    // A Long whose high and low halves are equal hashes to zero, just like the empty bitboard.
    // The synthetic targets intentionally test hashing alone, independently of move legality.
    val second = first.copy(enPassant = Bitboard(0x0000000100000001L), zobristKey = 0L)
    assertNotEquals(first, second)
    assertEquals(first.hashCode, second.hashCode)
    assertEquals((first, Color.White).hashCode, (second, Color.White).hashCode)
    val cache = new EvaluationCache((s, _) => if s.enPassant == Bitboard.empty then 11 else 22)
    assertEquals(cache(first, Color.White), 11)
    assertEquals(cache(second, Color.White), 22)
    assertEquals(cache(first, Color.White), 11)
    assertEquals(cache(second, Color.White), 22)
    assertEquals(cache.evaluatorCalls, 2L)
    assertEquals(cache.cacheHits, 2L)
    assertEquals(cache.entryCount, 2)
  }

  test("instances own their evaluator configuration and telemetry independently") {
    val state                                              = position
    def configured(offset: Int): (GameState, Color) => Int = (s, _) => s.fullMoveNumber + offset
    val first                                              = new EvaluationCache(configured(10))
    val second                                             = new EvaluationCache(configured(20))
    val anotherInvocation                                  = new EvaluationCache(configured(10))
    assertEquals(first(state, Color.White), 11)
    assertEquals(first(state, Color.White), 11)
    assertEquals(second(state, Color.White), 21)
    assertEquals(anotherInvocation.entryCount, 0)
    assertEquals(anotherInvocation(state, Color.White), 11)
    assertEquals(first.evaluatorCalls, 1L)
    assertEquals(first.cacheHits, 1L)
    assertEquals(second.evaluatorCalls, 1L)
    assertEquals(second.cacheHits, 0L)
    assertEquals(anotherInvocation.evaluatorCalls, 1L)
    assertEquals(anotherInvocation.cacheHits, 0L)
  }

  test("failed calls propagate the same exception, count attempts and leave no entry") {
    val state    = position
    val error    = new IllegalStateException("synthetic evaluator failure")
    var attempts = 0
    val cache    = new EvaluationCache((s, _) => {
      attempts += 1
      if s.fullMoveNumber == 1 && attempts <= 3 then scala.util.Failure[Int](error).get
      else s.fullMoveNumber
    })
    // A successful entry survives failures on another key.
    assertEquals(cache(state.copy(fullMoveNumber = 2), Color.White), 2)
    (1 to 2).foreach { _ =>
      val caught = intercept[IllegalStateException](cache(state, Color.White))
      assert(caught eq error)
      assertEquals(cache.entryCount, 1)
      assertEquals(cache.cacheHits, 0L)
    }
    assertEquals(cache.evaluatorCalls, 3L)
    assertEquals(cache(state, Color.White), 1)
    assertEquals(cache(state, Color.White), 1)
    assertEquals(cache(state.copy(fullMoveNumber = 2), Color.White), 2)
    assertEquals(attempts, 4)
    assertEquals(cache.evaluatorCalls, 4L)
    assertEquals(cache.cacheHits, 2L)
    assertEquals(cache.entryCount, 2)
  }
