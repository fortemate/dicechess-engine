// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.{Color, GameState}
import dicechess.engine.model.{ModelPackage, ModelRole}

import scala.util.{Random, Try}

/** A second ONNX model that rescores [[ExpectimaxSearch]]'s root candidates (see [[RootRescore]]) rather than
  * evaluating chance-node leaves — a tactically sharp but leaf-prohibitive model (e.g. trained on
  * [[dicechess.engine.search.KcpFeatures]]) that only the handful of root candidates can afford.
  *
  * @param modelPath
  *   path to the rescoring model, independent of the main model
  * @param extractFeatures
  *   the rescoring model's own feature extractor — need not match the main model's
  * @param weight
  *   blend weight, forwarded to [[RootRescore]] (`[0, 1]`). Zero disables rescoring before a second ONNX session is
  *   created.
  */
final case class RootRescoreModel(
    modelPath: String,
    extractFeatures: (GameState, Color) => Array[Float],
    weight: Double
):
  require(weight >= 0.0 && weight <= 1.0, s"weight must be in [0, 1], got $weight")

/** A model that answers a root candidate's whole chance node in one row instead of an expansion of the opponent's 56
  * weighted dice outcomes — wired as [[ChanceCollapse]], whose Scaladoc states what the hook keeps and what it gives
  * up.
  *
  * It gets its own ONNX session, never the leaf model's: the two answer different questions (an expectation over the
  * opponent's roll versus the value of one position), so a model in this slot is a different artifact with a different
  * training target, and [[CollapseModel.fromPackage]] refuses one whose manifest says otherwise.
  *
  * @param modelPath
  *   path to the chance-collapse model, independent of the main model
  * @param extractFeatures
  *   this model's own feature extractor
  * @param lossGuard
  *   whether to re-establish the exact search's loss-taint rule with one [[KingCaptureProbability]] search per root
  *   candidate — see [[ChanceCollapse.lossGuard]]
  */
final case class CollapseModel(
    modelPath: String,
    extractFeatures: (GameState, Color) => Array[Float],
    lossGuard: Boolean = false
)

object CollapseModel:

  /** Configures the hook from a validated model package, refusing an artifact trained for a different role.
    *
    * The manifest has already proved which extractor these bytes expect, so the caller does not choose one: a package
    * that reached this point carries its own, and passing the wrong one is no longer possible.
    */
  def fromPackage(pkg: ModelPackage, lossGuard: Boolean = false): Either[String, CollapseModel] =
    Either.cond(
      pkg.role == ModelRole.ChanceCollapse,
      CollapseModel(pkg.modelPath.toString, pkg.extract, lossGuard),
      s"${pkg.modelPath}: modelRole '${pkg.role.id}' cannot serve as '${ModelRole.ChanceCollapse.id}'"
    )

/** A model whose only job is to *order* the mover's legal turns, so the search expands the best
  * [[ExpectimaxConfig.candidateLimit]] of them rather than the best the material proxy could find.
  *
  * It gets its own session on purpose. Ordering candidates and valuing positions are different jobs: a ranker is
  * trained on which turn is better, a value model on how good a position is, and the two have different targets, can
  * use different feature schemas, and are not interchangeable just because both emit one number per row. Reusing the
  * leaf model for ranking is the cheaper option this repository already offers — `preRankWithModel` — and the two are
  * alternatives, not a pair.
  *
  * **What bounds its cost is the feature schema, not this type.** Pre-ranking sees every legal turn, which in Dice
  * Chess is routinely hundreds and can be thousands; at that width `material-7-v1` is a thousand cheap rows while
  * `kcp-13` is a thousand 216-outcome capture-probability searches, which is not viable at this seam at all. The
  * pre-rank pass is also paid in full *before* the deadline is consulted — it has to be, since its output is what the
  * anytime fallback plays — so a schema too expensive for the position's candidate count overruns the move budget
  * before the search proper begins.
  *
  * @param modelPath
  *   path to the pre-ranking model, independent of the main model
  * @param extractFeatures
  *   this model's own feature extractor
  * @param chunkSize
  *   rows per session run, forwarded to [[OnnxEvalSearch.onnxEvalBatchChunked]]. A bound, not a tuned value: it keeps
  *   one `[rows × F]` tensor from growing with the position's branching, and the throughput-optimal value depends on
  *   the model and belongs in the host's configuration.
  */
final case class PreRankModel(
    modelPath: String,
    extractFeatures: (GameState, Color) => Array[Float],
    chunkSize: Int = PreRankModel.DefaultChunkSize
):
  require(chunkSize > 0, s"chunkSize must be positive, got $chunkSize")

