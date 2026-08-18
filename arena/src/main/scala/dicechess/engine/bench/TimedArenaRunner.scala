package dicechess.engine.bench

import com.monovore.decline.*
import cats.implicits.*
import dicechess.engine.search.TimeManager

/** Executable entry point for the time-controlled arena (the #372 gate).
  *
  * Plays a time-budgeted bot against a baseline across one or more controls and prints win-rate, flag-rate, and the
  * latency distribution per control. Unlike a hardcoded harness, every parameter is an argument.
  *
  * Arguments (all optional, positional):
  *   1. `botUnderTest` — id of the bot being evaluated (default `monte-carlo`)
  *   1. `baseline` — id of the opponent (default `aggressive`)
  *   1. `gamesPerColor` — games on each side, so total is `2 ×` this (default `10`)
  *   1. `presets` — comma-separated chess-clock controls in `minutes[+incrementSeconds]` notation, e.g. `1+0,3+2,10+10`
  *      (1-minute bullet; 3 min + 2 s; 10 min + 10 s) (default `1+0,3+2,10+10`)
  *   1. `seed` — dice seed (default `42`, which reproduces this runner's original behaviour). Distinct seeds draw
  *      independent samples of the same matchup — run K shards with distinct seeds spaced at least `gamesPerColor`
  *      apart (e.g. `0, 1000, 2000, ...`) on separate cores or machines, and sum the tallies for K times the games in
  *      the same wall-clock time.
  *
  * Either bot position also accepts an `http(s)://` URL: the opponent then lives behind the platform's webhook protocol
  * and is driven by [[WebhookBot]] (#526) — deliveries are HMAC-signed with the secret from the
  * `DICECHESS_WEBHOOK_SECRET` env var (required for webhook opponents; never passed on the command line), an ownership
  * handshake runs before the first game, and the endpoint gets its full remaining clock per turn.
  *
  * An optional `--json <path>` flag (anywhere in the arguments) additionally writes the machine-readable report from
  * [[BotMatchRunner.timedReportJson]] to `path` — the human-readable table is always printed regardless.
  *
  * An optional `--sprt <elo0>,<elo1>,<alpha>,<beta>` flag (e.g. `--sprt 0,20,0.05,0.05`) turns on SPRT stopping (#522):
  * `gamesPerColor` becomes a cap instead of a fixed count, and each control stops as soon as the mirrored-pair evidence
  * is decisive — see [[BotMatchRunner.runTimedMatch]].
  *
  * Example:
  * `sbt 'arena/runMain dicechess.engine.bench.TimedArenaRunner --bot monte-carlo --baseline aggressive --games 10 --presets 1+0,3+2,10+10'`
  */
object TimedArenaRunner:
  def main(args: Array[String]): Unit =
    ArenaOptions.runCommand(command, args)

  private[bench] val command: Command[Unit] = Command(
    name = "TimedArenaRunner",
    header = "Dice Chess Bot Arena - JVM Timed Match Runner"
  ) {
    import ArenaOptions.*
    (
      botUnderTestOpt("monte-carlo"),
      baselineOpt("aggressive"),
      gamesOpt(10),
      presetsOpt(),
      seedOpt(),
      jsonPathOpt,
      sprtConfigOpt,
      timePolicyOpt("bot-time-policy", "bot under test"),
      timePolicyOpt("baseline-time-policy", "baseline")
    ).mapN { (botId, baseline, games, presets, seed, jsonPath, sprtConfig, botPolicy, baselinePolicy) =>
      val timeControls = TimedArenaRunner.parsePresets(presets)
      val results      =
        timeControls.map(tc =>
          BotMatchRunner.runTimedMatch(
            botId,
            baseline,
            TimedMatchSetup(
              games,
              tc,
              seed = seed,
              sprtConfig = sprtConfig,
              botTimeManager = TimeManager(botPolicy),
              baselineTimeManager = TimeManager(baselinePolicy)
            )
          )
        )
      BotMatchRunner.printTimedSummary(botId, baseline, results)
      jsonPath.foreach { path =>
        BotMatchRunner.writeJsonReport(path, BotMatchRunner.timedReportJson(botId, baseline, games, seed, results))
      }
    }
  }

  /** Parses comma-separated chess-clock presets in `minutes[+incrementSeconds]` notation (e.g. `1+0`, `3+2`, `10+10`)
    * into [[TimeControl]]s. The base is a positive integer number of minutes; the increment a non-negative number of
    * seconds (default 0). Requires at least one preset.
    */
  private[bench] def parsePresets(spec: String): List[TimeControl] =
    val controls = spec.split(',').toList.map(_.trim).filter(_.nonEmpty).map { token =>
      token.split('+').map(_.trim) match
        case Array(base, inc) => TimeControl(baseMinutes(token, base) * 60_000L, incrementSeconds(token, inc) * 1000L)
        case Array(base)      => TimeControl(baseMinutes(token, base) * 60_000L, 0L)
        case _ => sys.error(s"Invalid time-control preset '$token' (expected 'minutes' or 'minutes+incrementSeconds')")
    }
    if controls.isEmpty then sys.error("At least one time-control preset is required")
    controls

  private def baseMinutes(token: String, value: String): Long =
    val minutes = wholeNumber(token, value)
    if minutes <= 0 then sys.error(s"Invalid time-control preset '$token': base minutes must be > 0")
    minutes

  private def incrementSeconds(token: String, value: String): Long =
    val seconds = wholeNumber(token, value)
    if seconds < 0 then sys.error(s"Invalid time-control preset '$token': increment seconds must be >= 0")
    seconds

  private def wholeNumber(token: String, value: String): Long =
    value.toLongOption.getOrElse(sys.error(s"Invalid time-control preset '$token': '$value' is not an integer"))
