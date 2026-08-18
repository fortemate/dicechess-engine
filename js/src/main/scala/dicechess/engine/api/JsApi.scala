package dicechess.engine.api

import dicechess.engine.domain.*
import dicechess.engine.movegen.LegalMovesFilter
import dicechess.engine.search.{
  BotInfo,
  BotRegistry,
  ClockState,
  MonteCarloConfig,
  MonteCarloEquity,
  OpeningBookBot,
  OpeningBookParser,
  TimeBudgetedSearch,
  TimeManager,
  TimePolicies
}
import scala.util.Random
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.JSConverters.*

/** JavaScript API for the Dice Chess Engine.
  *
  * This object is exported to JavaScript as `DiceChess`. It provides high-level functions for FEN parsing, move
  * generation, and validation, optimized for UI consumption.
  */
@JSExportTopLevel("DiceChess")
object JsApi:

  /** Returns all available bots (search algorithms) supported by the engine.
    *
    * @return
    *   An array of bot metadata objects.
    */
  @JSExport
  @JSExportTopLevel("getAvailableBots")
  def getAvailableBots(): js.Array[js.Dynamic] =
    BotRegistry.availableBots.map { bot =>
      js.Dynamic.literal(
        id = bot.id,
        name = bot.name,
        description = bot.description,
        difficulty = bot.difficulty,
        isExperimental = bot.isExperimental
      )
    }.toJSArray

  /** Returns stable ids of all built-in time-management policies, so a host can validate persisted configuration or
    * build a selector without duplicating the engine's policy registry.
    */
  @JSExport
  @JSExportTopLevel("getAvailableTimePolicies")
  def getAvailableTimePolicies(): js.Array[String] = TimePolicies.available.map(_.id).toJSArray

  /** Returns all legal moves as an array of UCI strings (e.g., ["e2e4", "e7e8q"]).
    *
    * Useful for pawn promotion logic on the frontend to know exactly which pieces are allowed.
    *
    * @param dfen
    *   The position in DiceChess Forsyth-Edwards Notation (including the dice pool).
    * @return
    *   An array of full UCI move strings.
    */
  @JSExport
  @JSExportTopLevel("getLegalUciMoves")
  def getLegalUciMoves(dfen: String): js.Array[String] =
    if Option(dfen).isEmpty then js.Array()
    else
      FenParser.parse(dfen) match
        case Left(_)      => js.Array()
        case Right(state) =>
          val allMoves = LegalMovesFilter.filterMaximalMoves(state)
          allMoves.map(_.toUci).toJSArray

  /** Returns the piece type associated with a dice roll.
    *
    * @param dice
    *   The dice roll (1-6).
    * @return
    *   The piece notation (p, n, b, r, q, k) or null if invalid.
    */
  @JSExport
  @JSExportTopLevel("getPieceFromDice")
  def getPieceFromDice(dice: Int): String | Null =
    PieceType.fromDice(dice).map(_.asNotation).orNull

  /** Registers a runtime bot that consults an opening book before delegating to an existing bot.
    *
    * `tsvString` is an [[dicechess.engine.search.OpeningBook]] TSV string (canonical key, tab, comma-separated moves);
    * the new bot wraps `baseBotId` with an [[dicechess.engine.search.OpeningBookBot]] and is exposed under `newBotId`
    * for subsequent [[getBestMove]] calls. This lets a host (a private extension, the analytics backend, the trainer)
    * attach a data-driven opening book at runtime without shipping the book inside the engine. See
    * [[dicechess.engine.search.BotRegistry.registerCustomBot]] for the single-threaded-setup expectation.
    *
    * @return
    *   `true` when the base bot exists and the TSV parses (the bot is registered); `false` otherwise (no change).
    */
  @JSExport
  @JSExportTopLevel("registerOpeningBookBot")
  def registerOpeningBookBot(
      tsvString: String,
      baseBotId: String,
      newBotId: String,
      newBotName: String
  ): Boolean =
    // Exported to JS, so any argument may arrive as `null`; reject rather than throw on eager parsing.
    if Option(tsvString).isEmpty || Option(baseBotId).isEmpty
      || Option(newBotId).isEmpty || Option(newBotName).isEmpty
    then false
    else
      (BotRegistry.getAlgorithm(baseBotId), OpeningBookParser.parse(tsvString)) match
        case (Some(baseBot), Right(book)) =>
          val difficulty =
            BotRegistry.availableBots.find(_.id.equalsIgnoreCase(baseBotId)).map(_.difficulty).getOrElse(5)
          val info = BotInfo(
            id = newBotId,
            name = newBotName,
            description = s"Opening-book bot decorating '$baseBotId'.",
            difficulty = difficulty,
            isExperimental = true
          )
          // `decorate` preserves the base bot's time-budget capability (a plain bot stays plain).
          BotRegistry.registerCustomBot(info, OpeningBookBot.decorate(baseBot, book))
          true
        case _ => false

  /** Web Worker transport + rollout-granularity slack subtracted from the time-managed budget (see
    * [[dicechess.engine.search.TimeManager.budgetMs]]). The engine runs inside a worker, so a `postMessage` round-trip
    * plus one uninterruptible rollout can push the realised think time past the deadline; this margin keeps that
    * overrun off the game clock.
    */
  private val WorkerOverheadBufferMs: Long = 150L

  /** Upper bound on a budget (ms) before converting it to a nanosecond deadline, so `budgetMs * 1_000_000` cannot
    * overflow `Long`. Unreachable for any realistic clock (it is ~292 years); a pure guard against absurd input.
    */
  private val MaxDeadlineBudgetMs: Long = Long.MaxValue / 1_000_000L

  /** Computes the best sequence of micro-moves for the given position.
    *
    * @param dfen
    *   The position in DiceChess Forsyth-Edwards Notation.
    * @param options
    *   Optional configuration. Two ways to bound a time-budgeted bot (a [[dicechess.engine.search.TimeBudgetedSearch]]
    *   such as Monte-Carlo); other algorithms ignore both:
    *   ```json
    *   { "algorithm": "monte-carlo", "clock": { "remainingMs": 600000, "incrementMs": 10000 } }
    *   ```
    *   - `clock` (preferred): the live game clock; the engine derives the per-turn budget via
    *     [[dicechess.engine.search.TimeManager]], handling sudden-death and Fischer increment. Fields: `remainingMs`
    *     (required), `incrementMs` (default 0), `moveNumber` (default: the DFEN full-move), and an optional explicit
    *     `movesToGo`. Optional `timePolicy` selects a built-in policy by id; an absent or unknown id uses
    *     [[dicechess.engine.search.TimePolicies.default]].
    *   - `timeBudgetMs` (advanced override): a precomputed per-move budget in ms, bypassing time management. Used by
    *     tests and arena tuning; ignored when `clock` is present.
    * @return
    *   An object with `moves`, `score`, `timeTakenMs`, and `budgetMs` (the time-managed allocation actually used, or 0
    *   when the search was unbounded).
    */
  @JSExport
  @JSExportTopLevel("getBestMove")
  def getBestMove(dfen: String, options: js.UndefOr[js.Dynamic]): js.Dynamic =
    if Option(dfen).isEmpty then js.Dynamic.literal(moves = js.Array(), score = 0, timeTakenMs = 0, budgetMs = 0)
    else
      val searchAlgo = resolveAlgorithm(options)

      FenParser.parse(dfen) match
        case Left(_)      => js.Dynamic.literal(moves = js.Array(), score = 0, timeTakenMs = 0, budgetMs = 0)
        case Right(state) =>
          val start    = System.currentTimeMillis()
          val budgetMs = searchAlgo match
            case _: TimeBudgetedSearch =>
              clockOption(options, state.fullMoveNumber)
                .map(clock => timeManagerOption(options).budgetMs(clock, WorkerOverheadBufferMs))
                .orElse(intOption(options, "timeBudgetMs").filter(_ > 0).map(_.toLong))
                .getOrElse(0L)
            case _ => 0L

          val scored = searchAlgo match
            case tb: TimeBudgetedSearch if budgetMs > 0 =>
              val deadlineNanos = System.nanoTime() + math.min(budgetMs, MaxDeadlineBudgetMs) * 1_000_000L
              tb.findBestMove(state, deadlineNanos, new Random())
            case _ =>
              searchAlgo.findBestMove(state)

          scored match
            case None =>
              js.Dynamic.literal(
                moves = js.Array(),
                score = 0,
                timeTakenMs = (System.currentTimeMillis() - start).toInt,
                budgetMs = math.min(budgetMs, Int.MaxValue.toLong).toInt
              )
            case Some(scoredSeq) =>
              val jsMoves = scoredSeq.moves.map { m =>
                js.Dynamic.literal(
                  from = m.fromSquare.toNotation,
                  to = m.toSquare.toNotation,
                  promotion = m.promotionPieceType.map(_.asNotation).orUndefined
                )
              }.toJSArray
              js.Dynamic.literal(
                moves = jsMoves,
                score = scoredSeq.score,
                timeTakenMs = (System.currentTimeMillis() - start).toInt,
                budgetMs = math.min(budgetMs, Int.MaxValue.toLong).toInt
              )

  /** Applies a move to the given DFEN and returns the resulting state.
    *
    * @param dfen
    *   The starting board state in DiceChess Forsyth-Edwards Notation (DFEN).
    * @param from
    *   The algebraic notation of the starting square.
    * @param to
    *   The algebraic notation of the target square.
    * @param promotion
    *   The optional piece type to promote to (e.g. "q").
    * @return
    *   The updated DFEN string after applying the move, or `undefined` if the move is pseudo-illegal.
    */
  @JSExport
  @JSExportTopLevel("applyMove")
  def applyMove(dfen: String, from: String, to: String, promotion: js.UndefOr[String]): js.UndefOr[String] =
    dicechess.engine.EngineFacade.applyMove(dfen, from, to, promotion)

  /** Explicitly ends the current turn to mark a clear boundary between players in a multi-micro-move sequence.
    *
    * Frontend consumers should call this when a player has exhausted their dice or finished their sequence. This
    * function guarantees the game state is correctly finalized for the next player by cleaning up stale en-passant
    * targets from the previous turn, clearing the dice pool, and advancing the turn markers (color toggle, move
    * counts). It serves as the single entrypoint for advancing the DFEN state between player turns.
    *
    * @param dfen
    *   The current board state in DiceChess Forsyth-Edwards Notation.
    * @return
    *   The updated DFEN string finalized for the next player, or `undefined` if invalid.
    */
  @JSExport
  @JSExportTopLevel("endTurn")
  def endTurn(dfen: String): js.UndefOr[String] =
    dicechess.engine.EngineFacade.endTurn(dfen)

  /** Determines whether the bot should offer a double before its dice roll.
    */
  @JSExport
  @JSExportTopLevel("shouldBotOfferDouble")
  def shouldBotOfferDouble(dfen: String, currentStake: Int, options: js.UndefOr[js.Dynamic]): Boolean =
    if Option(dfen).isEmpty then false
    else
      val searchAlgo = resolveAlgorithm(options)
      FenParser.parse(dfen) match
        case Left(_)      => false
        case Right(state) => searchAlgo.shouldOfferDouble(state, currentStake)

  /** Determines whether the bot should accept (Take) or decline (Drop) a double from the opponent.
    */
  @JSExport
  @JSExportTopLevel("shouldBotAcceptDouble")
  def shouldBotAcceptDouble(dfen: String, currentStake: Int, options: js.UndefOr[js.Dynamic]): Boolean =
    if Option(dfen).isEmpty then false
    else
      val searchAlgo = resolveAlgorithm(options)
      FenParser.parse(dfen) match
        case Left(_)      => false
        case Right(state) => searchAlgo.shouldAcceptDouble(state, currentStake)

  /** Determines whether the bot should offer a draw.
    */
  @JSExport
  @JSExportTopLevel("shouldBotOfferDraw")
  def shouldBotOfferDraw(dfen: String, options: js.UndefOr[js.Dynamic]): Boolean =
    if Option(dfen).isEmpty then false
    else
      val searchAlgo = resolveAlgorithm(options)
      FenParser.parse(dfen) match
        case Left(_)      => false
        case Right(state) => searchAlgo.shouldOfferDraw(state)

  /** Determines whether the bot should accept a draw offered by the opponent.
    */
  @JSExport
  @JSExportTopLevel("shouldBotAcceptDraw")
  def shouldBotAcceptDraw(dfen: String, options: js.UndefOr[js.Dynamic]): Boolean =
    if Option(dfen).isEmpty then false
    else
      val searchAlgo = resolveAlgorithm(options)
      FenParser.parse(dfen) match
        case Left(_)      => false
        case Right(state) => searchAlgo.shouldAcceptDraw(state)

  /** Estimates pre-roll equity for `dfen` with a Rao-Blackwellized Monte-Carlo rollout.
    *
    * Designed for **progressive / anytime** client use: call repeatedly in batches (varying `seed`) and pool the
    * per-batch results in JS — each call returns the `rollouts` count and `standardError` needed to combine batches, so
    * the browser can show a quick estimate and keep tightening it off the main thread without loading the backend.
    *
    * @param dfen
    *   the position in DFEN.
    * @param options
    *   optional `{ rollouts?, maxPlies?, seed? }`. `seed` makes a batch deterministic (omit for a fresh source).
    * @return
    *   `{ whiteWin, blackWin, undecided, rollouts, standardError, varianceReductionVsVanilla }`; a neutral
    *   `undecided = 1` result for an invalid DFEN.
    */
  @JSExport
  @JSExportTopLevel("estimateEquity")
  def estimateEquity(dfen: String, options: js.UndefOr[js.Dynamic]): js.Dynamic =
    if Option(dfen).isEmpty then equityFallback
    else
      FenParser.parse(dfen) match
        case Left(_)      => equityFallback
        case Right(state) =>
          val config = MonteCarloConfig(
            rollouts = intOption(options, "rollouts").getOrElse(DefaultEquityRollouts),
            maxPlies = intOption(options, "maxPlies").getOrElse(DefaultEquityMaxPlies)
          )
          val rng = intOption(options, "seed").map(s => new Random(s.toLong)).getOrElse(new Random())
          val est = MonteCarloEquity.estimate(state, config, rng)
          js.Dynamic.literal(
            whiteWin = est.whiteWin,
            blackWin = est.blackWin,
            undecided = est.undecided,
            rollouts = est.rollouts,
            standardError = est.standardError,
            varianceReductionVsVanilla = est.varianceReductionVsVanilla
          )

  /** Returns the canonical key of `dfen` — the DFEN of the position's symmetry-class representative, shared by all
    * symmetry-equivalent positions. Useful as a cache key (e.g. for client-side equity estimates).
    *
    * @return
    *   the canonical DFEN, or `undefined` for an invalid DFEN.
    */
  @JSExport
  @JSExportTopLevel("canonicalKey")
  def canonicalKey(dfen: String): js.UndefOr[String] =
    if Option(dfen).isEmpty then js.undefined
    else FenParser.parse(dfen).toOption.map(Symmetry.canonicalKey).orUndefined

  private val DefaultEquityRollouts = 200
  private val DefaultEquityMaxPlies = 60

  private val equityFallback: js.Dynamic =
    js.Dynamic.literal(
      whiteWin = 0.0,
      blackWin = 0.0,
      undecided = 1.0,
      rollouts = 0,
      standardError = 0.0,
      varianceReductionVsVanilla = 0.0
    )

  private def intOption(options: js.UndefOr[js.Dynamic], key: String): Option[Int] =
    options.toOption
      .filter(Option(_).isDefined)
      .flatMap { opt =>
        val v = opt.selectDynamic(key)
        if scala.scalajs.js.typeOf(v) == "number" then Some(v.asInstanceOf[Double].toInt)
        else None
      }

  private def stringOption(options: js.UndefOr[js.Dynamic], key: String): Option[String] =
    options.toOption
      .filter(Option(_).isDefined)
      .flatMap { opt =>
        val v = opt.selectDynamic(key)
        if scala.scalajs.js.typeOf(v) == "string" then Some(v.asInstanceOf[String])
        else None
      }

  private def timeManagerOption(options: js.UndefOr[js.Dynamic]): TimeManager =
    stringOption(options, "timePolicy")
      .flatMap(TimePolicies.get)
      .map(TimeManager(_))
      .getOrElse(TimeManager.default)

  /** Reads a finite numeric field from a JS object as a [[Double]], or `None` when absent, not a number, or non-finite.
    *
    * `NaN` and `±Infinity` are JS numbers, so they are rejected explicitly here to keep them out of [[ClockState]] and
    * the deadline arithmetic (an `Infinity` clock would otherwise overflow the nanosecond deadline).
    */
  private def numField(obj: js.Dynamic, key: String): Option[Double] =
    val v = obj.selectDynamic(key)
    if scala.scalajs.js.typeOf(v) == "number" then
      val d = v.asInstanceOf[Double]
      if d.isNaN || d.isInfinite then None else Some(d)
    else None

  /** Reads an optional `clock` object from the options into a [[dicechess.engine.search.ClockState]].
    *
    * Returns `None` unless `clock` is a non-null object carrying a numeric `remainingMs`. `moveNumber` defaults to the
    * position's full-move number so the caller need not duplicate it.
    */
  private def clockOption(options: js.UndefOr[js.Dynamic], fallbackMoveNumber: Int): Option[ClockState] =
    options.toOption
      .filter(Option(_).isDefined)
      .flatMap { opt =>
        val clk = opt.selectDynamic("clock")
        // typeof null is "object", so guard the null case via Option(...) before reading any field.
        Option(clk.asInstanceOf[AnyRef])
          .filter(_ => scala.scalajs.js.typeOf(clk) == "object")
          .map(_ => clk)
      }
      .flatMap { clk =>
        numField(clk, "remainingMs").map { remaining =>
          ClockState(
            remainingMs = math.max(0L, remaining.toLong),
            incrementMs = math.max(0L, numField(clk, "incrementMs").map(_.toLong).getOrElse(0L)),
            moveNumber = numField(clk, "moveNumber").map(_.toInt).getOrElse(fallbackMoveNumber),
            movesToGo = numField(clk, "movesToGo").map(_.toInt)
          )
        }
      }

  private def resolveAlgorithm(options: js.UndefOr[js.Dynamic]): dicechess.engine.search.SearchAlgorithm =
    val algoName = options.toOption
      .filter(Option(_).isDefined)
      .flatMap { opt =>
        val alg = opt.selectDynamic("algorithm")
        if scala.scalajs.js.typeOf(alg) == "string" then Some(alg.asInstanceOf[String])
        else None
      }
      .getOrElse("greedy")
    BotRegistry.getAlgorithm(algoName).getOrElse(BotRegistry.defaultAlgorithm)