object PreRankModel:

  /** Default row bound per session run — large enough that per-call overhead stays amortized, small enough that the
    * tensor does not follow the branching factor.
    */
  val DefaultChunkSize = 256

  /** Configures the seam from a validated model package, refusing an artifact trained for a different role. */
  def fromPackage(pkg: ModelPackage, chunkSize: Int = DefaultChunkSize): Either[String, PreRankModel] =
    Either.cond(
      pkg.role == ModelRole.MovePreRank,
      PreRankModel(pkg.modelPath.toString, pkg.extract, chunkSize),
      s"${pkg.modelPath}: modelRole '${pkg.role.id}' cannot serve as '${ModelRole.MovePreRank.id}'"
    )

/** Search-tuning options for ONNX-backed expectimax search.
  *
  * @param statsSink
  *   sink function receiving root search statistics per decision node
  * @param rootRescore
  *   optional second ONNX model used to rescore root candidate moves
  * @param preRankWithModel
  *   whether to pre-rank root candidates using the main model instead of material
  * @param tt
  *   optional transposition table for caching evaluated search states
  * @param chanceCollapse
  *   optional model replacing each root candidate's exact chance-node expansion
  * @param preRankModel
  *   optional dedicated model for ordering root candidates, in its own session
  */
final case class OnnxSearchOptions(
    statsSink: RootSearchStats => Unit = _ => (),
    rootRescore: Option[RootRescoreModel] = None,
    preRankWithModel: Boolean = false,
    tt: Option[TranspositionTable] = None,
    chanceCollapse: Option[CollapseModel] = None,
    preRankModel: Option[PreRankModel] = None
):
  // Both configure the same seam, so a host that set both means one of them by mistake — and picking a winner here
  // would hide that from the only person who knows which.
  require(
    !(preRankWithModel && preRankModel.isDefined),
    "preRankWithModel and preRankModel are alternatives: the first reuses the leaf model, the second opens its own"
  )

/** A configurable two- or three-ply expectimax bot whose leaf evaluator is an externally-trained model (LightGBM, via
  * ONNX).
  *
  * [[ExpectimaxSearch]] supplies the lookahead and [[OnnxEvalSearch]] supplies the value function, evaluated in batches
  * at leaf decision nodes. The default two-ply tree sees the opponent's reply; `searchDepth = 3` also sees our next
  * rolled reply and therefore resolves exchanges that otherwise end on the opponent's capture.
  *
  * A positive-weight `rootRescore` wires a *second* ONNX session as [[ExpectimaxSearch]]'s root rescorer — see
  * [[RootRescoreModel]]. A zero-weight configuration is treated exactly like `None`, including session ownership.
  *
  * `preRankWithModel`, when `true`, uses this bot's *own already-loaded* model (batched) to pre-rank root candidates
  * instead of material — no second session, since the model already scoring the chance-node leaves is exactly the
  * "opinion" candidate selection should defer to. See [[ExpectimaxSearch]]'s `preRank` parameter for why: widening
  * `candidateLimit` only compensates for a crude (material) pre-ranker; a sharper one attacks the actual bottleneck.
  *
  * `preRankModel` wires a *dedicated* session for candidate ordering instead — see [[PreRankModel]] for why ranking and
  * valuing are different jobs, and why its cost is bounded by the feature schema rather than by the candidate limit. It
  * is an alternative to `preRankWithModel`, not a companion: configuring both is rejected.
  *
  * `chanceCollapse` wires a model in place of the chance-node expansion itself — the layer that costs hundreds to
  * thousands of leaf evaluations per candidate. It is a third, independent session, and with it `searchDepth`, the
  * transposition table and Star pruning have nothing left to do at the root: see [[ChanceCollapse]].
  *
  * `statsSink` is forwarded to the underlying [[ExpectimaxSearch]] — one [[RootSearchStats]] per move, so a production
  * host can log how many candidates its deadline really allowed (the difference between the configured limit and the
  * width actually searched on slow hardware).
  *
  * Owns its ONNX session(s) — the main model's, the rescorer's when configured with positive weight, and the
  * chance-collapse model's when configured; call [[close]] when done. Not safe for concurrent calls, matching every
  * other bot here.
  */
final class OnnxExpectimaxSearch(
    modelPath: String,
    config: ExpectimaxConfig = ExpectimaxConfig(),
    extractFeatures: (GameState, Color) => Array[Float] = OnnxFeatures.extract,
    rootRescore: Option[RootRescoreModel] = None,
    preRankWithModel: Boolean = false,
    statsSink: RootSearchStats => Unit = ExpectimaxSearch.NoStats,
    tt: Option[TranspositionTable] = None,
    chanceCollapse: Option[CollapseModel] = None,
    preRankModel: Option[PreRankModel] = None
) extends TimeBudgetedSearch
    with AutoCloseable:

  private val (sessions, expectimax) = OnnxExpectimaxSearchInitialization.initialize(
    modelPath,
    config,
    extractFeatures,
    OnnxSearchOptions(
      statsSink = statsSink,
      rootRescore = rootRescore,
      preRankWithModel = preRankWithModel,
      tt = tt,
      chanceCollapse = chanceCollapse,
      preRankModel = preRankModel
    )
  )

  override def findBestMove(state: GameState): Option[ScoredSequence] =
    expectimax.findBestMove(state)

  override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
    expectimax.findBestMove(state, random)

  override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] =
    expectimax.findBestMove(state, deadlineNanos, random)

  override def close(): Unit = sessions.closeAll()

