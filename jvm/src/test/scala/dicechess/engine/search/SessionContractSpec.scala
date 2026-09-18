// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.{Color, FenParser}
import dicechess.engine.model.{ContractFixtures, FeatureSchema, ModelManifest, ModelPackage}
import munit.FunSuite

/** The graph check on the serving path (#258).
  *
  * `ModelPackage.load` proves what a manifest says about bytes on disk; only a loaded session can prove that the graph
  * those bytes hold is the graph the manifest describes. These tests are about the second half: a package whose graph
  * contradicts its own manifest must be refused where the session is opened, not at the first inference — inside a JNI
  * call, under a move deadline, with the runtime's message instead of the contract's.
  */
class SessionContractSpec extends FunSuite:

  private val engineVersion = ContractFixtures.EngineVersion
  private val state         = FenParser.parse(FenParser.InitialPosition).toOption.get.withDicePool(List(1, 1, 4))

  /** Bare kings with king dice, searched one candidate wide.
    *
    * The manifest prescribes the `kcp-13` extractor, whose four capture-probability columns each integrate over 216
    * dice outcomes — its own Scaladoc says the set targets one-ply inference, because a two-ply tree's leaves would
    * cost thousands of seconds on a real position. This position keeps the tree small enough (a lone opposing king
    * passes on most rolls) and its features cheap, so the wiring can be exercised end to end in milliseconds.
    */
  private val endgame = FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - -").toOption.get.withDicePool(List(6, 6, 6))
  private val narrow  = ExpectimaxConfig(candidateLimit = 1)

  private def valuePackage: ModelPackage =
    ModelPackage
      .loadFiles(ContractFixtures.valueModel, ContractFixtures.valueManifest, engineVersion)
      .getOrElse(fail("the value fixture must load"))

  /** A package whose manifest is edited after loading — the only way to build one whose graph disagrees with it while
    * every other check still passes, since a manifest that describes different bytes fails the digest first.
    */
  private def contradicting(edit: ModelManifest => ModelManifest): ModelPackage =
    val loaded = valuePackage
    loaded.copy(manifest = edit(loaded.manifest))

  private def rejection(result: Either[String, ?]): String =
    result.swap.getOrElse(fail(s"expected a rejection, got $result"))

  test("a one-ply bot opened from a package validates the graph it is about to serve"):
    val bot = OnnxEvalSearch.fromPackage(valuePackage).getOrElse(fail("a conforming package must open"))
    try
      assertEquals(bot.onnxEval(state, Color.White) >= 0, true)
      // The extractor came from the manifest's schema, not from the call site.
      assertEquals(valuePackage.schema.id, FeatureSchema.Kcp13.id)
    finally bot.close()

  test("a graph whose tensor names contradict the manifest is refused when the session opens"):
    val refused = rejection(OnnxEvalSearch.fromPackage(contradicting(_.copy(inputName = "features"))))
    assertEquals(refused, "ONNX model must expose exactly one input named 'features'; found [input]")

  test("a graph whose width contradicts the manifest is refused when the session opens"):
    val refused = rejection(
      OnnxEvalSearch.fromPackage(contradicting(_.copy(featureSchema = "kcp-mobility-27-v1", featureCount = 27)))
    )
    assertEquals(refused, "input 'input' must have shape [batch,27] with a dynamic batch dimension; got [batch,13]")

  test("a graph with a fixed batch dimension is refused, fixture and all"):
    // This one needs no doctoring: the fixture's own manifest describes it correctly, and the graph is still wrong for
    // a search that scores a whole chance node per call.
    val packaged = ModelPackage
      .loadFiles(ContractFixtures.staticBatchModel, ContractFixtures.staticBatchManifest, engineVersion)
      .getOrElse(fail("the static-batch fixture must load"))
    val refused = rejection(OnnxEvalSearch.fromPackage(packaged))
    assertEquals(refused, "input 'input' must have shape [batch,13] with a dynamic batch dimension; got [1,13]")

  test("the expectimax bot built from packages checks its leaf and still plays"):
    val bot = OnnxExpectimaxSearch.fromPackages(valuePackage, narrow).getOrElse(fail("a conforming leaf must open"))
    try assert(bot.findBestMove(endgame).isDefined)
    finally bot.close()

  test("a leaf whose graph contradicts its manifest fails construction, not the first inference"):
    val refused = rejection(OnnxExpectimaxSearch.fromPackages(contradicting(_.copy(outputName = "variable")), narrow))
    assertEquals(refused, "ONNX model must expose exactly one output named 'variable'; found [output]")

  test("a model trained for another role cannot be the leaf evaluator"):
    val collapse = ModelPackage
      .loadFiles(ContractFixtures.valueModel, ContractFixtures.collapseManifest, engineVersion)
      .getOrElse(fail("the collapse fixture must load"))
    val refused = rejection(OnnxExpectimaxSearch.fromPackages(collapse, narrow))
    assert(refused.contains("modelRole 'chance-collapse' cannot serve as the leaf evaluator"), refused)

  test("a hook whose graph contradicts its manifest fails construction and leaks no session"):
    // The collapse model is the third session opened, so a refusal there has two earlier ones to close.
    var created                                                           = 0
    var closed                                                            = List.empty[Int]
    val sessionFactory: OnnxExpectimaxSearchInitialization.SessionFactory = (path, features) =>
      created += 1
      val index = created
      new OnnxEvalSearch(path, features):
        override def close(): Unit =
          super.close()
          closed = index :: closed

    val contradictingContract = contradicting(_.copy(inputName = "features")).manifest
    val thrown                = intercept[RuntimeException]:
      OnnxExpectimaxSearchInitialization.initialize(
        ContractFixtures.valueModel.toString,
        ExpectimaxConfig(),
        FeatureSchema.Kcp13.extract,
        OnnxSearchOptions(
          rootRescore = Some(RootRescoreModel(ContractFixtures.valueModel.toString, FeatureSchema.Kcp13.extract, 0.5)),
          chanceCollapse = Some(
            CollapseModel(
              ContractFixtures.valueModel.toString,
              FeatureSchema.Kcp13.extract,
              contract = Some(contradictingContract)
            )
          )
        ),
        sessionFactory = sessionFactory
      )
    assert(thrown.getMessage.contains("exactly one input named 'features'"), thrown.getMessage)
    assertEquals(created, 3)
    assertEquals(closed.sorted, List(1, 2, 3), "every opened session must be closed, including the refused one")

  test("a model configured by path alone is still opened unchecked"):
    // The check is a property of having loaded a package, not a new requirement on every caller: the arena runners
    // pass paths and must keep working. The fixture here is deliberately the one the contract REJECTS — a batch axis
    // fixed at one row — because a conforming model could not tell this test apart from one where the path-based
    // constructor had quietly started validating.
    val refusedByContract = ModelPackage
      .loadFiles(ContractFixtures.staticBatchModel, ContractFixtures.staticBatchManifest, engineVersion)
      .getOrElse(fail("the static-batch fixture must load"))
    assert(
      OnnxEvalSearch.fromPackage(refusedByContract).isLeft,
      "precondition: the package route refuses this graph"
    )

    // Neither entry point searches with it: an unchecked graph is exactly one that may not survive inference, which is
    // the trade a path-based caller makes. What is asserted is that construction is allowed to happen at all.
    val onePly = new OnnxEvalSearch(ContractFixtures.staticBatchModel.toString, FeatureSchema.Kcp13.extract)
    onePly.close()
    val bot = new OnnxExpectimaxSearch(ContractFixtures.staticBatchModel.toString, narrow, FeatureSchema.Kcp13.extract)
    bot.close()

    // And the conforming model still plays through the same unchecked path, so the entry point is not merely tolerant
    // of bad graphs but unchanged for good ones.
    val playing = new OnnxExpectimaxSearch(ContractFixtures.valueModel.toString, narrow, FeatureSchema.Kcp13.extract)
    try assert(playing.findBestMove(endgame).isDefined)
    finally playing.close()

  test("fromPackage populates each hook's contract so its session is checked too"):
    val collapse = ModelPackage
      .loadFiles(ContractFixtures.valueModel, ContractFixtures.collapseManifest, engineVersion)
      .getOrElse(fail("the collapse fixture must load"))
    val model = CollapseModel.fromPackage(collapse).getOrElse(fail("the collapse package must be accepted"))
    assertEquals(model.contract.map(_.modelId), Some("synthetic-kcp13-collapse"))

    val preRank = ModelPackage
      .loadFiles(ContractFixtures.valueModel, ContractFixtures.preRankManifest, engineVersion)
      .getOrElse(fail("the pre-rank fixture must load"))
    assertEquals(
      PreRankModel.fromPackage(preRank).map(_.contract.map(_.modelId)),
      Right(Some("synthetic-kcp13-prerank"))
    )

    assertEquals(
      RootRescoreModel.fromPackage(valuePackage, weight = 0.5).map(_.contract.map(_.modelId)),
      Right(Some("synthetic-kcp13-value"))
    )
    assert(
      rejection(RootRescoreModel.fromPackage(collapse, weight = 0.5))
        .contains("modelRole 'chance-collapse' cannot serve as 'position-value'")
    )
