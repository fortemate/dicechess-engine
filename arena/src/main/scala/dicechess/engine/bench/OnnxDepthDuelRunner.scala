package dicechess.engine.bench

import com.monovore.decline.*
import cats.implicits.*

import dicechess.engine.search.{ExpectimaxConfig, OnnxExpectimaxSearch, TimeManager, TimePolicy}

/** Duels an ONNX model against itself at depth 3 (challenger) vs depth 2 (defender), under a clock.
  *
  * Holds the model and evaluator configuration constant while varying only `ExpectimaxConfig.searchDepth`. Creates
  * search sessions for depth 3 and depth 2 using the same ONNX model file, feature extractor, candidate limit, and time
  * policy.
  *
  * Reports through [[BotMatchRunner.runTimedMatch]], so a `--json` run carries the mirrored-pair histogram, SPRT
  * verdict (if configured), and all comparison-critical setup parameters so archived runs cannot be misidentified.
  *
  * Usage:
  * `sbt 'arena/runMain dicechess.engine.bench.OnnxDepthDuelRunner <model.onnx> --features rich --games 10 --candidate-limit 8 --presets 3+2,10+10'`
  */
object OnnxDepthDuelRunner:

  private[bench] val ChallengerDepth = 3
  private[bench] val DefenderDepth   = 2

  private[bench] val ChallengerId = "depth-3"
  private[bench] val DefenderId   = "depth-2"

  final private case class SearchSetup(modelPath: String, featureSet: String, candidateLimit: Int)

  final private case class MatchSetup(
      games: Int,
      presets: String,
      seed: Long,
      sprtConfig: Option[SprtConfig],
      jsonPath: Option[String],
      timePolicy: TimePolicy
  )

  def main(args: Array[String]): Unit =
    ArenaOptions.runCommand(command, args)

  private[bench] val command: Command[Unit] = Command(
    name = "OnnxDepthDuelRunner",
    header = "Dice Chess Bot Arena - ONNX Depth Duel Runner (depth 3 vs depth 2)"
  ) {
    import ArenaOptions.*
    val searchOpts =
      (modelPathOpt, featuresOpt("rich"), candidateLimitOpt()).mapN(SearchSetup.apply)
    val matchOpts =
      (
        gamesOpt(10),
        presetsOpt("3+2,10+10"),
        seedOpt(),
        sprtConfigOpt,
        jsonPathOpt,
        timePolicyOpt("time-policy", "depth 3 and depth 2 searches")
      ).mapN(MatchSetup.apply)
    (searchOpts, matchOpts).mapN(runDuel)
  }

  private def runDuel(search: SearchSetup, matchSetup: MatchSetup): Unit =
    val SearchSetup(modelPath, featureSet, candidateLimit)                 = search
    val MatchSetup(games, presets, seed, sprtConfig, jsonPath, timePolicy) = matchSetup
    val extractFeatures                                                    = ArenaOptions.extractFeatures(featureSet)
    val controls                                                           = TimedArenaRunner.parsePresets(presets)

    println(
      s"Depth duel: $modelPath (features=$featureSet, K=$candidateLimit) depth $ChallengerDepth vs depth $DefenderDepth, " +
        s"$games mirrored pairs per control, controls=$presets, seed=$seed" +
        sprtConfig.fold("")(_ => " (SPRT stopping on)")
    )

    // Two sessions over the SAME file rather than one shared session: each side owns its own ONNX session
    // exactly as a deployed bot does, so neither gains from a warmed cache the other filled.
    val challenger = new OnnxExpectimaxSearch(
      modelPath,
      configForDepth(candidateLimit, ChallengerDepth),
      extractFeatures
    )
    try
      val defender = new OnnxExpectimaxSearch(
        modelPath,
        configForDepth(candidateLimit, DefenderDepth),
        extractFeatures
      )
      try
        val results = controls.map { tc =>
          BotMatchRunner.runTimedMatch(
            challenger,
            defender,
            TimedMatchSetup(
              gamesPerColor = games,
              tc = tc,
              seed = seed,
              sprtConfig = sprtConfig,
              botTimeManager = TimeManager(timePolicy),
              baselineTimeManager = TimeManager(timePolicy)
            )
          )
        }

        BotMatchRunner.printTimedSummary(ChallengerId, DefenderId, results)
        jsonPath.foreach { path =>
          BotMatchRunner.writeJsonReport(
            path,
            BotMatchRunner.timedReportJson(
              ChallengerId,
              DefenderId,
              games,
              seed,
              results,
              Map(
                "modelPath"       -> modelPath,
                "features"        -> featureSet,
                "candidateLimit"  -> candidateLimit.toString,
                "challengerDepth" -> ChallengerDepth.toString,
                "defenderDepth"   -> DefenderDepth.toString,
                "timePolicy"      -> timePolicy.id
              )
            )
          )
          println(s"Wrote $path")
        }
      finally defender.close()
    finally challenger.close()

  private[bench] def configForDepth(candidateLimit: Int, searchDepth: Int): ExpectimaxConfig =
    ExpectimaxConfig(candidateLimit = candidateLimit, searchDepth = searchDepth)
