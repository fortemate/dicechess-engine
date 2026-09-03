package dicechess.engine.bench

import dicechess.engine.search.{
  BotInfo,
  BotRegistry,
  ExpectimaxConfig,
  OnnxEvalSearch,
  OnnxExpectimaxSearch,
  OnnxSearchOptions
}

/** Makes an ONNX model playable by the arena, by giving it a [[BotRegistry]] id.
  *
  * [[BotMatchRunner]] resolves both sides of a match by registry id, and a model file is not a registry bot until
  * something registers it — so every ONNX runner that wants to reuse the engine's own match code has to perform this
  * step first. Keeping it here is what lets those runners differ only in what they measure.
  *
  * Both search depths live here because the arena needs whichever one the bot under discussion actually runs:
  * `dicechess-house-bots` picks between them with `ORACLE_SEARCH`, and an expensive evaluator that is only viable at
  * one ply cannot be reproduced by a 2-ply-only harness.
  *
  * The bot owns a native onnxruntime session. Closing the returned [[BotRegistry.Registration]] (e.g. via
  * [[scala.util.Using]]) both closes that session and removes the id from [[BotRegistry]], so a later run in the same
  * JVM does not find a closed session still reachable by id (#589).
  */
private[bench] object OnnxArenaBot:

  /** Specification for an ONNX model asset and its feature extractor identifier.
    *
    * @param path
    *   file system path to the serialized ONNX model
    * @param featureSet
    *   identifier of the feature extractor used by the model
    */
  final case class ModelSpec(path: String, featureSet: String)

  /** Builds and registers an ONNX bot.
    *
    * @param id
    *   registry identifier for the custom bot
    * @param model
    *   model specification (path and feature set)
    * @param searchKind
    *   one-ply or expectimax lookahead
    * @param config
    *   expectimax configuration; unused at one ply, where there is no candidate pre-ranking to limit. Callers that
    *   expose a `--candidate-limit` flag should reject it for one ply rather than let it be silently dropped here.
    * @param difficulty
    *   registry presentation metadata only — the arena never reads it when running a match.
    * @param description
    *   human-readable description for the registry
    * @param options
    *   search-tuning options (stats sink, root rescoring, pre-ranking, transposition table)
    */
  def register(
      id: String,
      model: ModelSpec,
      searchKind: SearchKind,
      config: ExpectimaxConfig,
      difficulty: Int,
      description: String,
      options: OnnxSearchOptions = OnnxSearchOptions()
  ): BotRegistry.Registration =
    val extract     = ArenaOptions.extractFeatures(model.featureSet)
    val (bot, name) = searchKind match
      case SearchKind.OnePly =>
        (new OnnxEvalSearch(model.path, extract), s"ONNX One-Ply (${model.featureSet})")
      case SearchKind.Expectimax =>
        (
          new OnnxExpectimaxSearch(
            model.path,
            config,
            extract,
            rootRescore = options.rootRescore,
            preRankWithModel = options.preRankWithModel,
            statsSink = options.statsSink,
            tt = options.tt
          ),
          s"ONNX Expectimax (${model.featureSet}, K=${config.candidateLimit})"
        )

    BotRegistry.registerCustomBot(
      BotInfo(
        id = id,
        name = name,
        description = description,
        difficulty = difficulty,
        isExperimental = true
      ),
      bot
    )
