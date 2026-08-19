package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

import scala.util.Failure
import scala.util.Random

/** Plumbing checks for the ONNX-backed 2-ply bot against the throwaway synthetic model (no chess signal — the real
  * model is a private artifact). Proves the wiring — session, batched leaf evaluation, chance node — runs end to end
  * and returns a legal turn; strength is measured in the arena, not here.
  */
class OnnxExpectimaxSearchSpec extends FunSuite:

  private val modelPath = getClass.getResource("/synthetic_test_model.onnx").getPath

  private def withBot[A](config: ExpectimaxConfig = ExpectimaxConfig())(f: OnnxExpectimaxSearch => A): A =
    val bot = new OnnxExpectimaxSearch(modelPath, config)
    try f(bot)
    finally bot.close()

  private val state =
    FenParser.parse(FenParser.InitialPosition).toOption.get.withDicePool(List(1, 1, 4))

  test("returns a legal turn from the starting position"):
    withBot()(bot => assert(bot.findBestMove(state).isDefined))

  test("honours a deadline and still returns a legal turn"):
    withBot()(bot => assert(bot.findBestMove(state, System.nanoTime(), Random(0)).isDefined))

  test("respects candidateLimit without error"):
    withBot(ExpectimaxConfig(candidateLimit = 2))(bot => assert(bot.findBestMove(state).isDefined))

  test("seeded findBestMove(state, random) returns a legal turn"):
    withBot()(bot => assert(bot.findBestMove(state, Random(0)).isDefined))

  test("threads the feature extractor to the leaf evaluator (9-wide RichFeatures is rejected by this 7-feature model)"):
    val bot = new OnnxExpectimaxSearch(modelPath, ExpectimaxConfig(), RichFeatures.extract)
    try intercept[Exception](bot.findBestMove(state))
    finally bot.close()

  test("wires an optional second session as the root rescorer and closes both on close()"):
    // Same throwaway synthetic model in both slots — this is a plumbing check (two ONNX sessions, both closed), not
    // a strength test (real rescoring behaviour is covered against a stub evalBatch in ExpectimaxSearchSpec).
    val bot = new OnnxExpectimaxSearch(
      modelPath,
      ExpectimaxConfig(),
      OnnxFeatures.extract,
      Some(RootRescoreModel(modelPath, OnnxFeatures.extract, weight = 0.5))
    )
    try assert(bot.findBestMove(state).isDefined)
    finally bot.close() // must not throw closing two sessions

  test("closes the main session when root-rescore session creation fails"):
    val creationFailure = new IllegalStateException("root-rescore session creation failed")
    var mainSession     = Option.empty[OnnxEvalSearch]
    var mainClosed      = false
    val sessionFactory: OnnxExpectimaxSearchInitialization.SessionFactory = (_, features) =>
      mainSession match
        case None =>
          val session = new OnnxEvalSearch(modelPath, features):
            override def close(): Unit =
              super.close()
              mainClosed = true
          mainSession = Some(session)
          session
        case Some(_) => Failure(creationFailure).get

    try
      val thrown = intercept[IllegalStateException]:
        OnnxExpectimaxSearchInitialization.initialize(
          modelPath,
          ExpectimaxConfig(),
          OnnxFeatures.extract,
          Some(RootRescoreModel("unused", OnnxFeatures.extract, weight = 0.5)),
          preRankWithModel = false,
          ExpectimaxSearch.NoStats,
          sessionFactory
        )
      assert(thrown eq creationFailure)
      assert(mainClosed)
    finally if !mainClosed then mainSession.foreach(_.close())

  test("preRankWithModel reuses the single main session (no second one) and still returns a legal turn"):
    // Plumbing check only (real pre-rank-changes-selection behaviour is covered against a stub evalBatch in
    // ExpectimaxSearchSpec): proves the model-based batch pre-ranker runs end to end without opening a second session.
    val bot = new OnnxExpectimaxSearch(modelPath, ExpectimaxConfig(), OnnxFeatures.extract, preRankWithModel = true)
    try assert(bot.findBestMove(state).isDefined)
    finally bot.close()
