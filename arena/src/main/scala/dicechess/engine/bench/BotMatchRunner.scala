package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.*
import java.nio.file.{Files, Path}
import scala.util.Random

import com.monovore.decline.*
import cats.implicits.*

/** Executable task that runs bot-vs-bot matches in memory.
  *
  * Simulates matches between different search algorithms to evaluate their playing strength. Measures wins, losses,
  * draws, and computes win rates relative to a baseline bot.
  */
object BotMatchRunner:
  def main(args: Array[String]): Unit =
    val command = Command(
      name = "BotMatchRunner",
      header = "Dice Chess Bot Arena - JVM Match Runner"
    ) {
      import ArenaOptions.*
      (baseBotOpt(), opponentOpt("").orNone, gamesOpt(), seedOpt(), jsonPathOpt).mapN {
        (baseBotId, opponentOptStr, gamesPerColor, seed, jsonPath) =>
          val opponentBotId = opponentOptStr.filter(_.nonEmpty)
          try runArena(baseBotId, opponentBotId, gamesPerColor, StartFen, seed, jsonPath)
          catch
            case e: Exception =>
              System.err.println(e.getMessage)
              sys.exit(1)
      }
    }
    ArenaOptions.runCommand(command, args)

  private[bench] val StartFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  def runArena(
      baseBotId: String,
      opponentBotId: Option[String],
      gamesPerColor: Int,
      startFen: String,
      seed: Long = 42L,
      jsonPath: Option[String] = None
  ): Unit =
    if gamesPerColor <= 0 then sys.error(s"Invalid gamesPerColor '$gamesPerColor'. Must be greater than 0.")
    if WebhookBot.isWebhookId(baseBotId) || opponentBotId.exists(WebhookBot.isWebhookId) then
      sys.error("webhook opponents are supported by the timed arena only (TimedArenaRunner)")

    val parsedFen =
      FenParser.parse(startFen).getOrElse(sys.error(s"Invalid start FEN: $startFen"))

    val baseAlgorithmOpt = BotRegistry.getAlgorithm(baseBotId)
    if baseAlgorithmOpt.isEmpty then sys.error(s"Baseline bot with ID '$baseBotId' not found in BotRegistry!")

    val baseAlgorithm = baseAlgorithmOpt.get
    val baseBotIdNorm = baseBotId.toLowerCase
    val baseBotInfo   = BotRegistry.availableBots
      .find(_.id.toLowerCase == baseBotIdNorm)
      .getOrElse {
        sys.error(
          s"Baseline bot details with ID '$baseBotId' not found in BotRegistry!"
        )
      }

    println("================================================================================")
    println(s"🎲♟️  Dice Chess Bot Arena - JVM Match Runner")
    println(s"Baseline Bot: ${baseBotInfo.name} (${baseBotInfo.id})")
    println(s"Games per Color: $gamesPerColor (Total ${gamesPerColor * 2} games per match)")
    if startFen != StartFen then println(s"Starting FEN: $startFen")
    println("================================================================================")

    val opponents = opponentBotId match
      case Some(id) =>
        BotRegistry.availableBots
          .find(_.id.toLowerCase == id.toLowerCase)
          .map(List(_))
          .getOrElse(sys.error(s"Opponent bot with ID '$id' not found!"))
      case None => BotRegistry.availableBots

    val results = for opponentInfo <- opponents yield
      val opponentAlgo = BotRegistry.getAlgorithm(opponentInfo.id).get
      val matchResult  = runMatch(opponentAlgo, baseAlgorithm, gamesPerColor, parsedFen, seed)
      (opponentInfo, matchResult)

    printSummaryTable(results)
    jsonPath.foreach { path =>
      writeJsonReport(path, arenaReportJson(baseBotId, baseBotInfo, gamesPerColor, seed, startFen, results))
    }

  /** Package-private visibility (`private[bench]`) is utilized to expose match orchestration to [[BotMatchRunnerSpec]]
    * for verification of win rates and results aggregation, while keeping execution internal to the bench module.
    *
    * Seeded per game, mirroring [[runTimedMatch]]: dice for game `i` come from `Random(seed + i)` in both color phases
    * (so a mirrored colour pair shares its dice), and each phase's bot tie-breaking gets its own stream (`Random(1000 +
    * i)` / `Random(2000 + i)`). A whole run drawing from one shared stream would couple game `i`'s dice to when game
    * `i − 1` ended, making two shards of the same matchup byte-identical; distinct seeds now draw independent samples
    * for sharding a run across cores or machines — space shard seeds at least `gamesPerColor` apart (e.g.
    * `0, 1000, 2000, ...`) so their `seed + i` dice ranges never overlap.
    */
  private[bench] def runMatch(
      opponentAlgo: SearchAlgorithm,
      baseAlgo: SearchAlgorithm,
      gamesPerColor: Int,
      startFen: GameState = FenParser.parse(StartFen).toOption.get,
      seed: Long = 42L
  ): MatchResult =
    var winsAsWhite   = 0
    var winsAsBlack   = 0
    var lossesAsWhite = 0
    var lossesAsBlack = 0
    var drawsAsWhite  = 0
    var drawsAsBlack  = 0
    // One tally per algorithm, not per color: the same bot plays both colors across the two halves, and the question
    // the telemetry answers — "how often does *this bot* leave pieces en prise?" — is color-agnostic.
    val opponentTally = new HangTally
    val baseTally     = new HangTally

    val startTime = System.currentTimeMillis()

    // 1. Play games with Opponent as White and Base Bot as Black
    for i <- 0 until gamesPerColor do
      val diceRand = new Random(seed + i)
      val botRand  = new Random(1000 + i)
      simulateGame(opponentAlgo, baseAlgo, diceRand, botRand, startFen, opponentTally, baseTally) match
        case GameOutcome.Win(color) =>
          if color.isWhite then winsAsWhite += 1 else lossesAsWhite += 1
        case GameOutcome.Draw =>
          drawsAsWhite += 1

    // 2. Play games with Base Bot as White and Opponent as Black
    for i <- 0 until gamesPerColor do
      val diceRand = new Random(seed + i)
      val botRand  = new Random(2000 + i)
      simulateGame(baseAlgo, opponentAlgo, diceRand, botRand, startFen, baseTally, opponentTally) match
        case GameOutcome.Win(color) =>
          if color.isBlack then winsAsBlack += 1 else lossesAsBlack += 1
        case GameOutcome.Draw =>
          drawsAsBlack += 1

    val durationMs = System.currentTimeMillis() - startTime
    MatchResult(
      totalGames = gamesPerColor * 2,
      winsAsWhite = winsAsWhite,
      winsAsBlack = winsAsBlack,
      lossesAsWhite = lossesAsWhite,
      lossesAsBlack = lossesAsBlack,
      drawsAsWhite = drawsAsWhite,
      drawsAsBlack = drawsAsBlack,
      durationMs = durationMs,
      opponentHangs = opponentTally.snapshot,
      baseHangs = baseTally.snapshot
    )

  /** Package-private visibility (`private[bench]`) allows [[BotMatchRunnerSpec]] to verify individual turn executions,
    * random seed reproducibility, and the 50-move rule draw condition.
    */
  private[bench] def simulateGame(
      whiteBot: SearchAlgorithm,
      blackBot: SearchAlgorithm,
      diceRand: Random,
      botRand: Random,
      startState: GameState = FenParser.parse(StartFen).toOption.get,
      whiteTally: HangTally = new HangTally,
      blackTally: HangTally = new HangTally
  ): GameOutcome =
    var state                = startState
    var isGameOver           = false
    var outcome: GameOutcome = GameOutcome.Draw
    // Each side's hanging squares as of the end of its own last turn — the punished-hang check reads the victim's set
    // exactly one turn later, which is the only window in which "you left it en prise and I took it" is attributable.
    var whiteHanging = Bitboard.empty
    var blackHanging = Bitboard.empty

    while !isGameOver do
      if state.halfMoveClock >= 100 then
        isGameOver = true
        outcome = GameOutcome.Draw
      else
        // Roll 3 random dice
        val dice           = List.fill(3)(diceRand.nextInt(6) + 1)
        val stateWithDice  = state.withDicePool(dice)
        val mover          = state.activeColor
        val activeBot      = if mover.isWhite then whiteBot else blackBot
        val (next, winner) = playTurn(state, activeBot.findBestMove(stateWithDice, botRand))
        winner match
          case Some(color) =>
            outcome = GameOutcome.Win(color)
            isGameOver = true
          case None =>
            // Telemetry runs on completed (non-terminal) turns only: a king-capture turn ends the game above, so the
            // few pieces grabbed on the way to the king are not worth the extra bookkeeping.
            val moverTally    = if mover.isWhite then whiteTally else blackTally
            val victimTally   = if mover.isWhite then blackTally else whiteTally
            val victimHanging = if mover.isWhite then blackHanging else whiteHanging
            val victimBefore  = if mover.isWhite then state.blackPieces else state.whitePieces
            val victimAfter   = if mover.isWhite then next.blackPieces else next.whitePieces
            // The victim's pieces never move during the mover's turn, so anything of theirs that vanished was
            // captured — including en passant, whose victim square is exactly the vanished one.
            val punished = victimBefore & ~victimAfter & victimHanging
            if !punished.isEmpty then
              victimTally.punishedCaptures += punished.count
              victimTally.punishedMaterial += PieceSafety.materialOn(state, punished)
            val moverHanging = PieceSafety.hangingSquares(next, mover)
            moverTally.turns += 1
            if !moverHanging.isEmpty then
              moverTally.hangTurns += 1
              moverTally.hangingMaterial += PieceSafety.materialOn(next, moverHanging)
              if !(moverHanging & next.queens).isEmpty then moverTally.queenHangTurns += 1
            if mover.isWhite then whiteHanging = moverHanging else blackHanging = moverHanging
            state = next

    outcome

  /** Applies one bot turn to `state`, preserving the active color across the 1–3 micro-moves (Dice Chess rule).
    *
    * A `None` `scoredSeq` is a forced pass. Returns the resulting state and the winner when a King is captured (in
    * which case the turn is *not* ended, mirroring the engine's terminal handling). Shared by the untimed
    * [[simulateGame]] and the timed [[simulateTimedGame]] so move application and desync checks live in one place.
    */
  private[bench] def playTurn(state: GameState, scoredSeq: Option[ScoredSequence]): (GameState, Option[Color]) =
    scoredSeq match
      case None =>
        val next = state.endTurn()
        verifySync(next, "endTurn(pass)")
        (next, None)
      case Some(seq) =>
        var tempState             = state
        var winner: Option[Color] = None
        val iterator              = seq.moves.iterator
        while iterator.hasNext && winner.isEmpty do
          val m      = iterator.next()
          val target = tempState.mailbox(m.toSquare)
          if !target.isEmpty && target.pieceType == PieceType.King && target.color != tempState.activeColor then
            winner = Some(tempState.activeColor)
          tempState = tempState.makeMove(m)
          verifySync(tempState, s"${m.fromSquare.toNotation}${m.toSquare.toNotation}")
        if winner.isDefined then (tempState, winner)
        else
          val next = tempState.endTurn()
          verifySync(next, "endTurn")
          (next, None)

  /** In-process slack subtracted from the [[TimeManager]] budget: one uninterruptible rollout can overrun the deadline,
    * and this keeps that overrun off the simulated clock. Smaller than the JS API's worker buffer (no postMessage
    * here).
    */
  private val ArenaOverheadBufferMs: Long = 50L

  /** Deducts `elapsedMs` from a side's clock and, unless the side flagged, credits the Fischer `incrementMs`.
    *
    * @return
    *   the updated remaining time and whether the side ran out of time on this turn (flag-fall)
    */
  private[bench] def tickClock(remainingMs: Long, elapsedMs: Long, incrementMs: Long): (Long, Boolean) =
    val afterSpend = remainingMs - elapsedMs
    if afterSpend < 0 then (afterSpend, true)
    else (afterSpend + incrementMs, false)

  /** Plays a single game under a wall-clock [[TimeControl]].
    *
    * Each side starts with `tc.initialMs`. A [[TimeBudgetedSearch]] bot is given a per-turn deadline derived from
    * [[TimeManager]] and the side's remaining clock; a [[WebhookBot]] receives its full remaining clock and manages its
    * own thinking (see [[WebhookBot]] for why); O(1) bots simply move (their elapsed time is still charged, but is
    * negligible). After each turn the elapsed wall-clock is deducted and the increment credited; a side whose clock
    * goes negative loses on time — as does a webhook side whose delivery fails, since the platform's single-attempt,
    * no-retry semantics mean a failed delivery inevitably ends in a flag. Non-deterministic by nature (depends on
    * machine speed) — for measurement, not reproducible assertions.
    *
    * @param diceRandom
    *   source for the dice rolls
    * @param botRandom
    *   source handed to the time-budgeted search, kept separate so a varying rollout count never perturbs the dice
    * @param gameId
    *   identifier carried in webhook delivery envelopes; irrelevant to in-process bots
    */
  private[bench] def simulateTimedGame(
      whiteBot: SearchAlgorithm,
      blackBot: SearchAlgorithm,
      diceRandom: Random,
      botRandom: Random,
      tc: TimeControl,
      startState: GameState = FenParser.parse(StartFen).toOption.get,
      gameId: String = "arena",
      whiteTimeManager: TimeManager = TimeManager.default,
      blackTimeManager: TimeManager = TimeManager.default
  ): TimedGameResult =
    var state                           = startState
    var whiteRemaining                  = tc.initialMs
    var blackRemaining                  = tc.initialMs
    val latencies                       = scala.collection.mutable.ListBuffer.empty[(Color, Long)]
    var result: Option[TimedGameResult] = None

    while result.isEmpty do
      if state.halfMoveClock >= 100 then result = Some(TimedGameResult(GameOutcome.Draw, None, latencies.toList))
      else
        val dice          = List.fill(3)(diceRandom.nextInt(6) + 1)
        val stateWithDice = state.withDicePool(dice)
        val mover         = state.activeColor
        val isWhite       = mover.isWhite
        val activeBot     = if isWhite then whiteBot else blackBot
        val timeManager   = if isWhite then whiteTimeManager else blackTimeManager
        val remaining     = if isWhite then whiteRemaining else blackRemaining

        // Webhook moves are latency samples too: the wire is part of the deployed artifact being measured.
        val isTimedBot = activeBot match
          case _: WebhookBot | _: TimeBudgetedSearch => true
          case _                                     => false

        val startNanos                                   = System.nanoTime()
        val turn: Either[String, Option[ScoredSequence]] = activeBot match
          case wb: WebhookBot =>
            val oppRemaining = if isWhite then blackRemaining else whiteRemaining
            wb.chooseTurn(stateWithDice, gameId, mover, remaining, oppRemaining, tc)
          case tb: TimeBudgetedSearch =>
            val budgetMs =
              timeManager.budgetMs(ClockState(remaining, tc.incrementMs, state.fullMoveNumber), ArenaOverheadBufferMs)
            Right(tb.findBestMove(stateWithDice, startNanos + budgetMs * 1_000_000L, botRandom))
          case other => Right(other.findBestMove(stateWithDice, botRandom))
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L

        if isTimedBot then latencies += ((mover, elapsedMs))

        turn match
          case Left(reason) =>
            val side = if isWhite then "White" else "Black"
            System.err.println(s"[arena] $gameId: $side webhook delivery failed — forfeits on time ($reason)")
            result = Some(TimedGameResult(GameOutcome.Win(mover.opponent), Some(mover), latencies.toList))
          case Right(scored) =>
            val (newRemaining, flagged) = tickClock(remaining, elapsedMs, tc.incrementMs)
            if isWhite then whiteRemaining = newRemaining else blackRemaining = newRemaining

            if flagged then
              result = Some(TimedGameResult(GameOutcome.Win(mover.opponent), Some(mover), latencies.toList))
            else
              val (next, winner) = playTurn(state, scored)
              winner match
                case Some(color) => result = Some(TimedGameResult(GameOutcome.Win(color), None, latencies.toList))
                case None        => state = next

    result.get

  /** Resolves an arena bot id: an `http(s)://` id becomes a [[WebhookBot]] (secret from [[WebhookBot.SecretEnvVar]],
    * ownership handshake run immediately so a dead or misconfigured endpoint aborts the run before any game is played);
    * anything else is a [[BotRegistry]] lookup.
    */
  private def resolveOpponent(id: String): SearchAlgorithm =
    if WebhookBot.isWebhookId(id) then
      val bot = WebhookBot.fromEnv(id)
      bot.handshake().fold(reason => sys.error(s"webhook handshake with $id failed: $reason"), _ => bot)
    else BotRegistry.getAlgorithm(id).getOrElse(sys.error(s"Bot '$id' not found"))

  /** Runs a time-controlled match: `botUnderTestId` vs `baselineId`, `gamesPerColor` games on each side, under `tc`.
    *
    * Results are reported from the bot-under-test's perspective (its wins/losses/draws, the games it lost on time, and
    * the latency distribution of its own moves), which is what the #372 gate asks for.
    *
    * The latency distribution keeps only the bot-under-test's timed moves — [[TimeBudgetedSearch]] and [[WebhookBot]]
    * alike, filtered by side — so it stays meaningful even in a configurable timed-vs-timed run.
    *
    * Dice for game `i` come from `Random(seed + i)` in both color phases; the bot's tie-breaking offsets (`1000 + i` /
    * `2000 + i`) are unaffected by `seed`, so `seed == 42` (the default) reproduces this runner's pre-existing
    * behaviour exactly. Distinct seeds draw independent samples of the same matchup — run K shards with distinct seeds
    * spaced at least `gamesPerColor` apart (e.g. `0, 1000, 2000, ...`) and sum the tallies.
    *
    * When `sprtConfig` is supplied (#522), `gamesPerColor` becomes a CAP rather than a fixed count: the two games of
    * mirrored-seed pair `i` (bot as White, bot as Black, same dice draw) are played together as one
    * [[Sprt.Pentanomial]] observation, the LLR is updated, and the match stops as soon as [[Sprt.test]] returns a
    * verdict other than [[Sprt.Verdict.Continue]] — or after `gamesPerColor` pairs if it never does. Without
    * `sprtConfig`, every pair plays regardless of outcome, exactly as before; interleaving each pair's two games
    * (rather than all bot-White games followed by all bot-Black games) does not change the resulting tallies or latency
    * percentiles, since both are order-independent — see [[BotMatchRunnerSpec]] for the byte-identical check.
    */
  private[bench] def runTimedMatch(
      botUnderTestId: String,
      baselineId: String,
      setup: TimedMatchSetup
  ): TimedMatchResult =
    runTimedMatch(resolveOpponent(botUnderTestId), resolveOpponent(baselineId), setup)

  private def validateTimedMatchResume(resume: TimedMatchResume, gamesPerColor: Int): Unit =
    val invalidResumeIndex = resume.observations.zipWithIndex.collectFirst {
      case (observation, expected) if observation.index != expected => (expected, observation.index)
    }
    invalidResumeIndex.foreach { case (expected, actual) =>
      sys.error(s"Resume observations must be contiguous from index 0: expected $expected, found $actual")
    }
    if resume.durationMs < 0 then sys.error("Resume durationMs must be non-negative")
    if resume.observations.size > gamesPerColor then
      sys.error(s"Resume has ${resume.observations.size} pairs, above the configured cap of $gamesPerColor")

  private def timedBotScore(res: TimedGameResult, botColor: Color): Double = res.outcome match
    case GameOutcome.Draw                    => 0.5
    case GameOutcome.Win(c) if c == botColor => 1.0
    case GameOutcome.Win(_)                  => 0.0

  private def validatePairObservation(observation: PairObservation): Unit =
    val expectedWhite = timedBotScore(observation.whiteGame, Color.White)
    val expectedBlack = timedBotScore(observation.blackGame, Color.Black)
    val expectedBin   = math.round((expectedWhite + expectedBlack) * 2).toInt
    if observation.whiteScore != expectedWhite || observation.blackScore != expectedBlack || observation.bin != expectedBin
    then sys.error(s"Resume observation ${observation.index} scores/bin do not match its game outcomes")

  private def addPentanomialBin(pentanomial: Sprt.Pentanomial, observation: PairObservation): Sprt.Pentanomial =
    observation.bin match
      case 0     => pentanomial.copy(n0 = pentanomial.n0 + 1)
      case 1     => pentanomial.copy(n1 = pentanomial.n1 + 1)
      case 2     => pentanomial.copy(n2 = pentanomial.n2 + 1)
      case 3     => pentanomial.copy(n3 = pentanomial.n3 + 1)
      case 4     => pentanomial.copy(n4 = pentanomial.n4 + 1)
      case other => sys.error(s"Resume observation ${observation.index} has invalid pair bin $other")

  /** Algorithm-level overload of [[runTimedMatch]] — lets a test field an opponent (e.g. a [[WebhookBot]] pointed at a
    * mock endpoint) without registering it in the process-wide [[BotRegistry]] singleton, whose contents other suites
    * assert exactly. Both overloads take the same [[TimedMatchSetup]], so neither carries defaults of its own — which
    * is what removes the asymmetry that previously made adding a parameter change which alternative a call resolved to.
    */
  private[bench] def runTimedMatch(
      botAlgo: SearchAlgorithm,
      baseAlgo: SearchAlgorithm,
      setup: TimedMatchSetup
  ): TimedMatchResult =
    import setup.{
      baselineTimeManager,
      botTimeManager,
      gameSink,
      gamesPerColor,
      resume,
      seed,
      sprtConfig,
      startState,
      tc
    }
    var wins             = 0
    var losses           = 0
    var draws            = 0
    var botTimeouts      = 0 // games the bot under test lost on time
    var baselineTimeouts = 0
    var pairsPlayed      = 0
    var pentanomial      = Sprt.Pentanomial.Empty
    var sprtResult       = Option.empty[Sprt.Result]
    val latencies        = scala.collection.mutable.ListBuffer.empty[Long]
    val startTime        = System.currentTimeMillis()
    val resumedPairs     = resume.observations.size

    validateTimedMatchResume(resume, gamesPerColor)

    def record(res: TimedGameResult, botColor: Color): Unit =
      latencies ++= res.latenciesByColorMs.collect { case (color, ms) if color == botColor => ms }
      res.outcome match
        case GameOutcome.Draw                    => draws += 1
        case GameOutcome.Win(c) if c == botColor => wins += 1
        case GameOutcome.Win(_)                  => losses += 1
      res.flaggedColor.foreach { flagged =>
        if flagged == botColor then botTimeouts += 1 else baselineTimeouts += 1
      }

    def recordPair(observation: PairObservation): Unit =
      validatePairObservation(observation)
      record(observation.whiteGame, Color.White)
      record(observation.blackGame, Color.Black)
      pairsPlayed += 1
      pentanomial = addPentanomialBin(pentanomial, observation)

    resume.observations.foreach(recordPair)

    def updateSprt(): Unit =
      sprtResult = sprtConfig.map { cfg =>
        Sprt.test(pentanomial, Sprt.Trinomial.Empty, cfg.elo0, cfg.elo1, cfg.alpha, cfg.beta)
      }

    if pairsPlayed > 0 then updateSprt()

    var i        = pairsPlayed
    var continue = sprtResult.forall(_.verdict == Sprt.Verdict.Continue)
    while i < gamesPerColor && continue do
      val whiteRes =
        simulateTimedGame(
          botAlgo,
          baseAlgo,
          new Random(seed + i),
          new Random(1000 + i),
          tc,
          startState,
          s"arena-w-$i",
          botTimeManager,
          baselineTimeManager
        )
      val blackRes =
        simulateTimedGame(
          baseAlgo,
          botAlgo,
          new Random(seed + i),
          new Random(2000 + i),
          tc,
          startState,
          s"arena-b-$i",
          baselineTimeManager,
          botTimeManager
        )
      // The pair's two 0/½/1 scores sum and double to an exact integer 0..4 — one of Pentanomial's five bins.
      // Unconditional: [[PairVariance]] needs this histogram to state what the run could resolve, whether or not
      // SPRT stopping was requested.
      val bin =
        math.round((timedBotScore(whiteRes, Color.White) + timedBotScore(blackRes, Color.Black)) * 2).toInt
      val observation =
        PairObservation(
          i,
          bin,
          timedBotScore(whiteRes, Color.White),
          timedBotScore(blackRes, Color.Black),
          whiteRes,
          blackRes
        )
      recordPair(observation)
      gameSink.foreach(_(observation))

      updateSprt()
      if sprtResult.exists(_.verdict != Sprt.Verdict.Continue) then continue = false
      i += 1

    def reportedPolicyId(algorithm: SearchAlgorithm, manager: TimeManager): String = algorithm match
      case _: WebhookBot => "webhook-managed"
      case _             => manager.policy.id

    TimedMatchResult(
      timeControl = tc,
      totalGames = pairsPlayed * 2,
      wins = wins,
      losses = losses,
      draws = draws,
      botTimeouts = botTimeouts,
      baselineTimeouts = baselineTimeouts,
      latency = LatencyStats.from(latencies.toList),
      durationMs = resume.durationMs + Option
        .when(pairsPlayed > resumedPairs)(System.currentTimeMillis() - startTime)
        .getOrElse(0L),
      botTimePolicyId = reportedPolicyId(botAlgo, botTimeManager),
      baselineTimePolicyId = reportedPolicyId(baseAlgo, baselineTimeManager),
      sprt = sprtResult,
      pentanomial = pentanomial
    )

  private[bench] def printTimedSummary(botId: String, baselineId: String, results: List[TimedMatchResult]): Unit =
    println("================================================================================")
    println(s"🎲♟️  Time-Controlled Arena — $botId (bot under test) vs $baselineId")
    results.headOption.foreach(r =>
      println(s"Time policies: ${r.botTimePolicyId} (bot) vs ${r.baselineTimePolicyId} (baseline)")
    )
    println("================================================================================")
    println(
      f"${"Control"}%-10s | ${"Games"}%-5s | ${"Score"}%-7s | ${"W/L/D"}%-12s | ${"Timeout b/o"}%-12s | ${"p50/p95/p99/max ms"}%-24s | ${"Wall"}%-8s"
    )
    println("-" * 100)
    for r <- results do
      val wld  = s"${r.wins}/${r.losses}/${r.draws}"
      val to   = s"${r.botTimeouts}/${r.baselineTimeouts}"
      val lat  = s"${r.latency.p50Ms}/${r.latency.p95Ms}/${r.latency.p99Ms}/${r.latency.maxMs}"
      val wall = fmtRoot("%.1fs", r.durationMs / 1000.0)
      println(
        fmtRoot(
          "%-10s | %-5d | %6.1f%% | %-12s | %-12s | %-24s | %s",
          r.timeControl.label,
          r.totalGames,
          r.scorePercent,
          wld,
          to,
          lat,
          wall
        )
      )
      r.sprt.foreach { s =>
        println(
          fmtRoot(
            "  └ SPRT: llr=%.3f bounds=[%.3f, %.3f] verdict=%s observations=%d",
            s.llr,
            s.lower,
            s.upper,
            s.verdict,
            s.observations
          )
        )
      }
    println("================================================================================\n")

  /** Formats with [[java.util.Locale.ROOT]] instead of the JVM's default locale, so `%f` fields always use a `.`
    * decimal separator — the default locale renders it as `,` on e.g. German/Russian JVMs, breaking naive parsing of
    * the printed tables (#521).
    */
  private def fmtRoot(format: String, args: Any*): String = String.format(java.util.Locale.ROOT, format, args*)

  private val enableVerifySync =
    sys.props.get("dicechess.bench.verifySync").flatMap(_.toBooleanOption).getOrElse(false)

  private def verifySync(state: GameState, lastMove: String): Unit =
    if enableVerifySync then verifySyncInternal(state, lastMove)

  private def verifySyncInternal(state: GameState, lastMove: String): Unit =
    for i <- 0 until 64 do
      val sq    = Square.fromIndex(i)
      val piece = state.mailbox(sq)
      if piece.isEmpty then
        if state.whitePieces.contains(sq) then
          sys.error(
            s"Desync: mailbox empty but whitePieces set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if state.blackPieces.contains(sq) then
          sys.error(
            s"Desync: mailbox empty but blackPieces set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if state.pawns.contains(sq) then
          sys.error(
            s"Desync: mailbox empty but pawns set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if state.knights.contains(sq) then
          sys.error(
            s"Desync: mailbox empty but knights set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if state.bishops.contains(sq) then
          sys.error(
            s"Desync: mailbox empty but bishops set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if state.rooks.contains(sq) then
          sys.error(
            s"Desync: mailbox empty but rooks set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if state.queens.contains(sq) then
          sys.error(
            s"Desync: mailbox empty but queens set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if state.kings.contains(sq) then
          sys.error(
            s"Desync: mailbox empty but kings set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
      else
        val color = piece.color
        val pt    = piece.pieceType
        if color.isWhite then
          if !state.whitePieces.contains(sq) then
            sys.error(
              s"Desync: mailbox has white $pt but whitePieces not set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
            )
          if state.blackPieces.contains(sq) then
            sys.error(
              s"Desync: mailbox has white $pt but blackPieces set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
            )
        else
          if !state.blackPieces.contains(sq) then
            sys.error(
              s"Desync: mailbox has black $pt but blackPieces not set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
            )
          if state.whitePieces.contains(sq) then
            sys.error(
              s"Desync: mailbox has black $pt but whitePieces set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
            )

        if pt == PieceType.Pawn && !state.pawns.contains(sq) then
          sys.error(
            s"Desync: mailbox has Pawn but pawns not set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if pt == PieceType.Knight && !state.knights.contains(sq) then
          sys.error(
            s"Desync: mailbox has Knight but knights not set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if pt == PieceType.Bishop && !state.bishops.contains(sq) then
          sys.error(
            s"Desync: mailbox has Bishop but bishops not set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if pt == PieceType.Rook && !state.rooks.contains(sq) then
          sys.error(
            s"Desync: mailbox has Rook but rooks not set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if pt == PieceType.Queen && !state.queens.contains(sq) then
          sys.error(
            s"Desync: mailbox has Queen but queens not set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if pt == PieceType.King && !state.kings.contains(sq) then
          sys.error(
            s"Desync: mailbox has King but kings not set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )

        if pt != PieceType.Pawn && state.pawns.contains(sq) then
          sys.error(
            s"Desync: mailbox has $pt but pawns set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if pt != PieceType.Knight && state.knights.contains(sq) then
          sys.error(
            s"Desync: mailbox has $pt but knights set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if pt != PieceType.Bishop && state.bishops.contains(sq) then
          sys.error(
            s"Desync: mailbox has $pt but bishops set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if pt != PieceType.Rook && state.rooks.contains(sq) then
          sys.error(
            s"Desync: mailbox has $pt but rooks set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if pt != PieceType.Queen && state.queens.contains(sq) then
          sys.error(
            s"Desync: mailbox has $pt but queens set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )
        if pt != PieceType.King && state.kings.contains(sq) then
          sys.error(
            s"Desync: mailbox has $pt but kings set at ${sq.toNotation} (after $lastMove) in FEN: ${FenParser.serialize(state)}"
          )

  private def printSummaryTable(results: List[(BotInfo, MatchResult)]): Unit =
    println("\n📊 MATCH RESULTS OVERVIEW:")
    println(
      f"${"Opponent Bot"}%-20s | ${"Total"}%-5s | ${"Wins (W/B)"}%-12s | ${"Losses (W/B)"}%-12s | ${"Draws (W/B)"}%-12s | ${"Win Rate"}%-8s | ${"Time"}%-8s"
    )
    println("-" * 92)

    for (botInfo, r) <- results do
      val totalWins   = r.winsAsWhite + r.winsAsBlack
      val totalLosses = r.lossesAsWhite + r.lossesAsBlack
      val totalDraws  = r.drawsAsWhite + r.drawsAsBlack
      val winRate     = winRatePercent(totalWins, totalDraws, r.totalGames)
      val timeStr     = fmtRoot("%.2fs", r.durationMs / 1000.0)

      val winsStr   = s"$totalWins (${r.winsAsWhite}/${r.winsAsBlack})"
      val lossesStr = s"$totalLosses (${r.lossesAsWhite}/${r.lossesAsBlack})"
      val drawsStr  = s"$totalDraws (${r.drawsAsWhite}/${r.drawsAsBlack})"

      println(
        fmtRoot(
          "%-20s | %-5d | %-12s | %-12s | %-12s | %6.1f%% | %s",
          botInfo.name,
          r.totalGames,
          winsStr,
          lossesStr,
          drawsStr,
          winRate,
          timeStr
        )
      )
    println("================================================================================")
    printHangTelemetry(results)
    println("================================================================================\n")

  /** Per-game hang rates for both sides of every match — the blunder-shaped counterpart of the W/L/D overview. Material
    * is printed in pawns (centipawns / 100) because "1.8 pawns of material hung per game" is the unit a human reasons
    * in.
    */
  private def printHangTelemetry(results: List[(BotInfo, MatchResult)]): Unit =
    println("\n🛡️  HANG TELEMETRY (per game; hanging = own non-king piece attacked & undefended after own turn):")
    println(
      f"${"Bot"}%-20s | ${"Turns"}%-7s | ${"HangT"}%-7s | ${"QHang"}%-7s | ${"HungMat(p)"}%-10s | ${"Punished"}%-8s | ${"PunMat(p)"}%-9s"
    )
    println("-" * 92)
    for (botInfo, r) <- results do
      printHangRow(botInfo.name, r.opponentHangs, r.totalGames)
      printHangRow("  └ baseline", r.baseHangs, r.totalGames)

  private def printHangRow(label: String, s: HangStats, games: Int): Unit =
    val g = games.toDouble
    println(
      fmtRoot(
        "%-20s | %7.1f | %7.2f | %7.2f | %10.2f | %8.2f | %9.2f",
        label,
        s.turns / g,
        s.hangTurns / g,
        s.queenHangTurns / g,
        s.hangingMaterial / 100.0 / g,
        s.punishedCaptures / g,
        s.punishedMaterial / 100.0 / g
      )
    )

  /** Win-rate percentage counting draws as half a point — shared by the human table and [[arenaReportJson]]. */
  private def winRatePercent(wins: Int, draws: Int, totalGames: Int): Double =
    (wins.toDouble + 0.5 * draws) / totalGames * 100.0

  /** Builds the opt-in machine-readable report for an untimed arena run (#521), one entry per opponent. The schema is
    * additive-stable: existing fields keep their names and types across releases, new fields may be added.
    * ```json
    * {
    *   "kind": "untimed_arena",
    *   "baseBotId": "greedy",
    *   "baseBotName": "Greedy",
    *   "gamesPerColor": 50,
    *   "seed": 42,
    *   "startFen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    *   "matches": [
    *     {
    *       "opponentId": "aggressive",
    *       "opponentName": "Aggressive",
    *       "totalGames": 100,
    *       "wins": {"white": 30, "black": 28, "total": 58},
    *       "losses": {"white": 15, "black": 20, "total": 35},
    *       "draws": {"white": 5, "black": 2, "total": 7},
    *       "winRatePercent": 62.0,
    *       "durationMs": 12345,
    *       "hangTelemetry": {
    *         "opponent": {
    *           "turns": 500, "hangTurns": 40, "queenHangTurns": 3,
    *           "hangingMaterial": 3200, "punishedCaptures": 12, "punishedMaterial": 1100
    *         },
    *         "baseline": { "...": "same shape" }
    *       }
    *     }
    *   ]
    * }
    * ```
    */
  private[bench] def arenaReportJson(
      baseBotId: String,
      baseBotInfo: BotInfo,
      gamesPerColor: Int,
      seed: Long,
      startFen: String,
      results: List[(BotInfo, MatchResult)]
  ): Json =
    Json.obj(
      "kind"          -> Json.str("untimed_arena"),
      "baseBotId"     -> Json.str(baseBotId),
      "baseBotName"   -> Json.str(baseBotInfo.name),
      "gamesPerColor" -> Json.int(gamesPerColor),
      "seed"          -> Json.int(seed),
      "startFen"      -> Json.str(startFen),
      "matches"       -> Json.arr(results.map(matchResultJson)*)
    )

  private def matchResultJson(entry: (BotInfo, MatchResult)): Json =
    val (opponentInfo, r) = entry
    val totalWins         = r.winsAsWhite + r.winsAsBlack
    val totalLosses       = r.lossesAsWhite + r.lossesAsBlack
    val totalDraws        = r.drawsAsWhite + r.drawsAsBlack
    Json.obj(
      "opponentId"   -> Json.str(opponentInfo.id),
      "opponentName" -> Json.str(opponentInfo.name),
      "totalGames"   -> Json.int(r.totalGames),
      "wins"         -> Json.obj(
        "white" -> Json.int(r.winsAsWhite),
        "black" -> Json.int(r.winsAsBlack),
        "total" -> Json.int(totalWins)
      ),
      "losses" -> Json.obj(
        "white" -> Json.int(r.lossesAsWhite),
        "black" -> Json.int(r.lossesAsBlack),
        "total" -> Json.int(totalLosses)
      ),
      "draws" -> Json.obj(
        "white" -> Json.int(r.drawsAsWhite),
        "black" -> Json.int(r.drawsAsBlack),
        "total" -> Json.int(totalDraws)
      ),
      "winRatePercent" -> Json.num(winRatePercent(totalWins, totalDraws, r.totalGames)),
      "durationMs"     -> Json.int(r.durationMs),
      "hangTelemetry"  -> Json.obj(
        "opponent" -> hangStatsJson(r.opponentHangs),
        "baseline" -> hangStatsJson(r.baseHangs)
      )
    )

  private def hangStatsJson(s: HangStats): Json =
    Json.obj(
      "turns"            -> Json.int(s.turns),
      "hangTurns"        -> Json.int(s.hangTurns),
      "queenHangTurns"   -> Json.int(s.queenHangTurns),
      "hangingMaterial"  -> Json.int(s.hangingMaterial),
      "punishedCaptures" -> Json.int(s.punishedCaptures),
      "punishedMaterial" -> Json.int(s.punishedMaterial)
    )

  /** Builds the opt-in machine-readable report for a time-controlled arena run (#521), one entry per time control.
    * Schema (additive-stable, see [[arenaReportJson]]):
    * ```json
    * {
    *   "kind": "timed_arena",
    *   "botUnderTestId": "monte-carlo",
    *   "baselineId": "aggressive",
    *   "gamesPerColor": 10,
    *   "seed": 42,
    *   "results": [
    *     {
    *       "timeControl": {"label": "1+0", "initialMs": 60000, "incrementMs": 0},
    *       "totalGames": 20,
    *       "wins": 8, "losses": 10, "draws": 2,
    *       "winRatePercent": 45.0,
    *       "flagCounts": {"bot": 1, "baseline": 0},
    *       "latencyMs": {"count": 10, "p50": 120, "p95": 340, "p99": 400, "max": 420},
    *       "durationMs": 5321,
    *       "sprt": {
    *         "llr": 3.1, "lower": -2.944, "upper": 2.944, "verdict": "AcceptH1", "observations": 42
    *       }
    *     }
    *   ]
    * }
    * ```
    * `sprt` is `null` unless the run was given a [[SprtConfig]] (#522); `verdict` is one of `AcceptH1` / `AcceptH0` /
    * `Continue` (the latter only when the run was cut off at the `gamesPerColor` cap without a decisive LLR).
    */
  /** @param setup
    *   what the run varied, as flat key/value pairs, written under `"setup"`. Empty by default so existing reports keep
    *   their exact shape.
    *
    * The two side ids alone do not describe a run: a duel registers both sides under fixed ids, so two chunk files from
    * completely different experiments are byte-comparable in their identifying fields and distinguishable only by the
    * directory someone filed them in. Anything the run holds constant on one side and varies on the other — models,
    * feature sets, search kind, time policies — belongs here, or the archive cannot answer what produced the number.
    */
  private[bench] def timedReportJson(
      botUnderTestId: String,
      baselineId: String,
      gamesPerColor: Int,
      seed: Long,
      results: List[TimedMatchResult],
      setup: Map[String, String] = Map.empty
  ): Json =
    val fields = Seq(
      "kind"           -> Json.str("timed_arena"),
      "botUnderTestId" -> Json.str(botUnderTestId),
      "baselineId"     -> Json.str(baselineId),
      "gamesPerColor"  -> Json.int(gamesPerColor),
      "seed"           -> Json.int(seed)
    )
    val setupField =
      if setup.isEmpty then Seq.empty
      else Seq("setup" -> Json.obj(setup.toSeq.sortBy(_._1).map((k, v) => k -> Json.str(v))*))
    Json.obj((fields ++ setupField :+ ("results" -> Json.arr(results.map(timedMatchResultJson)*)))*)

  private def timedMatchResultJson(r: TimedMatchResult): Json =
    Json.obj(
      "timeControl" -> Json.obj(
        "label"       -> Json.str(r.timeControl.label),
        "initialMs"   -> Json.int(r.timeControl.initialMs),
        "incrementMs" -> Json.int(r.timeControl.incrementMs)
      ),
      "totalGames"     -> Json.int(r.totalGames),
      "wins"           -> Json.int(r.wins),
      "losses"         -> Json.int(r.losses),
      "draws"          -> Json.int(r.draws),
      "winRatePercent" -> Json.num(r.scorePercent),
      "flagCounts"     -> Json.obj("bot" -> Json.int(r.botTimeouts), "baseline" -> Json.int(r.baselineTimeouts)),
      "latencyMs"      -> Json.obj(
        "count" -> Json.int(r.latency.count),
        "p50"   -> Json.int(r.latency.p50Ms),
        "p95"   -> Json.int(r.latency.p95Ms),
        "p99"   -> Json.int(r.latency.p99Ms),
        "max"   -> Json.int(r.latency.maxMs)
      ),
      "durationMs"   -> Json.int(r.durationMs),
      "timePolicies" -> Json.obj(
        "bot"      -> Json.str(r.botTimePolicyId),
        "baseline" -> Json.str(r.baselineTimePolicyId)
      ),
      "sprt"       -> r.sprt.map(sprtResultJson).getOrElse(Json.JNull),
      "pairs"      -> pentanomialJson(r.pentanomial),
      "resolution" -> resolutionJson(r)
    )

  /** The mirrored-pair histogram (#508). Emitted raw so a run can be re-analysed later — the collapsed SPRT verdict
    * cannot be un-collapsed, and every earlier run's observations are gone for good.
    */
  private def pentanomialJson(p: Sprt.Pentanomial): Json =
    Json.obj(
      "n0"    -> Json.int(p.n0),
      "n1"    -> Json.int(p.n1),
      "n2"    -> Json.int(p.n2),
      "n3"    -> Json.int(p.n3),
      "n4"    -> Json.int(p.n4),
      "total" -> Json.int(p.total)
    )

  /** What difference this run could actually have resolved, and what it would take to resolve 2pp / 3pp — the point of
    * the exercise. Both binnings of the same games are reported so the value of pairing is visible rather than assumed.
    */
  private def resolutionJson(r: TimedMatchResult): Json =
    val c = PairVariance.compare(r.pentanomial, Sprt.Trinomial(r.losses.toLong, r.draws.toLong, r.wins.toLong))
    def num(v: Double): Json                    = if v.isFinite then Json.num(v) else Json.JNull
    def games(d: Double, byPair: Boolean): Json =
      c.gamesToResolve(d).map((p, g) => Json.int(if byPair then p else g)).getOrElse(Json.JNull)
    Json.obj(
      "pairScoreSd"               -> num(c.pairs.sd),
      "gameScoreSd"               -> num(c.games.sd),
      "ci95HalfWidthPercent"      -> num(c.pairs.ci95HalfWidthPercent),
      "varianceReduction"         -> num(c.varianceReduction),
      "gamesToResolve2pcPaired"   -> games(2.0, byPair = true),
      "gamesToResolve2pcUnpaired" -> games(2.0, byPair = false),
      "gamesToResolve3pcPaired"   -> games(3.0, byPair = true),
      "gamesToResolve3pcUnpaired" -> games(3.0, byPair = false)
    )

  private def sprtResultJson(r: Sprt.Result): Json =
    Json.obj(
      "llr"          -> Json.num(r.llr),
      "lower"        -> Json.num(r.lower),
      "upper"        -> Json.num(r.upper),
      "verdict"      -> Json.str(r.verdict.toString),
      "observations" -> Json.int(r.observations)
    )

  /** Writes rendered JSON to `path`, overwriting any existing file. */
  private[bench] def writeJsonReport(path: String, json: Json): Unit =
    Files.writeString(Path.of(path), Json.render(json))

case class MatchResult(
    totalGames: Int,
    winsAsWhite: Int,
    winsAsBlack: Int,
    lossesAsWhite: Int,
    lossesAsBlack: Int,
    drawsAsWhite: Int,
    drawsAsBlack: Int,
    durationMs: Long,
    opponentHangs: HangStats = HangStats.empty,
    baseHangs: HangStats = HangStats.empty
)

/** Aggregated hang telemetry for one algorithm over a match — [[PieceSafety.hangingSquares]] applied after each of the
  * algorithm's completed turns, whichever color it happened to play.
  *
  * This exists because W/L/D alone cannot answer *why* a bot loses: a search or model change meant to stop the bot
  * hanging its queen needs a blunder-shaped number to move, not just a win rate (see #494).
  *
  * @param turns
  *   completed (non-terminal) turns the algorithm played
  * @param hangTurns
  *   turns after which at least one of its pieces stood en prise
  * @param queenHangTurns
  *   turns after which its queen stood en prise
  * @param hangingMaterial
  *   centipawn sum of en-prise pieces over all turns (an 800-turn match can exceed `Int` comfortably only in theory,
  *   but `Long` makes overflow a non-question)
  * @param punishedCaptures
  *   pieces the algorithm left hanging that the opponent actually captured on the very next turn
  * @param punishedMaterial
  *   centipawn sum of those punished pieces
  */
final case class HangStats(
    turns: Int,
    hangTurns: Int,
    queenHangTurns: Int,
    hangingMaterial: Long,
    punishedCaptures: Int,
    punishedMaterial: Long
)

object HangStats:
  val empty: HangStats = HangStats(0, 0, 0, 0L, 0, 0L)

/** Mutable accumulator behind [[HangStats]]: one instance per algorithm per match, handed to
  * [[BotMatchRunner.simulateGame]] as the white or black tally depending on which color the algorithm plays in that
  * half. Plain vars, single-threaded by construction (the arena is sequential).
  */
final private[bench] class HangTally:
  var turns            = 0
  var hangTurns        = 0
  var queenHangTurns   = 0
  var hangingMaterial  = 0L
  var punishedCaptures = 0
  var punishedMaterial = 0L

  def snapshot: HangStats =
    HangStats(turns, hangTurns, queenHangTurns, hangingMaterial, punishedCaptures, punishedMaterial)

enum GameOutcome derives CanEqual:
  case Win(color: Color)
  case Draw

/** A wall-clock time control: an initial budget plus a per-turn Fischer increment, both in milliseconds. */
final case class TimeControl(initialMs: Long, incrementMs: Long):
  /** Compact label such as `"60s"` (sudden death) or `"180s+2s"` (Fischer). */
  def label: String =
    val base = initialMs / 1000
    if incrementMs == 0 then s"${base}s" else s"${base}s+${incrementMs / 1000}s"

object TimeControl:
  /** Builds a control from whole seconds, e.g. `ofSeconds(60, 0)` or `ofSeconds(180, 2)`. */
  def ofSeconds(initialSec: Int, incrementSec: Int): TimeControl =
    TimeControl(initialSec * 1000L, incrementSec * 1000L)

/** Outcome of one timed game, plus the side that flagged (if any) and each timed move's `(side, think-time-ms)`, so the
  * match aggregator can keep only the bot-under-test's latencies even in a timed-vs-timed run.
  */
final case class TimedGameResult(
    outcome: GameOutcome,
    flaggedColor: Option[Color],
    latenciesByColorMs: List[(Color, Long)]
)

/** Nearest-rank latency percentiles (ms) over a set of move think times. */
final case class LatencyStats(count: Int, p50Ms: Long, p95Ms: Long, p99Ms: Long, maxMs: Long)

object LatencyStats:
  val empty: LatencyStats = LatencyStats(0, 0L, 0L, 0L, 0L)

  /** Nearest-rank percentiles over `samples`; returns [[empty]] for no samples. */
  def from(samples: Seq[Long]): LatencyStats =
    if samples.isEmpty then empty
    else
      val sorted                   = samples.sorted.toVector
      def percentile(p: Int): Long =
        val rank = math.ceil(p / 100.0 * sorted.size).toInt
        sorted(math.min(sorted.size, math.max(1, rank)) - 1)
      LatencyStats(sorted.size, percentile(50), percentile(95), percentile(99), sorted.last)

/** Configuration for optional SPRT stopping in [[BotMatchRunner.runTimedMatch]] (#522): hypothesis bounds `elo0` ("not
  * stronger by more than this") and `elo1` ("stronger by at least this"), and the type-I/II error rates `alpha`/`beta`.
  * See [[Sprt.test]] for what each parameter feeds.
  */
final case class SprtConfig(elo0: Double, elo1: Double, alpha: Double, beta: Double)

/** Aggregated result of a time-controlled match, from the bot-under-test's perspective. `sprt` is populated only when
  * [[BotMatchRunner.runTimedMatch]] was given a [[SprtConfig]].
  */
/** Everything about how to run a timed match except who plays it — the two [[BotMatchRunner.runTimedMatch]] overloads
  * differ only in how they resolve the players, so this carries the whole of the rest.
  *
  * Grouped rather than passed as a positional tail for a reason beyond the parameter count: defaults now live in one
  * place instead of on a single overload. Scala permits them on only one alternative, and that asymmetry silently
  * changed which alternative a call resolved to the moment a parameter was added (a `WebhookBot` argument started
  * binding to a `String`), which a shared setup object makes impossible.
  *
  * @param gamesPerColor
  *   mirrored pairs to play — a CAP rather than a fixed count when `sprtConfig` is set
  * @param seed
  *   dice for pair `i` come from `Random(seed + i)` in both colour phases; `42` reproduces this runner's original
  *   behaviour exactly
  * @param gameSink
  *   optional per-pair record (#508), so a run's raw observations survive it; `None` costs nothing
  * @param botTimeManager
  *   manager used for the bot under test in both colour phases
  * @param baselineTimeManager
  *   manager used for the baseline in both colour phases
  * @param resume
  *   durable observations from pair indices `[0, n)` and their prior wall time; the next pair remains `n`, preserving
  *   the uninterrupted run's dice and tie-breaking random streams
  */
final case class TimedMatchSetup(
    gamesPerColor: Int,
    tc: TimeControl,
    startState: GameState = FenParser.parse(BotMatchRunner.StartFen).toOption.get,
    seed: Long = 42L,
    sprtConfig: Option[SprtConfig] = None,
    gameSink: Option[PairObservation => Unit] = None,
    botTimeManager: TimeManager = TimeManager.default,
    baselineTimeManager: TimeManager = TimeManager.default,
    resume: TimedMatchResume = TimedMatchResume.empty
)

/** Completed mirrored-pair observations restored before [[BotMatchRunner.runTimedMatch]] continues a durable run. The
  * observations retain the original pair indices, outcomes, flag falls, and raw latency samples; `durationMs` carries
  * wall time already spent by earlier processes.
  */
final case class TimedMatchResume(observations: Vector[PairObservation], durationMs: Long)

object TimedMatchResume:
  val empty: TimedMatchResume = TimedMatchResume(Vector.empty, 0L)

final case class TimedMatchResult(
    timeControl: TimeControl,
    totalGames: Int,
    wins: Int,
    losses: Int,
    draws: Int,
    botTimeouts: Int,
    baselineTimeouts: Int,
    latency: LatencyStats,
    durationMs: Long,
    botTimePolicyId: String = TimePolicies.default.id,
    baselineTimePolicyId: String = TimePolicies.default.id,
    sprt: Option[Sprt.Result] = None,
    /** Mirrored-pair score histogram, always populated by [[BotMatchRunner.runTimedMatch]] (#508). Consumed by
      * [[PairVariance]] to state what difference the run could actually have resolved.
      */
    pentanomial: Sprt.Pentanomial = Sprt.Pentanomial.Empty
):
  /** Win-rate of the bot under test, counting draws as half a point. */
  def scorePercent: Double = (wins + 0.5 * draws) / totalGames * 100.0
