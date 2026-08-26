package dicechess.engine.bench

import scala.concurrent.duration.*
import scala.util.Random

import com.monovore.decline.Command
import dicechess.engine.domain.*
import dicechess.engine.search.*
import munit.FunSuite

class BotMatchRunnerSpec extends FunSuite:

  override def munitTimeout: Duration = 3.minutes

  test("simulateGame completes successfully and returns a valid outcome") {
    val outcome = BotMatchRunner.simulateGame(GreedySearch, GreedySearch, new Random(42), new Random(1000))

    // Outcome must be either a Win or a Draw
    assert(outcome == GameOutcome.Draw || outcome.isInstanceOf[GameOutcome.Win])
  }

  test("runMatch is reproducible across runs (bot tie-breaking is seeded, not fresh per move)") {
    def counts(r: MatchResult) =
      (r.winsAsWhite, r.winsAsBlack, r.lossesAsWhite, r.lossesAsBlack, r.drawsAsWhite, r.drawsAsBlack)
    // Both bots break ties randomly; only a seeded bot source makes two runs identical (this fails before the fix).
    val a = BotMatchRunner.runMatch(GreedySearch, AggressiveSearch, 4)
    val b = BotMatchRunner.runMatch(GreedySearch, AggressiveSearch, 4)
    assertEquals(counts(a), counts(b))
    // The hang telemetry is derived from the same seeded games, so it must be exactly as reproducible.
    assertEquals(a.opponentHangs, b.opponentHangs)
    assertEquals(a.baseHangs, b.baseHangs)
  }

  test("runMatch: same seed reproduces results, a different seed changes them") {
    def counts(r: MatchResult) =
      (r.winsAsWhite, r.winsAsBlack, r.lossesAsWhite, r.lossesAsBlack, r.drawsAsWhite, r.drawsAsBlack)
    val defaultFen = FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").toOption.get
    val a          = BotMatchRunner.runMatch(GreedySearch, AggressiveSearch, 6, defaultFen, seed = 7)
    val b          = BotMatchRunner.runMatch(GreedySearch, AggressiveSearch, 6, defaultFen, seed = 7)
    val c          = BotMatchRunner.runMatch(GreedySearch, AggressiveSearch, 6, defaultFen, seed = 99)
    assertEquals(counts(a), counts(b))
    assertEquals(a.opponentHangs, b.opponentHangs)
    // Different seeds draw an independent sample; requiring the tallies to differ risks flaking on a coincidental
    // tie, so it is the hang telemetry — a finer-grained signal than W/L/D — that must diverge.
    assertNotEquals(a.opponentHangs, c.opponentHangs)
  }

  test("runMatch executes the correct number of games and enforces alternating colors") {
    val gamesPerColor = 5
    val matchResult   = BotMatchRunner.runMatch(GreedySearch, GreedySearch, gamesPerColor)

    assertEquals(matchResult.totalGames, gamesPerColor * 2)
    assertEquals(matchResult.winsAsWhite + matchResult.lossesAsWhite + matchResult.drawsAsWhite, gamesPerColor)
    assertEquals(matchResult.winsAsBlack + matchResult.lossesAsBlack + matchResult.drawsAsBlack, gamesPerColor)
    assert(matchResult.durationMs >= 0)
  }

  test("Random vs Greedy results in higher win rate for Greedy") {
    val gamesPerColor = 5
    // Random is expected to perform extremely poorly against Greedy
    val matchResult = BotMatchRunner.runMatch(RandomSearch, GreedySearch, gamesPerColor)

    val totalWinsForRandom = matchResult.winsAsWhite + matchResult.winsAsBlack
    val totalWinsForGreedy = matchResult.lossesAsWhite + matchResult.lossesAsBlack

    assert(
      totalWinsForGreedy >= totalWinsForRandom,
      s"Expected Greedy to beat Random, but got Greedy: $totalWinsForGreedy, Random: $totalWinsForRandom"
    )
  }

  // ---- Hang telemetry (#494) ----

  test("hang telemetry: per-algorithm stats satisfy their structural invariants") {
    val r = BotMatchRunner.runMatch(GreedySearch, AggressiveSearch, 3)
    for s <- List(r.opponentHangs, r.baseHangs) do
      assert(s.turns > 0, "every played game contributes completed turns")
      assert(s.hangTurns >= 0 && s.hangTurns <= s.turns)
      assert(s.queenHangTurns <= s.hangTurns, "a queen hang is a hang turn")
      assert(s.hangingMaterial >= s.hangTurns * 100L, "every hang turn contributes at least one pawn of material")
      assert(s.punishedMaterial >= s.punishedCaptures * 100L)
  }

  /** Plays a fixed script of turns, then passes forever — [[BotMatchRunner.playTurn]] applies moves without consulting
    * the dice, so a scripted game can pin the telemetry's cause-and-effect exactly.
    */
  final private class ScriptedBot(script: List[List[Move]]) extends SearchAlgorithm:
    private var remaining                                               = script
    override def findBestMove(state: GameState): Option[ScoredSequence] =
      remaining match
        case head :: tail =>
          remaining = tail
          Some(ScoredSequence(head, 0))
        case Nil => None

  test("hang telemetry: a hung queen and its punishment are attributed to the side that hung it") {
    // White walks its queen from d4 into the c6-pawn's attack (undefended), Black takes it next turn. Both sides then
    // shuffle their kings until the 50-move rule ends the game (passes would freeze the half-move clock forever) —
    // bare-king shuffling neither hangs anything nor captures anything, so the telemetry stays pinned to the script.
    def shuffle(file1: Char, file2: Char, rank: Int) = List.tabulate(60) { i =>
      if i % 2 == 0 then List(Move(Square(file1, rank), Square(file2, rank)))
      else List(Move(Square(file2, rank), Square(file1, rank)))
    }
    val start = FenParser.parse("4k3/8/2p5/8/3Q4/8/8/4K3 w - - 0 1").toOption.get
    val white = new ScriptedBot(List(Move(Square('d', 4), Square('d', 5))) :: shuffle('e', 'd', 1))
    val black = new ScriptedBot(List(Move(Square('c', 6), Square('d', 5))) :: shuffle('e', 'd', 8))

    val whiteTally = new HangTally
    val blackTally = new HangTally
    val outcome    =
      BotMatchRunner.simulateGame(white, black, new Random(42), new Random(1000), start, whiteTally, blackTally)
    assertEquals(outcome, GameOutcome.Draw) // both bots pass after the script, so the 50-move rule fires

    val w = whiteTally.snapshot
    assertEquals(w.hangTurns, 1)
    assertEquals(w.queenHangTurns, 1)
    assertEquals(w.hangingMaterial, 900L)
    assertEquals(w.punishedCaptures, 1)
    assertEquals(w.punishedMaterial, 900L)

    // Black never left anything en prise and was never punished; the c6→d5 pawn is not attacked by the bare king.
    val b = blackTally.snapshot
    assertEquals(b.hangTurns, 0)
    assertEquals(b.queenHangTurns, 0)
    assertEquals(b.hangingMaterial, 0L)
    assertEquals(b.punishedCaptures, 0)
    assertEquals(b.punishedMaterial, 0L)
  }

  // ---- Time-controlled arena: pure helpers (deterministic, no wall-clock) ----

  test("tickClock: credits the increment on a non-flag and flags when the clock goes negative") {
    assertEquals(BotMatchRunner.tickClock(1000, 300, 100), (800L, false))
    assertEquals(BotMatchRunner.tickClock(100, 250, 50), (-150L, true))
    // Exact spend is not a flag; the increment is still credited.
    assertEquals(BotMatchRunner.tickClock(500, 500, 100), (100L, false))
  }

  test("LatencyStats.from: nearest-rank percentiles over (unsorted) samples") {
    assertEquals(LatencyStats.from(Nil), LatencyStats.empty)
    assertEquals(LatencyStats.from(List(42)), LatencyStats(1, 42, 42, 42, 42))
    val ten = LatencyStats.from(List(10, 20, 30, 40, 50, 60, 70, 80, 90, 100))
    assertEquals(ten, LatencyStats(10, 50, 100, 100, 100))
    // Sorting is applied: median of {10,20,30} is 20.
    assertEquals(LatencyStats.from(List(30, 10, 20)).p50Ms, 20L)
  }

  test("TimeControl.label and TimedMatchResult.scorePercent") {
    assertEquals(TimeControl.ofSeconds(60, 0).label, "60s")
    assertEquals(TimeControl.ofSeconds(180, 2).label, "180s+2s")
    assertEquals(TimeControl.ofSeconds(60, 0), TimeControl(60000L, 0L))
    val r =
      TimedMatchResult(TimeControl.ofSeconds(60, 0), 10, wins = 4, losses = 4, draws = 2, 0, 0, LatencyStats.empty, 0)
    assertEqualsDouble(r.scorePercent, 50.0, 1e-9)
  }

  test("parsePresets: parses chess-clock 'minutes[+incrementSeconds]' specs and rejects bad input") {
    // 1-minute bullet, 3 min + 2 s, 10 min + 10 s — base is minutes, increment is seconds.
    assertEquals(
      TimedArenaRunner.parsePresets("1+0,3+2,10+10"),
      List(TimeControl(60000L, 0L), TimeControl(180000L, 2000L), TimeControl(600000L, 10000L))
    )
    assertEquals(TimedArenaRunner.parsePresets("1"), List(TimeControl(60000L, 0L)))
    intercept[RuntimeException](TimedArenaRunner.parsePresets("3+x")) // non-integer increment
    intercept[RuntimeException](TimedArenaRunner.parsePresets("0+2")) // base minutes must be > 0
    intercept[RuntimeException](TimedArenaRunner.parsePresets(""))    // at least one preset required
  }

  // ---- Time-controlled arena: thin wall-clock smoke tests ----

  test("simulateTimedGame: a time-budgeted bot on a near-zero clock flags and loses on time") {
    val result =
      BotMatchRunner.simulateTimedGame(
        MonteCarloSearch,
        GreedySearch,
        new Random(1),
        new Random(2),
        TimeControl(5L, 0L)
      )
    assertEquals(result.flaggedColor, Some(Color.White)) // Monte-Carlo (White) overruns its 5ms and flags
    assertEquals(result.outcome, GameOutcome.Win(Color.Black))
    assertEquals(result.latenciesByColorMs.size, 1)              // exactly one timed move was made before the flag
    assertEquals(result.latenciesByColorMs.head._1, Color.White) // and it is attributed to White (Monte-Carlo)
  }

  test("runTimedMatch: O(1) bots never flag and the W/L/D totals are consistent") {
    val r = BotMatchRunner.runTimedMatch("greedy", "random", TimedMatchSetup(2, TimeControl.ofSeconds(6, 0)))
    assertEquals(r.totalGames, 4)
    assertEquals(r.wins + r.losses + r.draws, 4)
    assertEquals(r.botTimeouts, 0)
    assertEquals(r.baselineTimeouts, 0)
    assertEquals(r.latency, LatencyStats.empty) // neither bot is time-budgeted, so no latency samples
  }

  test("runTimedMatch: applies each participant's configured time manager across both colour phases") {
    def policy(idValue: String, movesToGo: Double): TimePolicy = new TimePolicy:
      override val id: String                                    = idValue
      override def estimateMovesToGo(_clock: ClockState): Double = movesToGo

    final class DeadlineRecordingBot extends TimeBudgetedSearch:
      private val observations = scala.collection.mutable.ListBuffer.empty[(Color, Long)]

      def remainingNanosByColor: Map[Color, Long] = observations.toMap

      override def findBestMove(state: GameState): Option[ScoredSequence] =
        TurnGenerator.generateAllLegalTurnPaths(state).headOption.map(ScoredSequence(_, 0))

      override def findBestMove(
          state: GameState,
          deadlineNanos: Long,
          _random: Random
      ): Option[ScoredSequence] =
        if !observations.exists(_._1 == state.activeColor) then
          observations += ((state.activeColor, deadlineNanos - System.nanoTime()))
        findBestMove(state)

    def assertBudget(observedNanos: Long, expectedMs: Long): Unit =
      val expectedNanos = expectedMs * 1_000_000L
      assert(observedNanos <= expectedNanos, s"deadline offset $observedNanos exceeded $expectedNanos")
      assert(
        observedNanos >= expectedNanos - 50_000_000L,
        s"deadline offset $observedNanos was more than 50 ms below $expectedNanos"
      )

    val botManager      = TimeManager(policy("test-bot-policy", 5.0))
    val baselineManager = TimeManager(policy("test-baseline-policy", 30.0))
    val bot             = new DeadlineRecordingBot
    val baseline        = new DeadlineRecordingBot
    val timeControl     = TimeControl.ofSeconds(6, 0)
    val result          = BotMatchRunner.runTimedMatch(
      bot,
      baseline,
      TimedMatchSetup(
        1,
        timeControl,
        botTimeManager = botManager,
        baselineTimeManager = baselineManager
      )
    )

    assertEquals(result.botTimePolicyId, "test-bot-policy")
    assertEquals(result.baselineTimePolicyId, "test-baseline-policy")
    val initialClock     = ClockState(timeControl.initialMs, timeControl.incrementMs, 1)
    val botBudgetMs      = botManager.budgetMs(initialClock, 50L)
    val baselineBudgetMs = baselineManager.budgetMs(initialClock, 50L)
    val botObservations  = bot.remainingNanosByColor
    val baseObservations = baseline.remainingNanosByColor
    assertEquals(botObservations.keySet, Set(Color.White, Color.Black))
    assertEquals(baseObservations.keySet, Set(Color.White, Color.Black))
    botObservations.values.foreach(assertBudget(_, botBudgetMs))
    baseObservations.values.foreach(assertBudget(_, baselineBudgetMs))
    assertNotEquals(botBudgetMs, baselineBudgetMs)
  }

  test(
    "runTimedMatch: same seed reproduces results, a different seed changes them, default seed matches the old hardcoded stream"
  ) {
    val tc = TimeControl.ofSeconds(6, 0)
    val a  = BotMatchRunner.runTimedMatch("greedy", "aggressive", TimedMatchSetup(6, tc, seed = 7))
    val b  = BotMatchRunner.runTimedMatch("greedy", "aggressive", TimedMatchSetup(6, tc, seed = 7))
    val c  = BotMatchRunner.runTimedMatch("greedy", "aggressive", TimedMatchSetup(6, tc, seed = 99))
    assertEquals((a.wins, a.losses, a.draws), (b.wins, b.losses, b.draws))
    // Default seed (42) must reproduce the pre-existing hardcoded-stream behaviour exactly.
    val default    = BotMatchRunner.runTimedMatch("greedy", "aggressive", TimedMatchSetup(6, tc))
    val explicit42 = BotMatchRunner.runTimedMatch("greedy", "aggressive", TimedMatchSetup(6, tc, seed = 42))
    assertEquals((default.wins, default.losses, default.draws), (explicit42.wins, explicit42.losses, explicit42.draws))
    // A different seed draws an independent sample — different dice, different games.
    assertNotEquals((a.wins, a.losses, a.draws), (c.wins, c.losses, c.draws))
  }

  // ---- SPRT stopping for the timed runner (#522) ----

  test("runTimedMatch: without sprtConfig, gamesPerColor is a fixed count and sprt is None") {
    val r = BotMatchRunner.runTimedMatch("greedy", "aggressive", TimedMatchSetup(4, TimeControl.ofSeconds(6, 0)))
    assertEquals(r.totalGames, 8)
    assertEquals(r.sprt, None)
  }

  test("runTimedMatch: with sprtConfig, a decisive matchup stops before the gamesPerColor cap") {
    // Greedy dominates Random (see the untimed "Random vs Greedy" test); a generous cap and loose error rates should
    // let the LLR cross a bound well before all 60 pairs play out.
    val cfg = SprtConfig(elo0 = 0, elo1 = 100, alpha = 0.05, beta = 0.05)
    val r   = BotMatchRunner.runTimedMatch(
      "greedy",
      "random",
      TimedMatchSetup(60, TimeControl.ofSeconds(6, 0), sprtConfig = Some(cfg))
    )
    val sprtResult = r.sprt.getOrElse(fail("sprtConfig was supplied, so sprt must be populated"))
    assert(r.totalGames < 120, s"expected the match to stop before the 60-pair cap, got ${r.totalGames} games")
    assertEquals(sprtResult.verdict, Sprt.Verdict.AcceptH1)
    assert(sprtResult.llr >= sprtResult.upper)
    assertEquals(sprtResult.observations, (r.totalGames / 2).toLong) // one observation per mirrored pair
  }

  test("runTimedMatch: with sprtConfig, an even matchup runs to the gamesPerColor cap (Continue)") {
    // Same bot on both sides: perfectly even, so the LLR should never leave the (lower, upper) band.
    val cfg = SprtConfig(elo0 = 0, elo1 = 20, alpha = 0.05, beta = 0.05)
    val r   = BotMatchRunner.runTimedMatch(
      "greedy",
      "greedy",
      TimedMatchSetup(5, TimeControl.ofSeconds(6, 0), sprtConfig = Some(cfg))
    )
    assertEquals(r.totalGames, 10)
    val sprtResult = r.sprt.getOrElse(fail("sprtConfig was supplied, so sprt must be populated"))
    assertEquals(sprtResult.verdict, Sprt.Verdict.Continue)
  }

  test("runTimedMatch: resumed observations preserve pair indices, random streams, and aggregate results") {
    final class TimedFirstLegalBot extends TimeBudgetedSearch:
      override def findBestMove(state: GameState): Option[ScoredSequence] =
        TurnGenerator.generateAllLegalTurnPaths(state).headOption.map(ScoredSequence(_, 0))

      override def findBestMove(
          state: GameState,
          _deadlineNanos: Long,
          _random: Random
      ): Option[ScoredSequence] = findBestMove(state)

    val tc    = TimeControl.ofSeconds(6, 0)
    val saved = scala.collection.mutable.ListBuffer.empty[PairObservation]
    val first = BotMatchRunner.runTimedMatch(
      new TimedFirstLegalBot,
      AggressiveSearch,
      TimedMatchSetup(2, tc, seed = 17, gameSink = Some(saved += _))
    )
    val resumedNew = scala.collection.mutable.ListBuffer.empty[PairObservation]
    val resumed    = BotMatchRunner.runTimedMatch(
      new TimedFirstLegalBot,
      AggressiveSearch,
      TimedMatchSetup(
        4,
        tc,
        seed = 17,
        gameSink = Some(resumedNew += _),
        resume = TimedMatchResume(saved.toVector, first.durationMs)
      )
    )
    val uninterrupted =
      BotMatchRunner.runTimedMatch(new TimedFirstLegalBot, AggressiveSearch, TimedMatchSetup(4, tc, seed = 17))

    def botLatencies(observation: PairObservation): List[Long] =
      observation.whiteGame.latenciesByColorMs.collect { case (Color.White, milliseconds) => milliseconds } ++
        observation.blackGame.latenciesByColorMs.collect { case (Color.Black, milliseconds) => milliseconds }

    val savedLatencies    = saved.toList.flatMap(botLatencies)
    val expectedLatencies = LatencyStats.from(savedLatencies ++ resumedNew.toList.flatMap(botLatencies))

    assertEquals(saved.map(_.index).toList, List(0, 1))
    assertEquals(resumedNew.map(_.index).toList, List(2, 3))
    assert(savedLatencies.nonEmpty)
    assertEquals(
      (resumed.wins, resumed.losses, resumed.draws, resumed.botTimeouts, resumed.baselineTimeouts),
      (
        uninterrupted.wins,
        uninterrupted.losses,
        uninterrupted.draws,
        uninterrupted.botTimeouts,
        uninterrupted.baselineTimeouts
      )
    )
    assertEquals(resumed.pentanomial, uninterrupted.pentanomial)
    assertEquals(resumed.latency, expectedLatencies)
    assertEquals(resumed.totalGames, uninterrupted.totalGames)
    assert(resumed.durationMs >= first.durationMs)
  }

  /** Synthetic [[TimedMatchResult]] fixture for the reporting tests below — they assert only serialization/printing of
    * the `sprt` field, so a fabricated result avoids paying for (and depending on the probabilistic verdict of) another
    * live match; [[BotMatchRunner.runTimedMatch]]'s actual SPRT-triggered early stop is covered above.
    */
  private def fixtureTimedResult(sprt: Option[Sprt.Result]): TimedMatchResult =
    TimedMatchResult(
      timeControl = TimeControl.ofSeconds(6, 0),
      totalGames = 20,
      wins = 12,
      losses = 6,
      draws = 2,
      botTimeouts = 0,
      baselineTimeouts = 0,
      latency = LatencyStats.empty,
      durationMs = 0,
      sprt = sprt
    )

  private val fixtureDecisiveSprt = Sprt.Result(3.1, -2.944, 2.944, Sprt.Verdict.AcceptH1, 10)

  test("printTimedSummary: prints the SPRT line only when sprt is populated") {
    val results = List(fixtureTimedResult(Some(fixtureDecisiveSprt)), fixtureTimedResult(None))
    val out     = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      BotMatchRunner.printTimedSummary("greedy", "random", results)
    }
    val lines = out.toString("UTF-8").linesIterator.count(_.contains("SPRT:"))
    assertEquals(lines, 1)
  }

  test("timedReportJson: includes a populated sprt object when active, and JSON null otherwise") {
    val results       = List(fixtureTimedResult(Some(fixtureDecisiveSprt)), fixtureTimedResult(None))
    val json          = BotMatchRunner.timedReportJson("greedy", "random", 60, 42L, results)
    val parsed        = Json.parse(Json.render(json)).getOrElse(fail("report did not render as valid JSON"))
    val parsedResults = parsed.field("results").flatMap(_.asArr).getOrElse(fail("results missing"))

    val activeSprt = parsedResults.head.field("sprt").getOrElse(fail("sprt field missing"))
    assertEquals(activeSprt.field("verdict").flatMap(_.asStr), Some("AcceptH1"))
    assertEquals(activeSprt.field("observations").flatMap(_.asNum), Some(10.0))

    assertEquals(parsedResults(1).field("sprt"), Some(Json.JNull))
  }

  test("ArenaOptions.sprtConfigOpt: valid and malformed specs") {
    val cmd = Command("test", "test")(ArenaOptions.sprtConfigOpt)

    assertEquals(cmd.parse(Seq("--sprt", "0,20,0.05,0.05"), sys.env), Right(Some(SprtConfig(0, 20, 0.05, 0.05))))
    assertEquals(cmd.parse(Seq(), sys.env), Right(None))

    assert(cmd.parse(Seq("--sprt"), sys.env).isLeft)
    assert(cmd.parse(Seq("--sprt", "0,20,0.05"), sys.env).isLeft)
    assert(cmd.parse(Seq("--sprt", "x,20,0.05,0.05"), sys.env).isLeft)
  }

  test("ArenaOptions.sprtConfigOpt: rejects degenerate elo/error-rate ranges") {
    val cmd = Command("test", "test")(ArenaOptions.sprtConfigOpt)

    // elo0 must be strictly below elo1 (equal collapses s1 - s0 to 0; reversed inverts the hypotheses).
    assert(cmd.parse(Seq("--sprt", "20,20,0.05,0.05"), sys.env).isLeft)
    assert(cmd.parse(Seq("--sprt", "20,0,0.05,0.05"), sys.env).isLeft)
    // alpha/beta must sit strictly inside (0, 1): at or beyond the ends, Sprt.test's bounds degenerate to
    // ±Infinity/NaN, making a verdict unreachable, the first pair always decisive, or every comparison false.
    assert(cmd.parse(Seq("--sprt", "0,20,0,0.05"), sys.env).isLeft)
    assert(cmd.parse(Seq("--sprt", "0,20,1,0.05"), sys.env).isLeft)
    assert(cmd.parse(Seq("--sprt", "0,20,0.05,0"), sys.env).isLeft)
    assert(cmd.parse(Seq("--sprt", "0,20,0.05,1"), sys.env).isLeft)
    assert(cmd.parse(Seq("--sprt", "0,20,-0.1,0.05"), sys.env).isLeft)
  }

  test("ArenaOptions.timePolicyOpt: resolves built-in ids and rejects unknown policies") {
    val cmd = Command("test", "test")(ArenaOptions.timePolicyOpt("time-policy", "test bot"))

    assertEquals(
      cmd.parse(Seq("--time-policy", "LEGACY-LINEAR-V1"), sys.env).map(_.id),
      Right("legacy-linear-v1")
    )
    assertEquals(cmd.parse(Seq(), sys.env).map(_.id), Right("empirical-v1"))
    assert(cmd.parse(Seq("--time-policy", "unknown"), sys.env).isLeft)
  }

  test("TimedArenaRunner.main: --sprt runs without error and stops a decisive matchup early") {
    val out = new java.io.ByteArrayOutputStream()
    val res = Console.withOut(out) {
      ArenaOptions.parseAndRun(
        TimedArenaRunner.command,
        Array(
          "--bot",
          "greedy",
          "--baseline",
          "random",
          "--games",
          "60",
          "--presets",
          "6+0",
          "--sprt",
          "0,100,0.05,0.05"
        )
      )
    }
    assertEquals(res, Right(()))
    assert(out.toString("UTF-8").contains("SPRT:"))
  }

  test("TimedArenaRunner.main: runs a small matrix without error") {
    val res = ArenaOptions.parseAndRun(
      TimedArenaRunner.command,
      Array("--bot", "greedy", "--baseline", "random", "--games", "1", "--presets", "6+0")
    )
    assertEquals(res, Right(()))
  }

  test("arenaReportJson: schema round-trips through render/parse") {
    val baseInfo     = BotRegistry.availableBots.find(_.id == "greedy").getOrElse(fail("greedy not registered"))
    val result       = BotMatchRunner.runMatch(AggressiveSearch, GreedySearch, 3)
    val opponentInfo = BotRegistry.availableBots.find(_.id == "aggressive").getOrElse(fail("aggressive not registered"))
    val json         =
      BotMatchRunner.arenaReportJson(
        "greedy",
        baseInfo,
        3,
        7L,
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        List(opponentInfo -> result)
      )

    val parsed = Json.parse(Json.render(json)).getOrElse(fail("report did not render as valid JSON"))
    assertEquals(parsed.field("kind").flatMap(_.asStr), Some("untimed_arena"))
    assertEquals(parsed.field("baseBotId").flatMap(_.asStr), Some("greedy"))
    assertEquals(parsed.field("gamesPerColor").flatMap(_.asNum), Some(3.0))
    assertEquals(parsed.field("seed").flatMap(_.asNum), Some(7.0))

    val matches = parsed.field("matches").flatMap(_.asArr).getOrElse(fail("matches missing"))
    assertEquals(matches.size, 1)
    val m = matches.head
    assertEquals(m.field("opponentId").flatMap(_.asStr), Some("aggressive"))
    assertEquals(m.field("totalGames").flatMap(_.asNum), Some(result.totalGames.toDouble))
    val winsTotal = m.field("wins").flatMap(_.field("total")).flatMap(_.asNum)
    assertEquals(winsTotal, Some((result.winsAsWhite + result.winsAsBlack).toDouble))
    assert(m.field("hangTelemetry").flatMap(_.field("opponent")).flatMap(_.field("turns")).isDefined)
    assert(m.field("hangTelemetry").flatMap(_.field("baseline")).flatMap(_.field("turns")).isDefined)
  }

  test("timedReportJson: schema round-trips through render/parse") {
    val result = BotMatchRunner.runTimedMatch("greedy", "random", TimedMatchSetup(2, TimeControl.ofSeconds(6, 0)))
    val json   = BotMatchRunner.timedReportJson("greedy", "random", 2, 42L, List(result))

    val parsed = Json.parse(Json.render(json)).getOrElse(fail("report did not render as valid JSON"))
    assertEquals(parsed.field("kind").flatMap(_.asStr), Some("timed_arena"))
    assertEquals(parsed.field("botUnderTestId").flatMap(_.asStr), Some("greedy"))
    assertEquals(parsed.field("baselineId").flatMap(_.asStr), Some("random"))

    val results = parsed.field("results").flatMap(_.asArr).getOrElse(fail("results missing"))
    assertEquals(results.size, 1)
    val r = results.head
    assertEquals(r.field("totalGames").flatMap(_.asNum), Some(result.totalGames.toDouble))
    assertEquals(r.field("timeControl").flatMap(_.field("label")).flatMap(_.asStr), Some(result.timeControl.label))
    assertEquals(r.field("flagCounts").flatMap(_.field("bot")).flatMap(_.asNum), Some(result.botTimeouts.toDouble))
    assertEquals(r.field("latencyMs").flatMap(_.field("p50")).flatMap(_.asNum), Some(result.latency.p50Ms.toDouble))
    assertEquals(r.field("timePolicies").flatMap(_.field("bot")).flatMap(_.asStr), Some("empirical-v1"))
    assertEquals(r.field("timePolicies").flatMap(_.field("baseline")).flatMap(_.asStr), Some("empirical-v1"))
  }

  test("--json: writes a parseable report and leaves the default table output unaffected") {
    val untimedOut = java.nio.file.Files.createTempFile("arena-untimed", ".json")
    val timedOut   = java.nio.file.Files.createTempFile("arena-timed", ".json")
    try
      // runArena directly with an explicit opponent — main() always sweeps every registered bot (including the
      // rollout-heavy Monte-Carlo one) since it has no CLI arg to restrict the opponent, which would make this test
      // needlessly slow without exercising any more of the --json wiring (main() only parses args and delegates).
      BotMatchRunner.runArena(
        "greedy",
        Some("random"),
        2,
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        seed = 7L,
        jsonPath = Some(untimedOut.toString)
      )
      val untimed = Json.parse(java.nio.file.Files.readString(untimedOut)).getOrElse(fail("invalid untimed JSON"))
      assertEquals(untimed.field("kind").flatMap(_.asStr), Some("untimed_arena"))

      TimedArenaRunner.main(
        Array(
          "--base-bot",
          "greedy",
          "--opponent",
          "random",
          "--games",
          "1",
          "--presets",
          "6+0",
          "--json",
          timedOut.toString
        )
      )
      val timed = Json.parse(java.nio.file.Files.readString(timedOut)).getOrElse(fail("invalid timed JSON"))
      assertEquals(timed.field("kind").flatMap(_.asStr), Some("timed_arena"))

      // Omitting --json (already exercised by the other main-invoking tests above) never touches the filesystem —
      // the only file-writing path is gated behind the flag being present.
    finally
      java.nio.file.Files.deleteIfExists(untimedOut)
      java.nio.file.Files.deleteIfExists(timedOut)
  }

  test("printSummaryTable/printTimedSummary: pin Locale.ROOT, so %f fields never render a comma decimal") {
    val originalLocale = java.util.Locale.getDefault
    val out            = new java.io.ByteArrayOutputStream()
    try
      java.util.Locale.setDefault(java.util.Locale.GERMANY)
      Console.withOut(out) {
        BotMatchRunner.runArena("greedy", Some("random"), 2, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val timed = BotMatchRunner.runTimedMatch("greedy", "random", TimedMatchSetup(1, TimeControl.ofSeconds(6, 0)))
        BotMatchRunner.printTimedSummary("greedy", "random", List(timed))
      }
    finally java.util.Locale.setDefault(originalLocale)
    val text = out.toString("UTF-8")
    assert(!text.contains(","), s"expected no comma-decimal formatting under Locale.GERMANY, got:\n$text")
  }
