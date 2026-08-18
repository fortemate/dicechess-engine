package dicechess.engine.bench

import scala.io.Source

import com.monovore.decline.*
import cats.implicits.*

import dicechess.engine.search.{
  BotInfo,
  BotRegistry,
  ExpectimaxConfig,
  KcpFeatures,
  OnnxExpectimaxSearch,
  OpeningBookBot,
  OpeningBookParser,
  RootRescoreModel,
  SearchAlgorithm
}

/** Acceptance-gate arena for the 2-ply ONNX expectimax bot against a built-in baseline (`aggressive` by default) — the
  * ">= 55% win rate" check for the Dice Chess AI hackathon project.
  *
  * Same orientation as [[OnnxArenaRunner]]: the built-in bot is the [[BotMatchRunner.runArena]] baseline and the
  * expectimax bot is the measured opponent, so the printed row is the model bot's win/loss/win-rate. The comparison
  * against [[OnnxArenaRunner]] (one-ply, same model) shows what the lookahead buys.
  *
  * The model file is read from a runtime path and never committed to this public repository.
  *
  * Usage:
  * `sbt 'arena/runMain dicechess.engine.bench.OnnxExpectimaxArenaRunner <model.onnx> --opponent aggressive --games 200 --features material'`
  */
object OnnxExpectimaxArenaRunner:

  final private case class Config(
      modelPath: String,
      opponentId: String,
      games: Int,
      candidateLimit: Int,
      featureSet: String,
      rescoreModelPath: Option[String],
      rescoreWeight: Double,
      preRankWithModel: Boolean,
      bookPath: Option[String],
      seed: Long,
      jsonPath: Option[String]
  )

  def main(args: Array[String]): Unit =
    val command = Command(
      name = "OnnxExpectimaxArenaRunner",
      header = "Dice Chess Bot Arena - ONNX Expectimax Runner"
    ) {
      import ArenaOptions.*
      (
        modelPathOpt,
        opponentOpt("aggressive"),
        gamesOpt(200),
        candidateLimitOpt(),
        featuresOpt("material"),
        rescoreModelPathOpt,
        rescoreWeightOpt,
        preRankWithModelOpt,
        bookPathOpt,
        seedOpt(),
        jsonPathOpt
      ).mapN(Config.apply).map { cfg =>
        val extractFeatures = ArenaOptions.extractFeatures(cfg.featureSet)

        val rootRescore =
          cfg.rescoreModelPath.map(path => RootRescoreModel(path, KcpFeatures.extract, cfg.rescoreWeight))

        val opponentInfo = BotRegistry.availableBots
          .find(_.id.equalsIgnoreCase(cfg.opponentId))
          .getOrElse(sys.error(s"Unknown opponent bot '${cfg.opponentId}'"))

        val botId = "onnx-expectimax"
        val bot   = new OnnxExpectimaxSearch(
          cfg.modelPath,
          ExpectimaxConfig(cfg.candidateLimit),
          extractFeatures,
          rootRescore,
          cfg.preRankWithModel
        )
        // registration is set only once BotRegistry actually holds an entry for botId; botNeedsOwnClose records
        // whether that entry is the *decorated* algorithm rather than `bot` itself, so `finally` knows whether
        // closing the registration already closed `bot` or whether `bot` still needs its own close call. Both start
        // at the "close `bot` myself" default, so any failure before registration succeeds (including a bad opening
        // book) still closes the ONNX session instead of leaking it.
        var registration: Option[BotRegistry.Registration] = None
        var botNeedsOwnClose                               = true
        try
          // With a book, the *decorated* algorithm is registered while `bot` is kept for closing the ONNX session —
          // the decorator owns no resources of its own, so the registration handle alone would not close it.
          val book = cfg.bookPath.map { path =>
            val tsv =
              val source = Source.fromFile(path)
              try source.mkString
              finally source.close()
            OpeningBookParser
              .parse(tsv)
              .fold(error => sys.error(s"Failed to parse opening book '$path': ${error.getMessage}"), identity)
          }
          val algorithm = book.fold(bot: SearchAlgorithm)(entries => OpeningBookBot.decorate(bot, entries))
          registration = Some(
            BotRegistry.registerCustomBot(
              BotInfo(
                id = botId,
                name = "ONNX Expectimax (2-ply)",
                description = s"2-ply expectimax scored by an externally-trained LightGBM model (${cfg.modelPath})",
                difficulty = opponentInfo.difficulty,
                isExperimental = true
              ),
              algorithm
            )
          )
          botNeedsOwnClose = book.isDefined

          val rescoreNote = rootRescore.fold("")(r => s", rootRescore=${r.modelPath} (weight=${r.weight})")
          val preRankNote = if cfg.preRankWithModel then ", preRank=model" else ""
          val bookNote    = book.fold("")(entries => s", book=${cfg.bookPath.getOrElse("")} (${entries.size} entries)")
          println(
            s"Loaded ONNX model from ${cfg.modelPath} (candidateLimit=${cfg.candidateLimit}, features=${cfg.featureSet}$rescoreNote$preRankNote$bookNote)"
          )
          // Opponent as baseline, expectimax bot as the measured side: the table row is the model bot's stats.
          BotMatchRunner.runArena(
            opponentInfo.id,
            Some(botId),
            cfg.games,
            BotMatchRunner.StartFen,
            seed = cfg.seed,
            jsonPath = cfg.jsonPath
          )
        finally
          registration.foreach(_.close())
          if botNeedsOwnClose then bot.close()
      }
    }
    ArenaOptions.runCommand(command, args)
