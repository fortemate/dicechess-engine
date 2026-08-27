package dicechess.engine.bench

import scala.util.Using

import com.monovore.decline.*
import cats.implicits.*

import dicechess.engine.search.{
  ExpectimaxConfig,
  RootRescoreModel,
  RootSearchStats,
  TimeManager,
  TimePolicy,
  TranspositionTable
}

/** Time-controlled arena for **two ONNX models against each other**.
  *
  * [[OnnxTimedArenaRunner]] puts one model on the clock against a *registry* bot, which is the right shape for asking
  * "is this model stronger than the hand-written baseline". It cannot answer the other question a training programme
  * runs into — "is model B stronger than model A" — because a registry bot is the only thing it accepts on the opposing
  * side. Serving the second model over the webhook protocol to reach [[TimedArenaRunner]] would work, but it buys
  * nothing except an HTTP hop and a container image: [[OnnxArenaBot]] already turns a model into a registry id. This
  * runner registers *both* sides and hands their ids to the same [[BotMatchRunner.runTimedMatch]] every other timed
  * measurement uses, so the measurement logic stays shared and only the wiring lives here.
  *
  * Both sides get the same search — `--search` and, at 2 ply, the same [[ExpectimaxConfig]] (`--candidate-limit`),
  * optional root rescorer, model pre-ranker, and per-side transposition-table capacity. That is the point: holding the
  * search identical isolates whatever the duel is actually varying. Give the two sides different widths or hybrid
  * components and the result stops being a statement about the leaf models.
  *
  * `--search oneply` exists because the depth has to match the bot under discussion (#610). An expensive evaluator can
  * be viable at one ply and hopeless at two — a KCP-featured model does not finish a single game at 2 ply — so a
  * 2-ply-only harness simply cannot reproduce such a bot. It also decides whether a time-policy comparison means
  * anything: measured at 5+3, `empirical-v1` and `legacy-linear-v1` spend the same time per move at K=24 and K=48 (the
  * search ends long before either budget) and only diverge once the budget actually binds.
  *
  * ⚠️ Timed results are **not** machine-independent — a slower box means fewer candidates per move inside the same
  * budget. Compare the two models only against each other, on one box, in one session; never against a number measured
  * elsewhere. This is the single easiest way to misread this harness.
  *
  * Usage:
  * `STATS_OUT=stats.tsv sbt 'arena/runMain dicechess.engine.bench.OnnxModelDuelRunner challenger.onnx defender.onnx --features rich --baseline-features rich --games 100 --candidate-limit 8 --rescore-model kcp.onnx --rescore-features kcp --rescore-weight 0.5 --pre-rank-with-model --tt-capacity 262144 --presets 3+2 --seed 0 --json out.json'`
  */
