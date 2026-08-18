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

/** A 2-ply expectimax bot whose leaf evaluator is an externally-trained model (LightGBM, via ONNX).
  *
  * This is the payoff of the search work: [[ExpectimaxSearch]] supplies the lookahead (my turn, the opponent's roll,
  * the opponent's best reply) and [[OnnxEvalSearch]] supplies the value function, evaluated in batches so all the
  * leaves under one chance node cost a single inference call. It fixes the one-ply model bot's recapture blindness —
  * the same model, but now it sees the reply.
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
    statsSink: RootSearchStats => Unit = ExpectimaxSearch.NoStats
) extends TimeBudgetedSearch
    with AutoCloseable:

  private val onnx        = new OnnxEvalSearch(modelPath, extractFeatures)
  private val rescoreOnnx = rootRescore.map(r => new OnnxEvalSearch(r.modelPath, r.extractFeatures))
  private val expectimax  = new ExpectimaxSearch(
    (states, color) => onnx.onnxEvalBatch(states, color),
    config,
    for
      session <- rescoreOnnx
      r       <- rootRescore
    yield RootRescore((states, color) => session.onnxEvalBatch(states, color), r.weight),
    if preRankWithModel then (states, color) => onnx.onnxEvalBatch(states, color) else ExpectimaxSearch.materialBatch,
    statsSink
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
