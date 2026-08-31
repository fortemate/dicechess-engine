package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.util.Random

class OpeningBookBotSpec extends FunSuite:

  private val startWithDice = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 BPR"
  private val startNoDice   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val winFen        = "k7/8/8/8/8/8/PPPPPPPP/QNBK4 w - - 0 1"
  private val loseFen       = "k7/q7/r7/8/8/8/8/4K3 w - - 0 1"
  private val equalFen      = "k7/8/8/8/8/8/8/K7 w - - 0 1"

  /** Long-algebraic notation of a micro-move, mirroring the decorator's internal matcher. */
  private def uci(m: Move): String = m.toUci

  private def silentBot(result: Option[ScoredSequence] = None): SearchAlgorithm =
    new SearchAlgorithm:
      def findBestMove(state: GameState): Option[ScoredSequence] = result

  private val fallback = Some(ScoredSequence(List(Move(Square('h', 2), Square('h', 3))), 100))

  test("plays a booked turn taken from the legal paths, ignoring stored move order") {
    val state = FenParser.parse(startWithDice).toOption.get
    val path  = TurnGenerator.generateAllLegalTurnPaths(state).head
    val moves = path.map(uci)
    // Store the moves reversed to prove matching is by multiset, not by sequence.
    val book = Map(OpeningBook.key(state).get -> moves.reverse.mkString(","))
    val bot  = new OpeningBookBot(silentBot(), book)
    assertEquals(bot.findBestMove(state).get.moves.map(uci).sorted, moves.sorted)
  }

  test("matches a booked promotion including the promotion suffix") {
    val state = FenParser.parse("7k/P7/8/8/8/8/8/7K w - - 0 1 P").toOption.get
    val book  = Map(OpeningBook.key(state).get -> "a7a8q")
    val bot   = new OpeningBookBot(silentBot(), book)
    assertEquals(bot.findBestMove(state).get.moves.map(uci), List("a7a8q"))
  }

  test("delegates to underlying when the position is not booked") {
    val state = FenParser.parse(startWithDice).toOption.get
    val bot   = new OpeningBookBot(silentBot(fallback), Map.empty)
    assertEquals(bot.findBestMove(state), fallback)
  }

  test("findBestMove(state, random) routes the seed to the underlying bot") {
    // silentBot has no seeded overload, so this also exercises SearchAlgorithm's default findBestMove(state, random).
    val state = FenParser.parse(startWithDice).toOption.get
    val bot   = new OpeningBookBot(silentBot(fallback), Map.empty)
    assertEquals(bot.findBestMove(state, Random(0)), fallback)
  }

  test("delegates to underlying when the booked move cannot be played legally") {
    val state = FenParser.parse(startWithDice).toOption.get
    val book  = Map(OpeningBook.key(state).get -> "a1a8") // not a legal turn from the start
    val bot   = new OpeningBookBot(silentBot(fallback), book)
    assertEquals(bot.findBestMove(state), fallback)
  }

  test("never consults the book when no dice are rolled") {
    val state = FenParser.parse(startNoDice).toOption.get
    val bot   = new OpeningBookBot(silentBot(fallback), Map("anything" -> "e2e4"))
    assertEquals(bot.findBestMove(state), fallback)
  }

  test("forwards the deadline to a time-budgeted underlying on a book miss") {
    val state      = FenParser.parse(startWithDice).toOption.get
    var seen       = 0L
    val underlying = new SearchAlgorithm with TimeBudgetedSearch:
      def findBestMove(state: GameState): Option[ScoredSequence]                                               = None
      override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] =
        seen = deadlineNanos
        fallback
    val bot = new TimeBudgetedOpeningBookBot(underlying, Map.empty)
    assertEquals(bot.findBestMove(state, 123456789L, new Random(1)), fallback)
    assertEquals(seen, 123456789L)
  }

  test("book hit short-circuits even on the time-budgeted entry point") {
    val state      = FenParser.parse(startWithDice).toOption.get
    val path       = TurnGenerator.generateAllLegalTurnPaths(state).head
    val book       = Map(OpeningBook.key(state).get -> path.map(uci).mkString(","))
    var called     = false
    val underlying = new SearchAlgorithm with TimeBudgetedSearch:
      def findBestMove(state: GameState): Option[ScoredSequence] = { called = true; None }
      override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] =
        called = true
        None
    val bot    = new TimeBudgetedOpeningBookBot(underlying, book)
    val played = bot.findBestMove(state, 1L, new Random(1))
    assertEquals(played.get.moves.map(uci).sorted, path.map(uci).sorted)
    assert(!called, "underlying must not be consulted on a book hit")
  }

  test("decorate preserves the underlying's time-budget capability") {
    assert(!OpeningBookBot.decorate(silentBot(), Map.empty).isInstanceOf[TimeBudgetedSearch])
    val tb = new SearchAlgorithm with TimeBudgetedSearch:
      def findBestMove(state: GameState): Option[ScoredSequence]                                               = None
      override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] = None
    assert(OpeningBookBot.decorate(tb, Map.empty).isInstanceOf[TimeBudgetedSearch])
  }

  test("proxies double decisions unconditionally to underlying") {
    val state     = FenParser.parse(startNoDice).toOption.get
    val winState  = FenParser.parse(winFen).toOption.get
    val loseState = FenParser.parse(loseFen).toOption.get

    // 1. DrawOfferLogic underlying
    val withLogic = new SearchAlgorithm with DrawOfferLogic:
      def findBestMove(state: GameState): Option[ScoredSequence]             = None
      override def shouldOfferDouble(state: GameState, stake: Int): Boolean  = stake == 10
      override def shouldAcceptDouble(state: GameState, stake: Int): Boolean = stake == 20
    val bookedLogic = new OpeningBookBot(withLogic, Map.empty)
    assert(bookedLogic.shouldOfferDouble(state, 10))
    assert(!bookedLogic.shouldOfferDouble(state, 5))
    assert(bookedLogic.shouldAcceptDouble(state, 20))
    assert(!bookedLogic.shouldAcceptDouble(state, 10))

    // 2. Direct-override underlying (e.g. AggressiveSearch without DrawOfferLogic)
    val bookedAggressive = OpeningBookBot.decorate(AggressiveSearch, Map.empty)
    assert(bookedAggressive.shouldOfferDouble(winState, 1))
    assert(bookedAggressive.shouldAcceptDouble(winState, 2))
    assert(!bookedAggressive.shouldOfferDouble(loseState, 1))
    assert(!bookedAggressive.shouldAcceptDouble(loseState, 2))

    // 3. Default SearchAlgorithm underlying (preserves trait default: accept when winProb > 0.25)
    val plain = new OpeningBookBot(silentBot(), Map.empty)
    assert(!plain.shouldOfferDouble(state, 10))
    assert(plain.shouldAcceptDouble(state, 20))      // winProb ~0.50 > 0.25 in starting position
    assert(!plain.shouldAcceptDouble(loseState, 20)) // winProb < 0.25 in lost position
  }

  test("plays a booked turn for Black, using the lower-cased dice key") {
    val state = FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1 bpr").toOption.get
    val path  = TurnGenerator.generateAllLegalTurnPaths(state).head
    val book  = Map(OpeningBook.key(state).get -> path.map(uci).mkString(","))
    val bot   = new OpeningBookBot(silentBot(), book)
    assertEquals(bot.findBestMove(state).get.moves.map(uci).sorted, path.map(uci).sorted)
  }

  test("a booked move missing the promotion suffix does not match a promotion-only turn") {
    val state = FenParser.parse("7k/P7/8/8/8/8/8/7K w - - 0 1 P").toOption.get
    val book  = Map(OpeningBook.key(state).get -> "a7a8") // no promotion piece ⇒ matches no legal path
    val bot   = new OpeningBookBot(silentBot(fallback), book)
    assertEquals(bot.findBestMove(state), fallback)
  }

  test("proxies draw decisions unconditionally to underlying") {
    val startState = FenParser.parse(startNoDice).toOption.get
    val equalState = FenParser.parse(equalFen).toOption.get
    val loseState  = FenParser.parse(loseFen).toOption.get

    // 1. Inherited DrawOfferLogic underlying (no method overrides)
    val withLogic = new SearchAlgorithm with DrawOfferLogic:
      def findBestMove(state: GameState): Option[ScoredSequence] = None
    val bookedLogic = new OpeningBookBot(withLogic, Map.empty)
    // Insufficient material (K vs K) triggers offer; starting position does not
    assert(bookedLogic.shouldOfferDraw(equalState))
    assert(!bookedLogic.shouldOfferDraw(startState))
    // Severe disadvantage (eval < -200) triggers acceptance; starting position does not
    assert(bookedLogic.shouldAcceptDraw(loseState))
    assert(!bookedLogic.shouldAcceptDraw(startState))

    // 2. Direct-override underlying without DrawOfferLogic returning distinct outcomes
    val directOverride = new SearchAlgorithm:
      def findBestMove(state: GameState): Option[ScoredSequence] = None
      override def shouldOfferDraw(state: GameState): Boolean    = true
      override def shouldAcceptDraw(state: GameState): Boolean   = false
    val bookedOverride = new OpeningBookBot(directOverride, Map.empty)
    assert(bookedOverride.shouldOfferDraw(startState))
    assert(!bookedOverride.shouldAcceptDraw(startState))

    // 3. Default SearchAlgorithm underlying (both default to false)
    val plain = new OpeningBookBot(silentBot(), Map.empty)
    assert(!plain.shouldOfferDraw(startState))
    assert(!plain.shouldAcceptDraw(startState))
  }

  test("OpeningBookParser parses a canonical-key TSV format") {
    val tsv    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - BPR\te2e4,f1c4"
    val parsed = OpeningBookParser.parse(tsv)
    assert(parsed.isRight)
    assertEquals(
      parsed.toOption.get.get("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - BPR"),
      Some("e2e4,f1c4")
    )
  }

  test("OpeningBookParser returns an empty map for empty string and fails on malformed values") {
    assertEquals(OpeningBookParser.parse("").toOption, Some(Map.empty[String, String]))
    assert(OpeningBookParser.parse("k: 5").isLeft)     // No tab character
    assert(OpeningBookParser.parse("\t1.e2e4").isLeft) // Missing key
    assert(
      OpeningBookParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - BPR\t").isLeft
    ) // Missing continuation
  }

  test("plays an immediate king capture instead of the booked move when king capture is available on book hit") {
    // White Qh5, Black f-pawn advanced (f7 empty: ppppp1pp), Black king on e8, Queen die in pool
    val fen   = "rnbqkbnr/ppppp1pp/8/7Q/5p2/8/PPPPPPPP/RNB1KBNR w KQkq - 0 1 Q"
    val state = FenParser.parse(fen).toOption.get
    // Key hit with a quiet continuation (e.g. h5e5)
    val bookKey = OpeningBook.key(state).get
    val book    = Map(bookKey -> "h5e5")
    val bot     = new OpeningBookBot(silentBot(fallback), book)

    val res = bot.findBestMove(state).get
    assertEquals(res.score, SearchScoring.TerminalWinScore)
    assertEquals(res.moves.map(uci), List("h5e8"))
  }

  test("chooses the shortest king capture path (fewest micro-moves) among multiple capture paths") {
    // White Queen on h5, Rook on e1, Black King on e8, f7 empty (ppppp1pp).
    // Dice pool has Q and R.
    // 1 micro-move capture: Qh5xe8 (1 move)
    // 2 micro-move capture: e1e7, e7e8 (2 moves)
    val fen     = "rnbqkbnr/ppppp1pp/8/7Q/5p2/8/PPPPPPPP/R3KBNR w KQkq - 0 1 QR"
    val state   = FenParser.parse(fen).toOption.get
    val bookKey = OpeningBook.key(state).get
    val book    = Map(bookKey -> "a2a3")
    val bot     = new OpeningBookBot(silentBot(fallback), book)

    val res = bot.findBestMove(state).get
    assertEquals(res.score, SearchScoring.TerminalWinScore)
    assertEquals(res.moves.size, 1)
    assertEquals(res.moves.map(uci), List("h5e8"))
  }

  test("immediate king capture takes precedence through TimeBudgetedOpeningBookBot") {
    val fen          = "rnbqkbnr/ppppp1pp/8/7Q/5p2/8/PPPPPPPP/RNB1KBNR w KQkq - 0 1 Q"
    val state        = FenParser.parse(fen).toOption.get
    val bookKey      = OpeningBook.key(state).get
    val book         = Map(bookKey -> "h5e5")
    val tbUnderlying = new SearchAlgorithm with TimeBudgetedSearch:
      def findBestMove(state: GameState): Option[ScoredSequence]                                               = None
      override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] = None
    val bot = new TimeBudgetedOpeningBookBot(tbUnderlying, book)

    val res = bot.findBestMove(state, 1000000L, new Random(1)).get
    assertEquals(res.score, SearchScoring.TerminalWinScore)
    assertEquals(res.moves.map(uci), List("h5e8"))
  }

  test("booked position with no capture available still returns the booked move") {
    val state   = FenParser.parse(startWithDice).toOption.get
    val path    = TurnGenerator.generateAllLegalTurnPaths(state).head
    val bookKey = OpeningBook.key(state).get
    val book    = Map(bookKey -> path.map(uci).mkString(","))
    val bot     = new OpeningBookBot(silentBot(fallback), book)

    val res = bot.findBestMove(state).get
    assertNotEquals(res.score, SearchScoring.TerminalWinScore)
    assertEquals(res.moves.map(uci).sorted, path.map(uci).sorted)
  }