/** The ONNX sessions one [[OnnxExpectimaxSearch]] owns, held together so that closing cannot forget one.
  *
  * Each optional session is absent exactly when its hook is unconfigured (or, for the rescorer, disabled by a zero
  * weight) — the type is the record of which native resources this bot actually opened.
  */
final private[search] case class OnnxSearchSessions(
    leaf: OnnxEvalSearch,
    rescore: Option[OnnxEvalSearch] = None,
    collapse: Option[OnnxEvalSearch] = None,
    preRank: Option[OnnxEvalSearch] = None
):

  def all: List[OnnxEvalSearch] = leaf :: (rescore.toList ++ collapse.toList ++ preRank.toList)

  /** Closes every session, then reports the first failure with the rest suppressed.
    *
    * Closing in sequence with no guard would leak a native session whenever an earlier `close` failed — the sessions
    * after it would never be reached — and a bot that has just failed to shut one model down is exactly when the others
    * need closing most.
    */
  def closeAll(): Unit =
    val failures = all.flatMap(session => Try(session.close()).failed.toOption)
    failures.headOption.foreach: first =>
      failures.tail.foreach(first.addSuppressed)
      throw first // scalafix:ok(DisableSyntax.throw)

private[search] object OnnxExpectimaxSearchInitialization:

  type SessionFactory = (String, (GameState, Color) => Array[Float]) => OnnxEvalSearch

  private val DefaultSessionFactory: SessionFactory =
    (path, features) => new OnnxEvalSearch(path, features)

  def initialize(
      modelPath: String,
      config: ExpectimaxConfig,
      extractFeatures: (GameState, Color) => Array[Float],
      options: OnnxSearchOptions = OnnxSearchOptions(),
      sessionFactory: SessionFactory = DefaultSessionFactory
  ): (OnnxSearchSessions, ExpectimaxSearch) =
    val leaf              = sessionFactory(modelPath, extractFeatures)
    val activeRootRescore = options.rootRescore.filter(_.weight > 0.0)
    var sessions          = OnnxSearchSessions(leaf)
    try
      // One assignment per session, not one copy for all of them: a session has to be recorded in `sessions` before
      // the next creation can throw, or the error path closes the leaf and leaks everything opened after it.
      sessions =
        sessions.copy(rescore = activeRootRescore.map(model => sessionFactory(model.modelPath, model.extractFeatures)))
      sessions = sessions.copy(collapse =
        options.chanceCollapse.map(model => sessionFactory(model.modelPath, model.extractFeatures))
      )
      sessions = sessions.copy(preRank =
        options.preRankModel.map(model => sessionFactory(model.modelPath, model.extractFeatures))
      )
      val expectimax = new ExpectimaxSearch(
        (states, color) => leaf.onnxEvalBatch(states, color),
        config,
        for
          session <- sessions.rescore
          model   <- activeRootRescore
        yield RootRescore((states, color) => session.onnxEvalBatch(states, color), model.weight),
        preRanker(leaf, sessions, options),
        options.statsSink,
        options.tt,
        for
          session <- sessions.collapse
          model   <- options.chanceCollapse
        yield ChanceCollapse((states, color) => session.onnxEvalBatch(states, color), model.lossGuard)
      )
      (sessions, expectimax)
    catch
      case error: Throwable =>
        closeSuppressing(sessions, error)
        throw error // scalafix:ok(DisableSyntax.throw)

  /** The batched pre-ranker the search orders its candidates with: a dedicated model's own session, the leaf model
    * reused, or material — in that order of specificity, and never two of them, since the options reject that.
    */
  private def preRanker(
      leaf: OnnxEvalSearch,
      sessions: OnnxSearchSessions,
      options: OnnxSearchOptions
  ): (Array[GameState], Color) => Array[Int] =
    val dedicated =
      for
        session <- sessions.preRank
        model   <- options.preRankModel
      yield (states: Array[GameState], color: Color) => session.onnxEvalBatchChunked(states, color, model.chunkSize)
    dedicated.getOrElse(
      if options.preRankWithModel then (states, color) => leaf.onnxEvalBatch(states, color)
      else ExpectimaxSearch.materialBatch
    )

  /** Closes what was opened while a later session or the search itself failed, without losing the original error. */
  private def closeSuppressing(sessions: OnnxSearchSessions, originalError: Throwable): Unit =
    sessions.all.reverse.foreach: session =>
      try session.close()
      catch
        case closeError: Throwable =>
          if closeError ne originalError then originalError.addSuppressed(closeError)
