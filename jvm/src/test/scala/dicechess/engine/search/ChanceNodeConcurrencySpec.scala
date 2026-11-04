// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.search.ChanceNodeOracle.{fixtures, LeafPolicy}
import munit.FunSuite

import java.util.concurrent.{Callable, CyclicBarrier, Executors, TimeUnit}
import scala.util.Random

/** JVM-only: independent [[ChanceNode]] invocations share nothing, so concurrent expansions of the same and of
  * different fixtures — with and without pruning windows, through [[ExpectimaxSearch]] as well as directly — produce
  * exactly the sequential results (#394).
  */
class ChanceNodeConcurrencySpec extends FunSuite:

  /** Whole depth-3 trees several times over on eight threads: fast uninstrumented, but scoverage instruments the
    * move-generation hot path at roughly 30x, so the limit is raised rather than the assertions thinned.
    */
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(5, "min")

  private val spread: (GameState, Color) => Int = (state, color) =>
    val own = if color.isWhite then state.whitePieces else state.blackPieces
    Evaluator.evaluateMaterial(state, color) + 3 * own.count - 2 * state.kings.count

  private val materialBatch: (Array[GameState], Color) => Array[Int] =
    (states, color) => states.map(Evaluator.evaluateMaterial(_, color))

  private def runAll[A](tasks: List[() => A]): List[A] =
    val pool = Executors.newFixedThreadPool(8)
    try
      val futures = tasks.map(task => pool.submit(new Callable[A] { def call(): A = task() }))
      futures.map(_.get(5, TimeUnit.MINUTES))
    finally pool.shutdownNow()

  test("lazy scratch and probe opt-out remain isolated across independent concurrent expansions"):
    val cases = for
      (_, state) <- List(fixtures.head, fixtures.last)
      probe      <- List(false, true)
      early      <- List(false, true)
    yield (state, probe, early)
    def one(state: GameState, probing: Boolean, early: Boolean): ChanceNode.Result =
      val policy = new ChanceNode.Policy:
        override def probingEnabled: Boolean                                           = probing
        def lowestValue: Double                                                        = 0.0
        def highestValue: Double                                                       = 10.0
        override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]] =
          Some(Array.fill(rolled.length)(ChanceNode.Value.exact(2.0)))
        def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
          ChanceNode.Value.exact(2.0)
      ChanceNode.expand(
        state,
        ExpectimaxSearch.utilityBounds(Color.Black),
        SearchControl.Unbounded,
        if early then 11.0 else 0.0,
        Double.PositiveInfinity,
        policy
      )
    val expected = cases.map(one.tupled)
    val repeated = cases ++ cases.reverse
    assertEquals(runAll(repeated.map(c => () => one.tupled(c))), repeated.map(c => expected(cases.indexOf(c))))

  test("uncut opt-out expansions overlap after allocating independent suffix scratch"):
    val cases   = List(fixtures.head._2, fixtures.last._2, fixtures.last._2, fixtures.head._2)
    val barrier = new CyclicBarrier(cases.length)
    def one(state: GameState, overlap: Boolean): ChanceNode.Result =
      var first  = true
      val policy = new ChanceNode.Policy:
        override def probingEnabled: Boolean                                                          = false
        def lowestValue: Double                                                                       = 0.0
        def highestValue: Double                                                                      = 10.0
        def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
          if first then
            first = false
            if overlap then barrier.await(30, TimeUnit.SECONDS)
          ChanceNode.Value.exact(2.0)
      ChanceNode.expand(
        state,
        ExpectimaxSearch.utilityBounds(Color.Black),
        SearchControl.Unbounded,
        1.5,
        2.5,
        policy
      )
    val expected = cases.map(one(_, false))
    assert(expected.forall(_.outcome.exactOption.contains(2.0)))
    assertEquals(runAll(cases.map(state => () => one(state, true))), expected)

  test("concurrent chance-node expansions reproduce the sequential outcomes"):
    val cases = for
      (_, afterTurn) <- fixtures
      perspective    <- List(Color.White, Color.Black)
      alphaOffset    <- List(Double.NegativeInfinity, -20.0, 0.0, 15.0)
    yield (afterTurn, perspective, alphaOffset)
    def one(afterTurn: GameState, perspective: Color, alphaOffset: Double): SearchOutcome[Double] =
      val bounds     = ExpectimaxSearch.utilityBounds(perspective)
      val maximizing = bounds.isMaximizing(afterTurn.activeColor)
      val oracle     = ChanceNodeOracle.expectationOver56(afterTurn, bounds, spread)
      val alpha      = if alphaOffset.isInfinite then alphaOffset else oracle + alphaOffset
      val policy     = new LeafPolicy(bounds, maximizing, spread, failSoft = true, probing = true)
      ChanceNode.expand(afterTurn, bounds, SearchControl.Unbounded, alpha, Double.PositiveInfinity, policy).outcome
    val sequential = cases.map(one.tupled)
    val repeated   = cases ++ cases.reverse ++ Random(11).shuffle(cases)
    val concurrent = runAll(repeated.map(c => () => one.tupled(c)))
    assertEquals(concurrent, repeated.map(c => sequential(cases.indexOf(c))))

  test("concurrent ExpectimaxSearch invocations at both depths reproduce the sequential moves and telemetry"):
    val f10    = ChanceNodeOracle.parse("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP")
    val sparse = ChanceNodeOracle.parse("k7/8/8/8/8/8/8/R3K3 w - - 0 1 NBK")
    def one(depth: Int, seed: Int): (Option[String], Option[Int], Option[RootSearchStats]) =
      var stats = Option.empty[RootSearchStats]
      val bot   = ExpectimaxSearch(
        materialBatch,
        ExpectimaxConfig(candidateLimit = 2, searchDepth = depth),
        statsSink = s => stats = Some(s)
      )
      val state = if depth == 2 then f10 else sparse
      val move  = bot.findBestMove(state, Random(seed))
      (
        move.map(_.moves.map(m => m.fromSquare.toNotation + m.toSquare.toNotation).mkString(" ")),
        move.map(_.score),
        stats
      )
    val cases      = for depth <- List(2, 3); seed <- 0 to 1 yield (depth, seed)
    val sequential = cases.map(one.tupled)
    val concurrent = runAll((cases ++ cases).map(c => () => one.tupled(c)))
    assertEquals(concurrent, sequential ++ sequential)
