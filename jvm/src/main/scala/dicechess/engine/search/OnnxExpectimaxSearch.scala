package dicechess.engine.search

import dicechess.engine.domain.{Color, GameState}

import scala.util.Random

/** A second ONNX model that rescores [[ExpectimaxSearch]]'s root candidates (see [[RootRescore]]) rather than
  * evaluating chance-node leaves — a tactically sharp but leaf-prohibitive model (e.g. trained on
  * [[dicechess.engine.search.KcpFeatures]]) that only the handful of root candidates can afford.
  *
  * @param modelPath
  *   path to the rescoring model, independent of the main model
  * @param extractFeatures
  *   the rescoring model's own feature extractor — need not match the main model's
  * @param weight
  *   blend weight, forwarded to [[RootRescore]] (`(0, 1]`)
  */
final case class RootRescoreModel(
    modelPath: String,
    extractFeatures: (GameState, Color) => Array[Float],
    weight: Double
)

/** A configurable two- or three-ply expectimax bot whose leaf evaluator is an externally-trained model (LightGBM, via
  * ONNX).
  *
  * [[ExpectimaxSearch]] supplies the lookahead and [[OnnxEvalSearch]] supplies the value function, evaluated in batches
  * at leaf decision nodes. The default two-ply tree sees the opponent's reply; `searchDepth = 3` also sees our next
  * rolled reply and therefore resolves exchanges that otherwise end on the opponent's capture.
  *
  * `rootRescore`, when given, wires a *second* ONNX session as [[ExpectimaxSearch]]'s root rescorer — see
  * [[RootRescoreModel]].
  *
  * `preRankWithModel`, when `true`, uses this bot's *own already-loaded* model (batched) to pre-rank root candidates
  * instead of material — no second session, since the model already scoring the chance-node leaves is exactly the
  * "opinion" candidate selection should defer to. See [[ExpectimaxSearch]]'s `preRank` parameter for why: widening
  * `candidateLimit` only compensates for a crude (material) pre-ranker; a sharper one attacks the actual bottleneck.
  *
  * `statsSink` is forwarded to the underlying [[ExpectimaxSearch]] — one [[RootSearchStats]] per move, so a production
  * host can log how many candidates its deadline really allowed (the difference between the configured limit and the
  * width actually searched on slow hardware).
  *
  * Owns the ONNX session(s) — the main model's, and the rescorer's when configured; call [[close]] when done. Not safe
  * for concurrent calls, matching every other bot here.
  */
final class OnnxExpectimaxSearch(
    modelPath: String,
    config: ExpectimaxConfig = ExpectimaxConfig(),
    extractFeatures: (GameState, Color) => Array[Float] = OnnxFeatures.extract,
    rootRescore: Option[RootRescoreModel] = None,
    preRankWithModel: Boolean = false,
    statsSink: RootSearchStats => Unit = ExpectimaxSearch.NoStats,
    tt: Option[TranspositionTable] = None
) extends TimeBudgetedSearch
    with AutoCloseable:

  private val (onnx, rescoreOnnx, expectimax) = OnnxExpectimaxSearchInitialization.initialize(
    modelPath,
    config,
    extractFeatures,
    rootRescore,
    preRankWithModel,
    statsSink,
    tt
  )

  override def findBestMove(state: GameState): Option[ScoredSequence] =
    expectimax.findBestMove(state)

  override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
    expectimax.findBestMove(state, random)

  override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] =
    expectimax.findBestMove(state, deadlineNanos, random)

  override def close(): Unit =
    onnx.close()
    rescoreOnnx.foreach(_.close())

private[search] object OnnxExpectimaxSearchInitialization:

  type SessionFactory = (String, (GameState, Color) => Array[Float]) => OnnxEvalSearch

  private val DefaultSessionFactory: SessionFactory =
    (path, features) => new OnnxEvalSearch(path, features)

  def initialize(
      modelPath: String,
      config: ExpectimaxConfig,
      extractFeatures: (GameState, Color) => Array[Float],
      rootRescore: Option[RootRescoreModel],
      preRankWithModel: Boolean,
      statsSink: RootSearchStats => Unit,
      tt: Option[TranspositionTable] = None,
      sessionFactory: SessionFactory = DefaultSessionFactory
  ): (OnnxEvalSearch, Option[OnnxEvalSearch], ExpectimaxSearch) =
    val onnx        = sessionFactory(modelPath, extractFeatures)
    var rescoreOnnx = Option.empty[OnnxEvalSearch]
    try
      rescoreOnnx = rootRescore.map(r => sessionFactory(r.modelPath, r.extractFeatures))
      val expectimax = new ExpectimaxSearch(
        (states, color) => onnx.onnxEvalBatch(states, color),
        config,
        for
          session <- rescoreOnnx
          r       <- rootRescore
        yield RootRescore((states, color) => session.onnxEvalBatch(states, color), r.weight),
        if preRankWithModel then (states, color) => onnx.onnxEvalBatch(states, color)
        else ExpectimaxSearch.materialBatch,
        statsSink,
        tt
      )
      (onnx, rescoreOnnx, expectimax)
    catch
      case error: Throwable =>
        rescoreOnnx.foreach(closeSuppressing(_, error))
        closeSuppressing(onnx, error)
        throw error // scalafix:ok(DisableSyntax.throw)

  private def closeSuppressing(session: OnnxEvalSearch, originalError: Throwable): Unit =
    try session.close()
    catch
      case closeError: Throwable =>
        if closeError ne originalError then originalError.addSuppressed(closeError)
