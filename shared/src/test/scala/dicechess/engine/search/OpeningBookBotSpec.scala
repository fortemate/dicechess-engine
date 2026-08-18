package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.util.Random

class OpeningBookBotSpec extends FunSuite:

  private val startWithDice = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 BPR"
  private val startNoDice   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

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

  test("proxies double decisions to a DrawOfferLogic underlying, else stays silent") {
    val state     = FenParser.parse(startNoDice).toOption.get
    val withLogic = new SearchAlgorithm with DrawOfferLogic:
      def findBestMove(state: GameState): Option[ScoredSequence]             = None
      override def shouldOfferDouble(state: GameState, stake: Int): Boolean  = stake == 10
      override def shouldAcceptDouble(state: GameState, stake: Int): Boolean = stake == 20
    val booked = new OpeningBookBot(withLogic, Map.empty)
    assert(booked.shouldOfferDouble(state, 10))
    assert(!booked.shouldOfferDouble(state, 5))
    assert(booked.shouldAcceptDouble(state, 20))

    val plain = new OpeningBookBot(silentBot(), Map.empty)
    assert(!plain.shouldOfferDouble(state, 10))
    assert(!plain.shouldAcceptDouble(state, 20))
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

  test("proxies draw decisions to a DrawOfferLogic underlying, else stays silent") {
    val state     = FenParser.parse(startNoDice).toOption.get
    val withLogic = new SearchAlgorithm with DrawOfferLogic:
      def findBestMove(state: GameState): Option[ScoredSequence] = None
      override def shouldOfferDraw(state: GameState): Boolean    = true
      override def shouldAcceptDraw(state: GameState): Boolean   = true
    assert(new OpeningBookBot(withLogic, Map.empty).shouldOfferDraw(state))
    assert(new OpeningBookBot(withLogic, Map.empty).shouldAcceptDraw(state))
    assert(!new OpeningBookBot(silentBot(), Map.empty).shouldOfferDraw(state))
    assert(!new OpeningBookBot(silentBot(), Map.empty).shouldAcceptDraw(state))
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
