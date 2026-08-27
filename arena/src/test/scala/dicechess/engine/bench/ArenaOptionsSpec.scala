package dicechess.engine.bench

import com.monovore.decline.Command
import munit.FunSuite

class ArenaOptionsSpec extends FunSuite:

  test("all options can be instantiated") {
    val _ = ArenaOptions.baseBotOpt()
    val _ = ArenaOptions.opponentOpt()
    val _ = ArenaOptions.gamesOpt()
    val _ = ArenaOptions.seedOpt()
    val _ = ArenaOptions.presetsOpt()
    val _ = ArenaOptions.wideKOpt()
    val _ = ArenaOptions.narrowKOpt()
    val _ = ArenaOptions.positionsOpt()
    val _ = ArenaOptions.limitOpt()

    val _ = ArenaOptions.modelPathOpt
    val _ = ArenaOptions.rescoreModelPathOpt
    val _ = ArenaOptions.rescoreWeightOpt
    val _ = ArenaOptions.optionalRescoreWeightOpt
    val _ = ArenaOptions.optionalRescoreFeaturesOpt
    val _ = ArenaOptions.jsonPathOpt
    val _ = ArenaOptions.sprtConfigOpt
    val _ = ArenaOptions.preRankWithModelOpt
    val _ = ArenaOptions.optionalTtCapacityOpt
    val _ = ArenaOptions.bookPathOpt
    val _ = ArenaOptions.reqBookPathOpt
    val _ = ArenaOptions.corpusPathOpt
  }

  test("games validation") {
    val command = Command("test", "test")(ArenaOptions.gamesOpt(50))
    assert(command.parse(Seq("--games", "10"), sys.env).isRight)
    assert(command.parse(Seq("--games", "0"), sys.env).isLeft)
    assert(command.parse(Seq("--games", "-5"), sys.env).isLeft)
  }

  test("wide-k validation") {
    val command = Command("test", "test")(ArenaOptions.wideKOpt(48))
    assert(command.parse(Seq("--wide-k", "10"), sys.env).isRight)
    assert(command.parse(Seq("--wide-k", "0"), sys.env).isLeft)
  }

  test("narrow-k validation") {
    val command = Command("test", "test")(ArenaOptions.narrowKOpt(24))
    assert(command.parse(Seq("--narrow-k", "10"), sys.env).isRight)
    assert(command.parse(Seq("--narrow-k", "0"), sys.env).isLeft)
  }

  test("positions validation") {
    val command = Command("test", "test")(ArenaOptions.positionsOpt(300))
    assert(command.parse(Seq("--positions", "10"), sys.env).isRight)
    assert(command.parse(Seq("--positions", "0"), sys.env).isLeft)
  }

  test("limit validation") {
    val command = Command("test", "test")(ArenaOptions.limitOpt(24))
    assert(command.parse(Seq("--limit", "10"), sys.env).isRight)

    command.parse(Seq("--limit", "0"), sys.env) match
      case Left(help) => assert(help.toString.contains("limit must be > 0"))
      case Right(_)   => fail("Expected Left for invalid limit")
  }

  test("every advertised feature set has an extractor") {
    // FeatureSets drives both the `--features` validation and its per-side twin in OnnxModelDuelRunner, while
    // extractFeatures does the actual dispatch. Adding a set to one and not the other passes validation and then
    // dies with `Unknown feature set` at run time, after the models are already loaded.
    ArenaOptions.FeatureSets.foreach(set => ArenaOptions.extractFeatures(set))
  }

  test("rescore weight validation") {
    val command = Command("test", "test")(ArenaOptions.optionalRescoreWeightOpt)
    assert(command.parse(Seq("--rescore-weight", "0"), sys.env).isRight)
    assert(command.parse(Seq("--rescore-weight", "1"), sys.env).isRight)
    assert(command.parse(Seq("--rescore-weight", "-0.1"), sys.env).isLeft)
    assert(command.parse(Seq("--rescore-weight", "1.1"), sys.env).isLeft)
    assert(command.parse(Seq("--rescore-weight", "NaN"), sys.env).isLeft)
  }

  test("transposition-table capacity validation") {
    val command = Command("test", "test")(ArenaOptions.optionalTtCapacityOpt)
    assert(command.parse(Seq("--tt-capacity", "262144"), sys.env).isRight)
    assert(command.parse(Seq("--tt-capacity", "3"), sys.env).isLeft)
    assert(command.parse(Seq("--tt-capacity", "0"), sys.env).isLeft)
    assert(command.parse(Seq("--tt-capacity", "8388608"), sys.env).isLeft)
  }

  test("parseAndRun reports a failure inside the command body as Left") {
    // Not a redundant wrapper test: these are Command[Unit], so decline runs the body during `parse`. If the catch
    // does not wrap `parse` itself, this throws instead of returning, and every runner loses its error message.
    val command = Command("test", "test")(ArenaOptions.gamesOpt(1).map(_ => sys.error("boom")))
    assertEquals(ArenaOptions.parseAndRun(command, Array("--games", "1")), Left("boom"))
  }

  test("sprt config parsing") {
    val command = Command("test", "test")(ArenaOptions.sprtConfigOpt)
    assert(command.parse(Seq("--sprt", "-2,2,0.05,0.05"), sys.env).isRight)
    assert(command.parse(Seq("--sprt", "invalid"), sys.env).isLeft)
    assert(command.parse(Seq("--sprt", "NaN,2,0.05,0.05"), sys.env).isLeft)
    assert(command.parse(Seq("--sprt", "-2,Infinity,0.05,0.05"), sys.env).isLeft)
    assert(command.parse(Seq("--sprt", "-2,2,NaN,0.05"), sys.env).isLeft)
    assert(command.parse(Seq("--sprt", "-2,2,0.05,Infinity"), sys.env).isLeft)
  }
