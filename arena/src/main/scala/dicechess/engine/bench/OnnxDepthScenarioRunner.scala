package dicechess.engine.bench

import scala.util.Using

import cats.implicits.*
import com.monovore.decline.*

import dicechess.engine.search.ExpectimaxConfig

/** Deterministic scenario-suite evaluator comparing an ONNX model at expectimax **depth 3 vs depth 2**.
  *
  * Parent: #60. Evaluates expectimax depth 3 (candidate) against depth 2 (baseline) using the existing
  * [[SearchFixtureCatalog]] and [[SearchEvaluation]] infrastructure. Loads one ONNX model twice (once for each depth)
  * with the same feature extractor and candidate limit, and attributes every difference to a stable scenario and seed.
  *
  * Usage:
  * `sbt 'arena/runMain dicechess.engine.bench.OnnxDepthScenarioRunner model.onnx --features rich --candidate-limit 8 --fixtures arena/src/main/resources/search-evaluation/core-v1.json --json report.json'`
  */
object OnnxDepthScenarioRunner:

  /** Fixed registry IDs for the two search depths evaluated inside one JVM. */
  private[bench] val CandidateId = "onnx-depth-3"
  private[bench] val BaselineId  = "onnx-depth-2"

  private val ArenaDifficulty = 5

  private val fixturePathOpt: Opts[Option[String]] =
    Opts.option[String]("fixtures", help = "Path to a search-evaluation fixture JSON file").orNone

  def main(args: Array[String]): Unit =
    ArenaOptions.runCommand(command, args)

  private[bench] val command: Command[Unit] = Command(
    name = "OnnxDepthScenarioRunner",
    header = "Dice Chess deterministic expectimax depth-3 vs depth-2 scenario evaluation"
  ) {
    import ArenaOptions.*
    (
      modelPathOpt,
      featuresOpt("rich"),
      candidateLimitOpt(8),
      fixturePathOpt,
      jsonPathOpt
    ).mapN(runEvaluation)
  }

  private def runEvaluation(
      modelPath: String,
      featureSet: String,
      candidateLimit: Int,
      fixturePath: Option[String],
      jsonPath: Option[String]
  ): Unit =
    val fixtures        = SearchFixtureCatalog.load(fixturePath).fold(error => sys.error(error), identity)
    val candidateConfig = configForDepth(candidateLimit, 3)
    val baselineConfig  = configForDepth(candidateLimit, 2)

    println(
      s"Depth scenario evaluation: $modelPath (features=$featureSet, K=$candidateLimit) depth=3 vs depth=2"
    )

    Using.resource(register(CandidateId, modelPath, featureSet, candidateConfig, "Expectimax Depth 3")) { _ =>
      Using.resource(register(BaselineId, modelPath, featureSet, baselineConfig, "Expectimax Depth 2")) { _ =>
        val report = SearchEvaluation
          .run(fixtures, BaselineId, CandidateId)
          .fold(error => sys.error(error), identity)

        SearchEvaluation.printHuman(report)

        jsonPath.foreach { path =>
          val json = toJson(report, modelPath, featureSet, candidateLimit)
          BotMatchRunner.writeJsonReport(path, json)
        }
      }
    }

  private def register(
      id: String,
      modelPath: String,
      featureSet: String,
      config: ExpectimaxConfig,
      nameSuffix: String
  ) =
    OnnxArenaBot.register(
      id,
      OnnxArenaBot.ModelSpec(modelPath, featureSet),
      SearchKind.Expectimax,
      config,
      ArenaDifficulty,
      s"deterministic scenario evaluator for $nameSuffix over $modelPath"
    )

  private[bench] def configForDepth(candidateLimit: Int, searchDepth: Int): ExpectimaxConfig =
    ExpectimaxConfig(candidateLimit = candidateLimit, searchDepth = searchDepth)

  private[bench] def toJson(
      report: SearchEvaluationReport,
      modelPath: String,
      featureSet: String,
      candidateLimit: Int
  ): Json =
    SearchEvaluation.toJson(report) match
      case Json.JObj(fields) =>
        Json.JObj(
          fields ++ List(
            "modelPath"      -> Json.str(modelPath),
            "featureSet"     -> Json.str(featureSet),
            "candidateLimit" -> Json.int(candidateLimit),
            "candidateDepth" -> Json.int(3),
            "baselineDepth"  -> Json.int(2)
          )
        )
      case other => other
