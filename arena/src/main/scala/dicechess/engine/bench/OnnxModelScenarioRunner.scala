package dicechess.engine.bench

import scala.util.Using

import cats.implicits.*
import com.monovore.decline.*

import dicechess.engine.search.{ExpectimaxConfig, RootRescoreModel, TranspositionTable}

/** Deterministic scenario-suite evaluator comparing two ONNX leaf models under identical expectimax search.
  *
  * Unlike [[OnnxModelDuelRunner]], this runner has no clock and plays no games. It evaluates both models on the stable
  * [[SearchFixtureCatalog]] scenarios and seeds through [[SearchEvaluation]], so every tactical improvement or
  * regression is attributable to a named position. Both sides use expectimax depth 2 and share the same candidate
  * limit, optional root-rescore model, leaf-model pre-ranking setting, and transposition-table capacity. The two
  * root-rescore sessions and transposition tables are separate mutable resources despite their identical parameters.
  *
  * Usage:
  * `sbt 'arena/runMain dicechess.engine.bench.OnnxModelScenarioRunner challenger.onnx defender.onnx --features rich --baseline-features rich --candidate-limit 8 --rescore-model kcp.onnx --rescore-features kcp --rescore-weight 0.5 --pre-rank-with-model --tt-capacity 262144 --fixtures arena/src/main/resources/search-evaluation/core-v1.json --json report.json'`
  */
