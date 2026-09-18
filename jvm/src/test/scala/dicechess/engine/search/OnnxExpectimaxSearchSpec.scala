// SPDX-License-Identifier: AGPL-3.0-only
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

  test("zero-weight root rescoring is disabled before a second ONNX session is created"):
    var sessionsCreated                                                   = 0
    val sessionFactory: OnnxExpectimaxSearchInitialization.SessionFactory = (_, features) =>
      sessionsCreated += 1
      new OnnxEvalSearch(modelPath, features)
    val (sessions, expectimax) = OnnxExpectimaxSearchInitialization.initialize(
      modelPath,
      ExpectimaxConfig(),
      OnnxFeatures.extract,
      OnnxSearchOptions(
        rootRescore = Some(RootRescoreModel("unused", OnnxFeatures.extract, weight = 0.0)),
        preRankWithModel = false,
        statsSink = ExpectimaxSearch.NoStats,
        tt = None
      ),
      sessionFactory = sessionFactory
    )
    try
      assertEquals(sessionsCreated, 1)
      assert(sessions.rescore.isEmpty)
      assert(sessions.collapse.isEmpty)
      assert(expectimax.findBestMove(state).isDefined)
    finally sessions.closeAll()

  test("RootRescoreModel validates the closed weight interval before session initialization"):
    intercept[IllegalArgumentException](RootRescoreModel("unused", OnnxFeatures.extract, weight = -0.1))
    intercept[IllegalArgumentException](RootRescoreModel("unused", OnnxFeatures.extract, weight = 1.1))
    intercept[IllegalArgumentException](RootRescoreModel("unused", OnnxFeatures.extract, weight = Double.NaN))
    RootRescoreModel("unused", OnnxFeatures.extract, weight = 0.0)
    RootRescoreModel("unused", OnnxFeatures.extract, weight = 1.0)

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
          OnnxSearchOptions(
            rootRescore = Some(RootRescoreModel("unused", OnnxFeatures.extract, weight = 0.5)),
            preRankWithModel = false,
            statsSink = ExpectimaxSearch.NoStats,
            tt = None
          ),
          sessionFactory = sessionFactory
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

  // --- chance collapse (#78) -------------------------------------------------------------------

  private def collapsePackage(manifest: java.nio.file.Path) =
    dicechess.engine.model.ModelPackage
      .loadFiles(
        dicechess.engine.model.ContractFixtures.valueModel,
        manifest,
        dicechess.engine.model.ContractFixtures.EngineVersion
      )
      .getOrElse(fail(s"fixture package must load: $manifest"))

  test("wires a third session for the chance-collapse model, with its own feature schema, and closes all of them"):
    // The leaf model here is the 7-wide synthetic one and the collapse model the 13-wide kcp-13 fixture: two schemas
    // in one bot, which only works if each session is handed its own extractor.
    val collapse = CollapseModel
      .fromPackage(collapsePackage(dicechess.engine.model.ContractFixtures.collapseManifest))
      .getOrElse(fail("the collapse fixture must be accepted"))
    val bot = new OnnxExpectimaxSearch(
      modelPath,
      ExpectimaxConfig(),
      OnnxFeatures.extract,
      Some(RootRescoreModel(modelPath, OnnxFeatures.extract, weight = 0.5)),
      chanceCollapse = Some(collapse.copy(extractFeatures = KcpFeatures.extract))
    )
    try assert(bot.findBestMove(state).isDefined)
    finally bot.close() // must not throw closing three sessions

  test("no chance-collapse session is created unless the hook is configured"):
    var sessionsCreated                                                   = 0
    val sessionFactory: OnnxExpectimaxSearchInitialization.SessionFactory = (_, features) =>
      sessionsCreated += 1
      new OnnxEvalSearch(modelPath, features)

    def initialize(collapse: Option[CollapseModel]) =
      OnnxExpectimaxSearchInitialization.initialize(
        modelPath,
        ExpectimaxConfig(),
        OnnxFeatures.extract,
        OnnxSearchOptions(chanceCollapse = collapse),
        sessionFactory = sessionFactory
      )

    val (without, _) = initialize(None)
    try
      assertEquals(sessionsCreated, 1)
      assert(without.collapse.isEmpty)
    finally without.closeAll()

    sessionsCreated = 0
    val (withHook, expectimax) = initialize(Some(CollapseModel(modelPath, OnnxFeatures.extract)))
    try
      assertEquals(sessionsCreated, 2, "one leaf session plus the collapse model's own")
      assert(withHook.collapse.isDefined)
      assert(expectimax.findBestMove(state).isDefined)
    finally withHook.closeAll()

  test("a model trained for another role cannot be wired as the chance-collapse model"):
    val valueModel = collapsePackage(dicechess.engine.model.ContractFixtures.valueManifest)
    val refused    = CollapseModel.fromPackage(valueModel).swap.getOrElse(fail("expected a rejection"))
    assert(refused.contains("modelRole 'position-value' cannot serve as 'chance-collapse'"), refused)

  test("closing reaches every session even when one of them fails, and reports the first failure"):
    val closeFailure = new IllegalStateException("collapse session close failed")
    var leafClosed   = false
    val leaf         = new OnnxEvalSearch(modelPath, OnnxFeatures.extract):
      override def close(): Unit =
        super.close()
        leafClosed = true
    val failing = new OnnxEvalSearch(modelPath, OnnxFeatures.extract):
      override def close(): Unit =
        super.close()
        Failure(closeFailure).get
    val thrown = intercept[IllegalStateException](OnnxSearchSessions(leaf, collapse = Some(failing)).closeAll())
    assert(thrown eq closeFailure)
    assert(leafClosed, "a failing close must not leak the sessions after it")

  test("a session that fails to open closes the ones already opened, not only the leaf"):
    val creationFailure = new IllegalStateException("third session creation failed")
    var created         = 0
    var closed          = List.empty[Int]
    val sessionFactory: OnnxExpectimaxSearchInitialization.SessionFactory = (path, features) =>
      created += 1
      if created == 3 then Failure(creationFailure).get
      else
        val index = created
        new OnnxEvalSearch(path, features):
          override def close(): Unit =
            super.close()
            closed = index :: closed

    val thrown = intercept[IllegalStateException]:
      OnnxExpectimaxSearchInitialization.initialize(
        modelPath,
        ExpectimaxConfig(),
        OnnxFeatures.extract,
        OnnxSearchOptions(
          rootRescore = Some(RootRescoreModel(modelPath, OnnxFeatures.extract, weight = 0.5)),
          chanceCollapse = Some(CollapseModel(modelPath, OnnxFeatures.extract))
        ),
        sessionFactory = sessionFactory
      )
    assert(thrown eq creationFailure)
    assertEquals(closed.sorted, List(1, 2), "both the leaf session and the rescorer's must be closed")
