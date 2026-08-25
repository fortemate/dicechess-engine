package dicechess.engine.bench

import java.nio.file.Path
import scala.collection.mutable.ArrayBuffer

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
  * verdict (if configured), and all comparison-critical setup parameters so archived runs cannot be misidentified. A
  * single-control run can also use `--checkpoint`: the runner atomically saves every completed mirrored pair and
  * resumes it on the next invocation without changing any random stream or latency percentile.
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

  final private case class OutputSetup(jsonPath: Option[String], checkpointPath: Option[String])

  final private case class MatchSetup(
      games: Int,
      presets: String,
      seed: Long,
      sprtConfig: Option[SprtConfig],
      output: OutputSetup,
      timePolicy: TimePolicy
  )

  private val checkpointPathOpt: Opts[Option[String]] =
    Opts
      .option[String]("checkpoint", help = "Durable checkpoint path (single time control only; resumes if present)")
      .orNone

  def main(args: Array[String]): Unit =
    ArenaOptions.runCommand(command, args)

  private[bench] val command: Command[Unit] = Command(
    name = "OnnxDepthDuelRunner",
    header = "Dice Chess Bot Arena - ONNX Depth Duel Runner (depth 3 vs depth 2)"
  ) {
    import ArenaOptions.*
    val searchOpts =
      (modelPathOpt, featuresOpt("rich"), candidateLimitOpt()).mapN(SearchSetup.apply)
    val outputOpts = (jsonPathOpt, checkpointPathOpt).mapN(OutputSetup.apply)
    val matchOpts  =
      (
        gamesOpt(10),
        presetsOpt("3+2,10+10"),
        seedOpt(),
        sprtConfigOpt,
        outputOpts,
        timePolicyOpt("time-policy", "depth 3 and depth 2 searches")
      ).mapN(MatchSetup.apply)
    (searchOpts, matchOpts).mapN(runDuel)
  }

  private def runDuel(search: SearchSetup, matchSetup: MatchSetup): Unit =
    val SearchSetup(modelPath, featureSet, candidateLimit)               = search
    val MatchSetup(games, presets, seed, sprtConfig, output, timePolicy) = matchSetup
    val OutputSetup(jsonPath, checkpointPath)                            = output
    val extractFeatures                                                  = ArenaOptions.extractFeatures(featureSet)
    val controls                                                         = TimedArenaRunner.parsePresets(presets)
    val normalizedModelPath = Path.of(modelPath).toAbsolutePath.normalize.toString
    val modelSha256         = DepthDuelCheckpoint.sha256(normalizedModelPath).fold(error => sys.error(error), identity)
    val normalizedJsonPath  = jsonPath.map(path => Path.of(path).toAbsolutePath.normalize)
    val normalizedCheckpointPath = checkpointPath.map(path => Path.of(path).toAbsolutePath.normalize)
    if checkpointPath.nonEmpty && controls.size != 1 then
      sys.error("--checkpoint requires exactly one time control; run each control with its own checkpoint file")
    if normalizedCheckpointPath.exists(path => normalizedJsonPath.contains(path)) then
      sys.error("--checkpoint and --json must use different paths")

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
          val checkpointIdentity = DepthDuelCheckpointIdentity(
            normalizedModelPath,
            modelSha256,
            featureSet,
            candidateLimit,
            ChallengerDepth,
            DefenderDepth,
            seed,
            timePolicy.id,
            tc,
            sprtConfig
          )
          val resumed = checkpointPath
            .map(path =>
              DepthDuelCheckpoint.load(path, checkpointIdentity, games).fold(error => sys.error(error), identity)
            )
            .getOrElse(TimedMatchResume.empty)
          checkpointPath.foreach { path =>
            if resumed.observations.nonEmpty then
              println(s"Resuming ${resumed.observations.size} completed mirrored pairs from $path")
          }

          val observations   = ArrayBuffer.from(resumed.observations)
          val resumedAt      = System.currentTimeMillis()
          val checkpointSink = checkpointPath.map { path => (observation: PairObservation) =>
            observations += observation
            val progress = TimedMatchResume(
              observations.toVector,
              resumed.durationMs + System.currentTimeMillis() - resumedAt
            )
            DepthDuelCheckpoint
              .save(path, checkpointIdentity, games, progress)
              .fold(error => sys.error(error), identity)
          }
          val result = BotMatchRunner.runTimedMatch(
            challenger,
            defender,
            TimedMatchSetup(
              gamesPerColor = games,
              tc = tc,
              seed = seed,
              sprtConfig = sprtConfig,
              botTimeManager = TimeManager(timePolicy),
              baselineTimeManager = TimeManager(timePolicy),
              gameSink = checkpointSink,
              resume = resumed
            )
          )
          checkpointPath.foreach { path =>
            val completed = TimedMatchResume(observations.toVector, result.durationMs)
            DepthDuelCheckpoint
              .save(path, checkpointIdentity, games, completed)
              .fold(error => sys.error(error), identity)
          }
          result
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
                "modelSha256"     -> modelSha256,
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
