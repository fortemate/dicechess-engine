package dicechess.engine.bench

import dicechess.engine.search.{BotInfo, BotRegistry, ExpectimaxConfig, OnnxEvalSearch, OnnxExpectimaxSearch, RootSearchStats}

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

  /** Builds and registers an ONNX bot.
    *
    * @param config
    *   expectimax configuration; unused at one ply, where there is no candidate pre-ranking to limit. Callers that
    *   expose a `--candidate-limit` flag should reject it for one ply rather than let it be silently dropped here.
    * @param difficulty
    *   registry presentation metadata only — the arena never reads it when running a match.
    */
  def register(
      id: String,
      modelPath: String,
      featureSet: String,
      searchKind: SearchKind,
      config: ExpectimaxConfig,
      difficulty: Int,
      description: String,
      statsSink: RootSearchStats => Unit = _ => ()
  ): BotRegistry.Registration =
    val extract     = ArenaOptions.extractFeatures(featureSet)
    val (bot, name) = searchKind match
      case SearchKind.OnePly =>
        (new OnnxEvalSearch(modelPath, extract), s"ONNX One-Ply ($featureSet)")
      case SearchKind.Expectimax =>
        (
          new OnnxExpectimaxSearch(modelPath, config, extract, statsSink = statsSink),
          s"ONNX Expectimax ($featureSet, K=${config.candidateLimit})"
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