object OnnxModelDuelRunner:

  /** Ids the two sides are registered under. Fixed rather than derived from the file names because [[BotRegistry]] is a
    * process-wide singleton keyed by id: a stable pair means a second run in the same JVM replaces the previous
    * registration instead of leaving stale bots behind.
    */
  private[bench] val ChallengerId = "onnx-challenger"
  private[bench] val DefenderId   = "onnx-defender"

  /** Registry presentation metadata, not a search parameter. Both sides share one value so nothing downstream can
    * mistake either for the harder bot — unlike [[OnnxTimedArenaRunner]], there is no baseline bot to inherit from.
    */
  private val ArenaDifficulty = 5

  def main(args: Array[String]): Unit =
    ArenaOptions.runCommand(command, args)

  private[bench] val command: Command[Unit] = Command(
    name = "OnnxModelDuelRunner",
    header = "Dice Chess Bot Arena - ONNX model vs ONNX model, on a clock"
  ) {
    import ArenaOptions.*
    (
      challengerModelPathOpt,
      defenderModelPathOpt,
      featuresOpt("rich"),
      baselineFeaturesOpt(),
      gamesOpt(10),
      optionalCandidateLimitOpt,
      rescoreModelPathOpt,
      optionalRescoreFeaturesOpt,
      optionalRescoreWeightOpt,
      preRankWithModelOpt,
      optionalTtCapacityOpt,
      // One control by default, unlike the other timed runners: a duel of two ONNX models costs roughly twice a
      // model-vs-baseline run, and the question this runner exists for is normally posed at a single control.
      presetsOpt("3+2"),
      seedOpt(),
      jsonPathOpt,
      sprtConfigOpt,
      timePolicyOpt("challenger-time-policy", "challenger"),
      timePolicyOpt("defender-time-policy", "defender"),
      // Shared by both sides on purpose: holding the search identical is what makes a result a statement about the
      // models or the time policies. A per-side search would be a different runner with a different premise.
      searchOpt
    ).mapN(OnnxModelDuelConfig.apply).map(runDuel)
  }

  private[bench] def runDuel(config: OnnxModelDuelConfig): Unit =
    import config.*
    val onePly = searchKind == SearchKind.OnePly

    // Rejected rather than ignored. A candidate limit is an ExpectimaxConfig parameter with no meaning at one ply, and
    // silently dropping it would let a "one-ply at K=48" run be archived and later compared against a "one-ply at K=24"
    // run as though the width had been a variable. A measurement harness must not accept a knob it will not use.
    if onePly && candidateLimit.isDefined then
      sys.error("--candidate-limit has no meaning with --search oneply: there is no candidate pre-ranking to limit")
    val hybridOptionsPresent =
      rescoreModel.isDefined || rescoreFeatures.isDefined || rescoreWeight.isDefined || preRankWithModel || ttCapacity.isDefined
    if onePly && hybridOptionsPresent then
      sys.error(
        "root rescoring, model pre-ranking, and transposition tables require --search expectimax"
      )
    if rescoreModel.isEmpty && (rescoreFeatures.isDefined || rescoreWeight.isDefined) then
      sys.error("--rescore-features and --rescore-weight require --rescore-model")

    // Parsed before either model is loaded: a bad preset should cost nothing, and loading two onnxruntime sessions
    // only to reject the argument that follows them is a slow way to report a typo.
    val controls              = TimedArenaRunner.parsePresets(presets)
    val configObj             = ExpectimaxConfig(candidateLimit.getOrElse(ExpectimaxConfig().candidateLimit))
    val width                 = if onePly then "n/a" else configObj.candidateLimit.toString
    val activeRescoreFeatures = rescoreFeatures.getOrElse("kcp")
    val activeRescoreWeight   = rescoreWeight.getOrElse(0.5)
    val statsPath             = sys.env.get("STATS_OUT")

    // The search kind is in the header because every chunk log and archived result is read later by someone who needs
    // to know which depth produced the number.
    println(
      s"Timed model duel: $challengerModel (features=$challengerFeatures) vs " +
        s"$defenderModel (features=$defenderFeatures), search=${searchKind.id}, K=$width, controls=$presets, seed=$seed"
    )

    val statsWriter             = statsPath.map(path => new java.io.PrintWriter(new java.io.FileWriter(path), true))
    @volatile var currentPreset = ""
    def sink(side: String): RootSearchStats => Unit =
      stats => statsWriter.foreach(_.println(s"$currentPreset\t$side\t$stats"))

    def rootRescore: Option[RootRescoreModel] =
      rescoreModel.map(path =>
        RootRescoreModel(path, ArenaOptions.extractFeatures(activeRescoreFeatures), activeRescoreWeight)
      )

    def table: Option[TranspositionTable] = ttCapacity.map(new TranspositionTable(_))

    try
      Using.resource(
        OnnxArenaBot.register(
          id = ChallengerId,
          modelPath = challengerModel,
          featureSet = challengerFeatures,
          searchKind = searchKind,
          config = configObj,
          difficulty = ArenaDifficulty,
          description = s"clock-aware model duel over $challengerModel",
          statsSink = sink("challenger"),
          rootRescore = rootRescore,
          preRankWithModel = preRankWithModel,
          tt = table
        )
      ) { _ =>
        Using.resource(
          OnnxArenaBot.register(
            id = DefenderId,
            modelPath = defenderModel,
            featureSet = defenderFeatures,
            searchKind = searchKind,
            config = configObj,
            difficulty = ArenaDifficulty,
            description = s"clock-aware model duel over $defenderModel",
            statsSink = sink("defender"),
            rootRescore = rootRescore,
            preRankWithModel = preRankWithModel,
            tt = table
          )
        ) { _ =>
          val results = controls.map { tc =>
            currentPreset = tc.toString
            BotMatchRunner.runTimedMatch(
              ChallengerId,
              DefenderId,
              TimedMatchSetup(
                games,
                tc,
                seed = seed,
                sprtConfig = sprtConfig,
                botTimeManager = TimeManager(challengerPolicy),
                baselineTimeManager = TimeManager(defenderPolicy)
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
                  "search"                -> searchKind.id,
                  "candidateLimit"        -> width,
                  "challengerModel"       -> challengerModel,
                  "challengerFeatures"    -> challengerFeatures,
                  "challengerTimePolicy"  -> challengerPolicy.id,
                  "defenderModel"         -> defenderModel,
                  "defenderFeatures"      -> defenderFeatures,
                  "defenderTimePolicy"    -> defenderPolicy.id,
                  "rootRescoreModel"      -> rescoreModel.getOrElse("disabled"),
                  "rootRescoreFeatures"   -> rescoreModel.fold("n/a")(_ => activeRescoreFeatures),
                  "rootRescoreWeight"     -> rescoreModel.fold("n/a")(_ => activeRescoreWeight.toString),
                  "preRankWithModel"      -> preRankWithModel.toString,
                  "transpositionCapacity" -> ttCapacity.fold("disabled")(_.toString),
                  "statsOut"              -> statsPath.getOrElse("disabled")
                )
              )
            )
          }
        }
      }
    finally
      statsWriter.foreach { writer =>
        writer.close()
        if writer.checkError() then
          System.err.println("STATS_OUT: write errors occurred — the stats file may be incomplete")
      }

final case class OnnxModelDuelConfig(
    challengerModel: String,
    defenderModel: String,
    challengerFeatures: String,
    defenderFeatures: String,
    games: Int,
    candidateLimit: Option[Int],
    rescoreModel: Option[String],
    rescoreFeatures: Option[String],
    rescoreWeight: Option[Double],
    preRankWithModel: Boolean,
    ttCapacity: Option[Int],
    presets: String,
    seed: Long,
    jsonPath: Option[String],
    sprtConfig: Option[SprtConfig],
    challengerPolicy: TimePolicy,
    defenderPolicy: TimePolicy,
    searchKind: SearchKind
)