object OnnxModelScenarioRunner:

  /** Stable, distinct registry IDs make repeated in-process runs replace registrations instead of leaking bots. */
  private[bench] val ChallengerId = "onnx-scenario-challenger"
  private[bench] val DefenderId   = "onnx-scenario-defender"

  private val ArenaDifficulty = 5
  private val SearchDepth     = 2

  private val fixturePathOpt: Opts[Option[String]] =
    Opts.option[String]("fixtures", help = "Path to a search-evaluation fixture JSON file").orNone

  def main(args: Array[String]): Unit =
    ArenaOptions.runCommand(command, args)

  private[bench] val command: Command[Unit] = Command(
    name = "OnnxModelScenarioRunner",
    header = "Dice Chess deterministic ONNX model-vs-model scenario evaluation"
  ) {
    import ArenaOptions.*
    (
      challengerModelPathOpt,
      defenderModelPathOpt,
      featuresOpt("rich"),
      baselineFeaturesOpt(),
      candidateLimitOpt(8),
      rescoreModelPathOpt,
      optionalRescoreFeaturesOpt,
      optionalRescoreWeightOpt,
      preRankWithModelOpt,
      optionalTtCapacityOpt,
      fixturePathOpt,
      jsonPathOpt
    ).mapN(OnnxModelScenarioConfig.apply).map(runEvaluation)
  }

  private[bench] def runEvaluation(config: OnnxModelScenarioConfig): Unit =
    import config.*
    if rescoreModel.isEmpty && (rescoreFeatures.isDefined || rescoreWeight.isDefined) then
      sys.error("--rescore-features and --rescore-weight require --rescore-model")

    val fixtures               = SearchFixtureCatalog.load(fixturePath).fold(error => sys.error(error), identity)
    val challengerSha256       = hashModel(challengerModel)
    val defenderSha256         = hashModel(defenderModel)
    val activeRescoreFeatures  = rescoreFeatures.getOrElse("kcp")
    val activeRescoreWeight    = rescoreWeight.getOrElse(0.5)
    val rootRescoreModelSha256 = rescoreModel.map(hashModel)
    val searchConfig           = ExpectimaxConfig(candidateLimit = candidateLimit, searchDepth = SearchDepth)

    println(
      s"Model scenario evaluation: $challengerModel (features=$challengerFeatures) vs " +
        s"$defenderModel (features=$defenderFeatures), search=expectimax, depth=$SearchDepth, K=$candidateLimit"
    )

    def rootRescore: Option[RootRescoreModel] =
      rescoreModel.map(path =>
        RootRescoreModel(path, ArenaOptions.extractFeatures(activeRescoreFeatures), activeRescoreWeight)
      )

    def table: Option[TranspositionTable] = ttCapacity.map(new TranspositionTable(_))

    Using.resource(
      register(
        ChallengerId,
        challengerModel,
        challengerFeatures,
        searchConfig,
        "challenger",
        rootRescore,
        table,
        preRankWithModel
      )
    ) { _ =>
      Using.resource(
        register(
          DefenderId,
          defenderModel,
          defenderFeatures,
          searchConfig,
          "defender",
          rootRescore,
          table,
          preRankWithModel
        )
      ) { _ =>
        val report = SearchEvaluation
          .run(fixtures, DefenderId, ChallengerId)
          .fold(error => sys.error(error), identity)

        SearchEvaluation.printHuman(report)
        jsonPath.foreach { path =>
          val json = toJson(
            report,
            config,
            challengerSha256,
            defenderSha256,
            rootRescoreModelSha256,
            activeRescoreFeatures,
            activeRescoreWeight
          )
          BotMatchRunner.writeJsonReport(path, json)
        }
      }
    }

  private def register(
      id: String,
      modelPath: String,
      featureSet: String,
      config: ExpectimaxConfig,
      role: String,
      rootRescore: Option[RootRescoreModel],
      tt: Option[TranspositionTable],
      preRankWithModel: Boolean
  ) =
    OnnxArenaBot.register(
      id = id,
      modelPath = modelPath,
      featureSet = featureSet,
      searchKind = SearchKind.Expectimax,
      config = config,
      difficulty = ArenaDifficulty,
      description = s"deterministic model scenario $role over $modelPath",
      rootRescore = rootRescore,
      preRankWithModel = preRankWithModel,
      tt = tt
    )

  private def hashModel(path: String): String =
    DepthDuelCheckpoint.sha256(path).fold(error => sys.error(error), identity)

  private[bench] def toJson(
      report: SearchEvaluationReport,
      config: OnnxModelScenarioConfig,
      challengerSha256: String,
      defenderSha256: String,
      rootRescoreModelSha256: Option[String],
      activeRescoreFeatures: String,
      activeRescoreWeight: Double
  ): Json =
    import config.*
    SearchEvaluation.toJson(report) match
      case Json.JObj(fields) =>
        Json.JObj(
          fields ++ List(
            "search"          -> Json.str("expectimax"),
            "searchDepth"     -> Json.int(SearchDepth),
            "candidateLimit"  -> Json.int(candidateLimit),
            "challengerModel" -> modelJson(challengerModel, challengerSha256, challengerFeatures),
            "defenderModel"   -> modelJson(defenderModel, defenderSha256, defenderFeatures),
            "rootRescore"     -> rescoreModel
              .zip(rootRescoreModelSha256)
              .headOption
              .map((path, sha256) =>
                Json.obj(
                  "path"     -> Json.str(path),
                  "sha256"   -> Json.str(sha256),
                  "features" -> Json.str(activeRescoreFeatures),
                  "weight"   -> Json.num(activeRescoreWeight)
                )
              )
              .getOrElse(Json.JNull),
            "preRankWithModel"      -> Json.bool(preRankWithModel),
            "transpositionCapacity" -> ttCapacity.map(value => Json.int(value)).getOrElse(Json.JNull)
          )
        )
      case other => other

  private def modelJson(path: String, sha256: String, features: String): Json =
    Json.obj(
      "path"     -> Json.str(path),
      "sha256"   -> Json.str(sha256),
      "features" -> Json.str(features)
    )

final private[bench] case class OnnxModelScenarioConfig(
    challengerModel: String,
    defenderModel: String,
    challengerFeatures: String,
    defenderFeatures: String,
    candidateLimit: Int,
    rescoreModel: Option[String],
    rescoreFeatures: Option[String],
    rescoreWeight: Option[Double],
    preRankWithModel: Boolean,
    ttCapacity: Option[Int],
    fixturePath: Option[String],
    jsonPath: Option[String]
)
