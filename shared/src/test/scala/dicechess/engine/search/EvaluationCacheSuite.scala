// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Exact state identity, per-invocation ownership and telemetry regressions for #295, #443 and #456. */
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

  test("scalar-index collisions retain every distinct mailbox through equal-allocation hits") {
    val base   = position
    val states = base :: (0 until 32).map { index =>
      val mailbox = base.mailbox.toArray
      mailbox(index) = if mailbox(index) == Piece.Empty then Piece(Color.Black, PieceType.Knight) else Piece.Empty
      base.copy(mailbox = Mailbox.fromBuilder(mailbox))
    }.toList
    assertEquals(states.distinct.size, states.size)
    val cache = new EvaluationCache((s, c) => states.indexOf(s) * (if c.isWhite then 1 else -1))
    for state <- states; color <- List(Color.White, Color.Black) do
      assertEquals(cache(state, color), states.indexOf(state) * (if color.isWhite then 1 else -1))
    for state <- states.reverse ++ states; color <- List(Color.White, Color.Black) do
      val equal = state.copy(mailbox = Mailbox.fromBuilder(state.mailbox.toArray), zobristKey = 123L)
      assertEquals(cache(equal, color), states.indexOf(state) * (if color.isWhite then 1 else -1))
    assertEquals(cache.entryCount, states.size * 2)
    assertEquals(cache.evaluatorCalls, states.size.toLong * 2)
    assertEquals(cache.cacheHits, states.size.toLong * 4)
  }

  test("a failed evaluation preserves successful entries in its scalar collision chain") {
    val base    = position
    val mailbox = base.mailbox.toArray
    mailbox(20) = Piece(Color.Black, PieceType.Knight)
    val other   = base.copy(mailbox = Mailbox.fromBuilder(mailbox))
    val error   = new IllegalStateException("synthetic colliding evaluator failure")
    var failing = true
    val cache   = new EvaluationCache((state, _) =>
      if state == other && failing then scala.util.Failure[Int](error).get
      else if state == other then 78
      else 12
    )
    assertEquals(cache(base, Color.White), 12)
    for _ <- 1 to 2 do
      assert(intercept[IllegalStateException](cache(other, Color.White)) eq error)
      assertEquals(cache.entryCount, 1)
    assertEquals(cache(base, Color.White), 12)
    failing = false
    assertEquals(cache(other, Color.White), 78)
    assertEquals(cache(other, Color.White), 78)
    assertEquals(cache(base, Color.White), 12)
    assertEquals(cache.entryCount, 2)
    assertEquals(cache.evaluatorCalls, 4L)
    assertEquals(cache.cacheHits, 3L)
  }

  test("nested evaluations retain colliding entries through growth without duplicating a key") {
    val base    = position
    val mailbox = base.mailbox.toArray
    mailbox(20) = Piece(Color.Black, PieceType.Knight)
    val other                          = base.copy(mailbox = Mailbox.fromBuilder(mailbox))
    var cache: Option[EvaluationCache] = None
    var nested                         = false
    val invocation                     = new EvaluationCache((state, color) =>
      if !nested then
        nested = true
        for counter <- 2 to 65 do assertEquals(cache.get(base.copy(fullMoveNumber = counter), color), 12)
        assertEquals(cache.get(other, color), 78)
        assertEquals(cache.get(base, color), 12)
      if state == other then 78 else 12
    )
    cache = Some(invocation)
    assertEquals(invocation(base, Color.White), 12)
    assertEquals(invocation(base, Color.White), 12)
    assertEquals(invocation(other, Color.White), 78)
    for counter <- 2 to 65 do assertEquals(invocation(base.copy(fullMoveNumber = counter), Color.White), 12)
    assertEquals(invocation.entryCount, 66)
    assertEquals(invocation.evaluatorCalls, 67L)
    assertEquals(invocation.cacheHits, 66L)
  }

  test("growing the invocation index preserves all unique states and perspectives") {
    val base  = position
    val cache = new EvaluationCache((state, color) => state.fullMoveNumber * (if color.isWhite then 1 else -1))
    for counter <- 1 to 512; color <- List(Color.White, Color.Black) do
      assertEquals(cache(base.copy(fullMoveNumber = counter), color), counter * (if color.isWhite then 1 else -1))
    for counter <- (1 to 512).reverse; color <- List(Color.Black, Color.White) do
      assertEquals(cache(base.copy(fullMoveNumber = counter), color), counter * (if color.isWhite then 1 else -1))
    assertEquals(cache.entryCount, 1024)
    assertEquals(cache.evaluatorCalls, 1024L)
    assertEquals(cache.cacheHits, 1024L)
  }

  test("colliding per-field fingerprints preserve exact states across growth") {
    val base = position
    // Multiplication by the field constant yields 0x0000000100000001, whose folded halves cancel.
    // Unlike a derived-key collision, this exercises an actual collision in the cache's scalar index.
    val collision = base.copy(enPassant = Bitboard(0x8b15f71e9937733dL))
    val cache     = new EvaluationCache((s, c) =>
      s.fullMoveNumber * (if c.isWhite then 1 else -1) + (if s.enPassant == Bitboard.empty then 0 else 1000)
    )
    for counter <- 1 to 128; s <- List(base, collision); c <- List(Color.White, Color.Black) do
      val state = s.copy(fullMoveNumber = counter)
      assertEquals(
        cache(state, c),
        counter * (if c.isWhite then 1 else -1) +
          (if s.enPassant == Bitboard.empty then 0 else 1000)
      )
    for counter <- (1 to 128).reverse; s <- List(collision, base); c <- List(Color.Black, Color.White) do
      val state = s.copy(fullMoveNumber = counter, mailbox = Mailbox.fromBuilder(s.mailbox.toArray))
      assertEquals(
        cache(state, c),
        counter * (if c.isWhite then 1 else -1) +
          (if s.enPassant == Bitboard.empty then 0 else 1000)
      )
    assertEquals(cache.entryCount, 512)
    assertEquals(cache.evaluatorCalls, 512L)
    assertEquals(cache.cacheHits, 512L)
  }

  test("same-key successful re-entry retains the outer callback's final value") {
    val base                           = position
    var active                         = false
    var cache: Option[EvaluationCache] = None
    val invocation                     = new EvaluationCache((s, c) =>
      if active then 3
      else
        active = true
        assertEquals(cache.get(s, c), 3)
        7
    )
    cache = Some(invocation)
    assertEquals(invocation(base, Color.White), 7)
    assertEquals(invocation(base, Color.White), 7)
    assertEquals(invocation.entryCount, 1)
    assertEquals(invocation.evaluatorCalls, 2L)
    assertEquals(invocation.cacheHits, 1L)
  }

  test("an outer failure preserves successful nested insertions and retries after growth") {
    val base                           = position
    val error                          = new IllegalStateException("outer failure after nested growth")
    var active                         = false
    var cache: Option[EvaluationCache] = None
    val invocation                     = new EvaluationCache((s, c) =>
      if !active then
        active = true
        for counter <- 2 to 128 do assertEquals(cache.get(base.copy(fullMoveNumber = counter), c), counter)
        scala.util.Failure[Int](error).get
      else s.fullMoveNumber
    )
    cache = Some(invocation)
    assert(intercept[IllegalStateException](invocation(base, Color.White)) eq error)
    assertEquals(invocation.entryCount, 127)
    assertEquals(invocation(base, Color.White), 1)
    for counter <- 2 to 128 do assertEquals(invocation(base.copy(fullMoveNumber = counter), Color.White), counter)
    assertEquals(invocation.entryCount, 128)
    assertEquals(invocation.evaluatorCalls, 129L)
    assertEquals(invocation.cacheHits, 127L)
  }

  // ===========================================================================
  // Consumer-declared valuation keys (#459)
  // ===========================================================================

  final case class TestBoardValuationKey(
      white: Long,
      black: Long,
      active: Color,
      perspective: Color
  ) derives CanEqual

  final case class CollidingKey(id: Int) {
    override def hashCode(): Int           = 42
    override def equals(obj: Any): Boolean = obj match
      case that: CollidingKey => this.id == that.id
      case _                  => false
  }

  test("consumer-declared valuation key merges counter variants when callback is invariant") {
    val base = position
    val c1   = base.copy(fullMoveNumber = 42, flags = base.flags.withHalfMoveClock(15))
    val c2   = base.copy(fullMoveNumber = 43, flags = base.flags.withHalfMoveClock(20))
    val c3   = base.copy(fullMoveNumber = 44, flags = base.flags.withHalfMoveClock(25))

    var receivedStates                  = List.empty[GameState]
    val eval: (GameState, Color) => Int = (s, _) =>
      receivedStates = s :: receivedStates
      42

    val cache = EvaluationCache.withValuationKey(eval)((s, c) =>
      TestBoardValuationKey(s.whitePieces.value, s.blackPieces.value, s.activeColor, c)
    )

    assertEquals(cache(c1, Color.White), 42)
    assertEquals(cache(c2, Color.White), 42)
    assertEquals(cache(c3, Color.White), 42)
    assertEquals(cache.evaluatorCalls, 1L)
    assertEquals(cache.cacheHits, 2L)
    assertEquals(cache.entryCount, 1)

    // The callback must receive the ORIGINAL un-normalized state on miss with non-zero counters intact
    assertEquals(receivedStates.length, 1)
    assertEquals(receivedStates.head.fullMoveNumber, 42)
    assertEquals(receivedStates.head.halfMoveClock, 15)
  }

  test("separately allocated equal custom keys share an entry") {
    val base = position
    val k1   = TestBoardValuationKey(base.whitePieces.value, base.blackPieces.value, base.activeColor, Color.White)
    val k2   = TestBoardValuationKey(base.whitePieces.value, base.blackPieces.value, base.activeColor, Color.White)
    assert(!(k1.asInstanceOf[AnyRef] eq k2.asInstanceOf[AnyRef]))
    assertEquals(k1, k2)

    val cache = new EvaluationCache(
      (_, _) => 99,
      (_, c) => TestBoardValuationKey(base.whitePieces.value, base.blackPieces.value, base.activeColor, c)
    )
    val s1 = base.copy(fullMoveNumber = 1)
    val s2 = base.copy(fullMoveNumber = 2)
    assertEquals(cache(s1, Color.White), 99)
    assertEquals(cache(s2, Color.White), 99)
    assertEquals(cache.evaluatorCalls, 1L)
    assertEquals(cache.cacheHits, 1L)
    assertEquals(cache.entryCount, 1)
  }

  test("deliberate hash collisions with unequal keys are resolved by equality") {
    val base  = position
    val s1    = base.copy(fullMoveNumber = 1)
    val s2    = base.copy(fullMoveNumber = 2)
    val cache = new EvaluationCache((s, _) => s.fullMoveNumber * 10, (s, _) => CollidingKey(s.fullMoveNumber))

    assertEquals(cache(s1, Color.White), 10)
    assertEquals(cache(s2, Color.White), 20)
    assertEquals(cache(s1, Color.White), 10)
    assertEquals(cache(s2, Color.White), 20)
    assertEquals(cache.evaluatorCalls, 2L)
    assertEquals(cache.cacheHits, 2L)
    assertEquals(cache.entryCount, 2)
  }

  test("different perspectives remain distinct under custom keys") {
    val base  = position
    val cache = EvaluationCache.withValuationKey((_, c) => if c.isWhite then 100 else -100)((s, c) =>
      TestBoardValuationKey(s.whitePieces.value, s.blackPieces.value, s.activeColor, c)
    )

    assertEquals(cache(base, Color.White), 100)
    assertEquals(cache(base, Color.Black), -100)
    assertEquals(cache(base, Color.White), 100)
    assertEquals(cache(base, Color.Black), -100)
    assertEquals(cache.evaluatorCalls, 2L)
    assertEquals(cache.cacheHits, 2L)
    assertEquals(cache.entryCount, 2)
  }

  test("key construction failure propagates without counting calls or creating entries") {
    val base  = position
    val error = new IllegalArgumentException("key construction failure")
    var fail  = true
    val cache = new EvaluationCache(
      (_, _) => 77,
      (s, c) =>
        if fail then scala.util.Failure(error).get
        else TestBoardValuationKey(s.whitePieces.value, s.blackPieces.value, s.activeColor, c)
    )

    val caught = intercept[IllegalArgumentException](cache(base, Color.White))
    assert(caught eq error)
    assertEquals(cache.evaluatorCalls, 0L)
    assertEquals(cache.cacheHits, 0L)
    assertEquals(cache.entryCount, 0)

    fail = false
    assertEquals(cache(base, Color.White), 77)
    assertEquals(cache(base, Color.White), 77)
    assertEquals(cache.evaluatorCalls, 1L)
    assertEquals(cache.cacheHits, 1L)
    assertEquals(cache.entryCount, 1)
  }

  test("evaluator failure under custom key counts attempts, retries and leaves no entry") {
    val base     = position
    val error    = new IllegalStateException("synthetic evaluator failure under key")
    var attempts = 0
    val cache    = EvaluationCache.withValuationKey { (_, _) =>
      attempts += 1
      if attempts <= 2 then scala.util.Failure[Int](error).get
      else 123
    }((s, c) => TestBoardValuationKey(s.whitePieces.value, s.blackPieces.value, s.activeColor, c))

    intercept[IllegalStateException](cache(base, Color.White))
    intercept[IllegalStateException](cache(base, Color.White))
    assertEquals(cache.evaluatorCalls, 2L)
    assertEquals(cache.cacheHits, 0L)
    assertEquals(cache.entryCount, 0)

    assertEquals(cache(base, Color.White), 123)
    assertEquals(cache(base, Color.White), 123)
    assertEquals(cache.evaluatorCalls, 3L)
    assertEquals(cache.cacheHits, 1L)
    assertEquals(cache.entryCount, 1)
  }

  test("same-key re-entry with custom key updates without stale slot corruption") {
    val base                           = position
    var active                         = false
    var cache: Option[EvaluationCache] = None
    val invocation                     = EvaluationCache.withValuationKey((s, c) =>
      if active then 111
      else
        active = true
        assertEquals(cache.get(s, c), 111)
        222
    )((s, c) => TestBoardValuationKey(s.whitePieces.value, s.blackPieces.value, s.activeColor, c))
    cache = Some(invocation)

    assertEquals(invocation(base, Color.White), 222)
    assertEquals(invocation(base, Color.White), 222)
    assertEquals(invocation.entryCount, 1)
    assertEquals(invocation.evaluatorCalls, 2L)
    assertEquals(invocation.cacheHits, 1L)
  }

  test("nested re-entry on distinct custom key with table growth resolves outer slot correctly") {
    val base                           = position
    var active                         = false
    var cache: Option[EvaluationCache] = None
    val invocation                     = new EvaluationCache(
      (s, c) =>
        if !active then
          active = true
          for i <- 2 to 128 do
            val other = base.copy(fullMoveNumber = i)
            assertEquals(cache.get(other, c), i)
          999
        else s.fullMoveNumber,
      (s, _) => s.fullMoveNumber
    )
    cache = Some(invocation)

    assertEquals(invocation(base.copy(fullMoveNumber = 1), Color.White), 999)
    assertEquals(invocation(base.copy(fullMoveNumber = 1), Color.White), 999)
    assertEquals(invocation.entryCount, 128)
    assertEquals(invocation.evaluatorCalls, 128L)
    assertEquals(invocation.cacheHits, 1L)
  }

  test("extreme integer values are cached correctly under custom key") {
    List(0, -1, Int.MinValue, Int.MaxValue).foreach { value =>
      val base  = position
      val cache = EvaluationCache.withValuationKey((_, _) => value)((s, c) =>
        TestBoardValuationKey(s.whitePieces.value, s.blackPieces.value, s.activeColor, c)
      )
      assertEquals(cache(base, Color.White), value)
      assertEquals(cache(base, Color.White), value)
      assertEquals(cache.evaluatorCalls, 1L)
      assertEquals(cache.cacheHits, 1L)
      assertEquals(cache.entryCount, 1)
    }
  }

  test("negative control: counter-sensitive evaluator with default exact cache distinguishes counter variants") {
    val base = position
    val s1   = base.copy(fullMoveNumber = 1)
    val s2   = base.copy(fullMoveNumber = 2)

    val sensitiveEval: (GameState, Color) => Int = (s, _) => s.fullMoveNumber
    val exactCache                               = EvaluationCache.exact(sensitiveEval)

    assertEquals(exactCache(s1, Color.White), 1)
    assertEquals(exactCache(s2, Color.White), 2)
    assertEquals(exactCache.evaluatorCalls, 2L)
    assertEquals(exactCache.cacheHits, 0L)
    assertEquals(exactCache.entryCount, 2)
  }

  test("numeric keys of different boxed types merge into one entry under Scala value equality") {
    val base  = position
    val s1    = base.copy(fullMoveNumber = 1)
    val s2    = base.copy(fullMoveNumber = 2)
    val cache = new EvaluationCache(
      (_, _) => 777,
      (s, _) => if s.fullMoveNumber == 1 then 1: Int else 1L: Long
    )
    assertEquals(cache(s1, Color.White), 777)
    assertEquals(cache(s2, Color.White), 777)
    assertEquals(cache.evaluatorCalls, 1L)
    assertEquals(cache.cacheHits, 1L)
    assertEquals(cache.entryCount, 1)
  }

  test("null keys and non-null collision are handled safely without NPE") {
    val base   = position
    val sNull1 = base.copy(fullMoveNumber = 1)
    val sNull2 = base.copy(fullMoveNumber = 2)
    val sZero  = base.copy(fullMoveNumber = 3)

    val cache = new EvaluationCache(
      (s, _) => s.fullMoveNumber * 10,
      (s, _) => if s.fullMoveNumber <= 2 then null: Any else 0: Int // scalafix:ok(DisableSyntax.null)
    )
    // First null key creates entry
    assertEquals(cache(sNull1, Color.White), 10)
    // Second null key hits cache
    assertEquals(cache(sNull2, Color.White), 10)
    assertEquals(cache.evaluatorCalls, 1L)
    assertEquals(cache.cacheHits, 1L)
    assertEquals(cache.entryCount, 1)

    // Non-null key with hashCode 0 collides in slot 0 with null key
    assertEquals(cache(sZero, Color.White), 30)
    assertEquals(cache.evaluatorCalls, 2L)
    assertEquals(cache.cacheHits, 1L)
    assertEquals(cache.entryCount, 2)

    // Verify subsequent lookups for both null and zero hit their respective entries
    assertEquals(cache(sNull1, Color.White), 10)
    assertEquals(cache(sZero, Color.White), 30)
    assertEquals(cache.evaluatorCalls, 2L)
    assertEquals(cache.cacheHits, 3L)
    assertEquals(cache.entryCount, 2)
  }

  final class ThrowingKey(val id: Int, val throwOnHash: Boolean = false, val throwOnEquals: Boolean = false):
    override def hashCode(): Int =
      if throwOnHash then scala.util.Failure(new ArithmeticException("synthetic hashCode exception")).get
      else 42 // Deliberately collide

    override def equals(obj: Any): Boolean =
      if throwOnEquals then scala.util.Failure(new IllegalArgumentException("synthetic equals exception")).get
      else
        obj match
          case that: ThrowingKey =>
            if that.throwOnEquals then
              scala.util.Failure(new IllegalArgumentException("synthetic equals exception")).get
            this.id == that.id
          case _ => false

  test("exceptions in keyFn, hashCode and equals preserve telemetry and create no stale entries") {
    val base = position

    // 1. Exception in keyFn
    var keyFnCalls    = 0
    val keyFnErrCache = new EvaluationCache(
      (_, _) => 100,
      (_, _) =>
        keyFnCalls += 1
        if keyFnCalls == 1 then scala.util.Failure(new UnsupportedOperationException("keyFn failure")).get
        else "ok"
    )
    intercept[UnsupportedOperationException](keyFnErrCache(base, Color.White))
    assertEquals(keyFnErrCache.evaluatorCalls, 0L)
    assertEquals(keyFnErrCache.cacheHits, 0L)
    assertEquals(keyFnErrCache.entryCount, 0)
    // Retry succeeds
    assertEquals(keyFnErrCache(base, Color.White), 100)
    assertEquals(keyFnErrCache.evaluatorCalls, 1L)
    assertEquals(keyFnErrCache.entryCount, 1)

    // 2. Exception in hashCode
    var hashAttempts = 0
    val hashErrCache = new EvaluationCache(
      (_, _) => 200,
      (_, _) =>
        hashAttempts += 1
        ThrowingKey(hashAttempts, throwOnHash = hashAttempts == 1)
    )
    intercept[ArithmeticException](hashErrCache(base, Color.White))
    assertEquals(hashErrCache.evaluatorCalls, 0L)
    assertEquals(hashErrCache.cacheHits, 0L)
    assertEquals(hashErrCache.entryCount, 0)
    assertEquals(hashErrCache(base, Color.White), 200)
    assertEquals(hashErrCache.evaluatorCalls, 1L)
    assertEquals(hashErrCache.entryCount, 1)

    // 3. Exception in equals during hash collision probe
    val s1             = base.copy(fullMoveNumber = 1)
    val s2             = base.copy(fullMoveNumber = 2)
    val equalsErrCache = new EvaluationCache(
      (s, _) => s.fullMoveNumber * 100,
      (s, _) => ThrowingKey(s.fullMoveNumber, throwOnEquals = s.fullMoveNumber == 2)
    )
    // First entry succeeds
    assertEquals(equalsErrCache(s1, Color.White), 100)
    assertEquals(equalsErrCache.evaluatorCalls, 1L)
    assertEquals(equalsErrCache.entryCount, 1)
    // Second entry collides with slot of s1, calling equals which throws
    intercept[IllegalArgumentException](equalsErrCache(s2, Color.White))
    assertEquals(equalsErrCache.evaluatorCalls, 1L)
    assertEquals(equalsErrCache.cacheHits, 0L)
    assertEquals(equalsErrCache.entryCount, 1)
    // First entry is still valid and hits
    assertEquals(equalsErrCache(s1, Color.White), 100)
    assertEquals(equalsErrCache.evaluatorCalls, 1L)
    assertEquals(equalsErrCache.cacheHits, 1L)
    assertEquals(equalsErrCache.entryCount, 1)
  }

  test("re-entry with equivalent custom key from different original state resolves correctly") {
    val base = position
    val s1   = base.copy(fullMoveNumber = 1)
    val s2   = base.copy(fullMoveNumber = 2)

    var cache: Option[EvaluationCache] = None
    var firstCall                      = true
    val invocation                     = EvaluationCache.withValuationKey((_, c) =>
      if firstCall then
        firstCall = false
        // Re-entrant call with s2, which has different state but equivalent valuation key
        assertEquals(cache.get(s2, c), 888)
        999
      else 888
    )((s, c) => TestBoardValuationKey(s.whitePieces.value, s.blackPieces.value, s.activeColor, c))
    cache = Some(invocation)

    assertEquals(invocation(s1, Color.White), 999)
    assertEquals(invocation(s2, Color.White), 999)
    assertEquals(invocation.evaluatorCalls, 2L)
    assertEquals(invocation.cacheHits, 1L)
    assertEquals(invocation.entryCount, 1)
  }

  test("independent custom-key cache instances own their data and telemetry separately") {
    val base = position
    val s1   = base.copy(fullMoveNumber = 1)
    val s2   = base.copy(fullMoveNumber = 2)

    val c1 = EvaluationCache.withValuationKey((s, _) => s.fullMoveNumber + 10)((s, c) =>
      TestBoardValuationKey(s.whitePieces.value, s.blackPieces.value, s.activeColor, c)
    )
    val c2 = EvaluationCache.withValuationKey((s, _) => s.fullMoveNumber + 20)((s, c) =>
      TestBoardValuationKey(s.whitePieces.value, s.blackPieces.value, s.activeColor, c)
    )

    assertEquals(c1(s1, Color.White), 11)
    assertEquals(c1(s2, Color.White), 11)
    assertEquals(c1.evaluatorCalls, 1L)
    assertEquals(c1.cacheHits, 1L)

    assertEquals(c2.entryCount, 0)
    assertEquals(c2(s1, Color.White), 21)
    assertEquals(c2(s2, Color.White), 21)
    assertEquals(c2.evaluatorCalls, 1L)
    assertEquals(c2.cacheHits, 1L)
  }
