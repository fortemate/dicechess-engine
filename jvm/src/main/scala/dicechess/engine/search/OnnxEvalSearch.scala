package dicechess.engine.search

import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtSession}
import dicechess.engine.domain.*

import java.util.Collections
import scala.util.Random

/** One-ply search scored by an externally-trained model (LightGBM, exported to ONNX) instead of a hand-tuned heuristic.
  * Structurally identical to [[GreedySearch]]/[[AggressiveSearch]] — the only difference is the `evalFn` handed to
  * [[SearchScoring.scorePath]].
  *
  * The ONNX session is created once per instance (session creation, not inference, is the expensive part) and is not
  * thread-safe to call concurrently from multiple threads without external synchronization — matches every other bot in
  * this codebase, none of which claim thread safety either.
  *
  * As a [[TimeBudgetedSearch]] it honours a wall-clock deadline by scoring candidates through the model in
  * [[OnnxEvalSearch.BatchSize]]-sized chunks, checking the clock between chunks rather than only across the whole
  * candidate set — see
  * [[findBestMove(state:dicechess\.engine\.domain\.GameState,deadlineNanos:Long,random:scala\.util\.Random)*]].
  */
class OnnxEvalSearch(
    modelPath: String,
    extractFeatures: (GameState, Color) => Array[Float] = OnnxFeatures.extract
) extends TimeBudgetedSearch
    with AutoCloseable:

  private val env     = OrtEnvironment.getEnvironment
  private val session = env.createSession(modelPath, new OrtSession.SessionOptions())

  /** The model's raw output is a probability in [0, 1]; scaling by this keeps every score far below
    * [[SearchScoring.TerminalWinScore]] (`Int.MaxValue`) while preserving enough resolution to discriminate between
    * close positions.
    */
  private val ScoreScale = 10000

  /** Runs the ONNX session on a batch of feature rows (`[batch × F]`, `F` = `extractFeatures`' width, which must match
    * the model's input) and returns the scaled scores in row order.
    *
    * The session run is the shared inference primitive behind [[onnxEval]] and [[onnxEvalBatch]]; both funnel through
    * here so the tensor lifecycle and scaling stay in one place. The output tensor is `[batch × 1]` (one probability
    * per row).
    */
  private def runScaled(features: Array[Array[Float]]): Array[Int] =
    val inputTensor = OnnxTensor.createTensor(env, features)
    try
      val result = session.run(Collections.singletonMap("input", inputTensor))
      try result.get(0).getValue.asInstanceOf[Array[Array[Float]]].map(row => (row(0) * ScoreScale).toInt)
      finally result.close()
    finally inputTensor.close()

  /** Runs the ONNX session on `state`'s features (via `extractFeatures`) from `color`'s perspective, scaled onto
    * `scorePath`'s Int axis.
    */
  def onnxEval(state: GameState, color: Color): Int =
    runScaled(Array(extractFeatures(state, color)))(0)

  /** Batched [[onnxEval]]: evaluates many positions from a single `color`'s perspective in one session run.
    *
    * Session-run cost is dominated by per-call overhead (JNI boundary, graph setup), not by the number of rows, so
    * folding N positions into one `[N × F]` tensor is far cheaper than N separate `[1 × F]` runs. This is the hot path
    * for a multi-leaf search — e.g. an expectimax chance node integrating over the 56 dice outcomes (see
    * [[DiceRolls]]), whose leaves are all scored from the root mover's perspective. Scores come back on the same scaled
    * axis as [[onnxEval]], one per input in order; an empty input yields an empty result.
    */
  def onnxEvalBatch(states: Array[GameState], color: Color): Array[Int] =
    if states.isEmpty then Array.emptyIntArray
    else runScaled(states.map(extractFeatures(_, color)))

  override def findBestMove(state: GameState): Option[ScoredSequence] =
    findBestMove(state, new Random())

  override def findBestMove(state: GameState, rand: Random): Option[ScoredSequence] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then None
    else
      val scoredPaths  = paths.map(path => SearchScoring.scorePath(state, path, onnxEval))
      val maxCriterion = scoredPaths.map(scored => (scored.score, terminalWinPreference(scored))).max
      val optimalPaths = scoredPaths.filter(scored => (scored.score, terminalWinPreference(scored)) == maxCriterion)
      Some(optimalPaths(rand.nextInt(optimalPaths.length)))

  private def terminalWinPreference(scored: ScoredSequence): Int =
    if scored.score == SearchScoring.TerminalWinScore then -scored.moves.size else 0

  /** Finds the best turn under a wall-clock deadline, scoring the model in [[OnnxEvalSearch.BatchSize]]-row chunks so
    * the deadline can be honoured between chunks rather than only across the whole candidate set (#497) — mind the
    * granularity point in [[TimeBudgetedSearch]]'s Scaladoc.
    *
    * Strategy, mirroring [[MonteCarloSearch]]'s deadline path: a cheap material pre-score
    * ([[Evaluator.evaluateMaterial]] via [[SearchScoring.scorePath]]) takes any immediate king capture for free —
    * `scorePath` assigns [[SearchScoring.TerminalWinScore]] regardless of the evaluator — and otherwise doubles as the
    * anytime fallback if the deadline is already past before the first chunk runs. Every remaining candidate's
    * resulting position is then scored through the model, one chunk at a time, keeping the best (ties broken via
    * `random`, as in the untimed path) of whatever chunks complete before the deadline.
    */
  override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then None
    else
      val preScored = paths.map(path => SearchScoring.scorePath(state, path, Evaluator.evaluateMaterial))
      val captures  = preScored.filter(_.score == SearchScoring.TerminalWinScore)
      if captures.nonEmpty then Some(captures.minBy(_.moves.size))
      else
        val myColor                = state.activeColor
        val pathsArr               = paths.toArray
        val finalStates            = pathsArr.map(path => path.foldLeft(state)((s, move) => s.makeMove(move)).endTurn())
        val (bestScore, bestPaths) = scoreChunksUnderDeadline(finalStates, pathsArr, myColor, deadlineNanos)
        // The deadline elapsed before even the first chunk: fall back to the cheap material pick, exactly as
        // MonteCarloSearch's deadline path does, so a legal turn is still returned.
        if bestPaths.isEmpty then Some(preScored.maxBy(_.score))
        else Some(ScoredSequence(bestPaths(random.nextInt(bestPaths.length)), bestScore))

  /** Scores `finalStates` (one non-capturing candidate's resulting position each, same index as `pathsArr`) through the
    * model [[OnnxEvalSearch.BatchSize]] rows at a time, stopping before starting a chunk that would begin at or after
    * `deadlineNanos` — the chunk, not the whole array, is this loop's unit of uninterruptible work.
    *
    * @return
    *   the best score found and every path achieving it (so the caller can break ties via `random`, matching the
    *   untimed path), or `(Int.MinValue, Nil)` when the deadline elapsed before a single chunk could run.
    */
  private def scoreChunksUnderDeadline(
      finalStates: Array[GameState],
      pathsArr: Array[List[Move]],
      myColor: Color,
      deadlineNanos: Long
  ): (Int, List[List[Move]]) =
    var bestScore = Int.MinValue
    var bestPaths = List.empty[List[Move]]
    var i         = 0
    while i < finalStates.length && System.nanoTime() < deadlineNanos do
      val end    = math.min(i + OnnxEvalSearch.BatchSize, finalStates.length)
      val scores = onnxEvalBatch(finalStates.slice(i, end), myColor)
      var j      = 0
      while j < scores.length do
        val score = scores(j)
        if score > bestScore then
          bestScore = score
          bestPaths = List(pathsArr(i + j))
        else if score == bestScore then bestPaths = pathsArr(i + j) :: bestPaths
        j += 1
      i = end
    (bestScore, bestPaths)

  override def close(): Unit = session.close()

object OnnxEvalSearch:

  /** Chunk size for the deadline-checked batch loop in the timed
    * [[findBestMove(state:dicechess\.engine\.domain\.GameState,deadlineNanos:Long,random:scala\.util\.Random)*]] —
    * small enough that one chunk stays a modest fraction of a realistic per-move budget (the granularity concern in
    * [[TimeBudgetedSearch]]'s Scaladoc), large enough to keep the ONNX session's per-call overhead amortized across
    * many rows, in the same spirit as [[onnxEvalBatch]].
    */
  private[search] val BatchSize = 32
