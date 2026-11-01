// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

import scala.util.Random

/** [[ExpectimaxSearch]] under a [[SearchControl]] (#393): deterministic work budgets, an injected clock, the check
  * before cache hits, nested interruption at depth 3 and the root fallback order — on the public synthetic fixtures F4,
  * F7 and F10 of the traversal and search contracts page. No assertion depends on elapsed time.
  */
class ExpectimaxSearchControlSuite extends FunSuite:

  /** The depth-3 cases run whole trees several times over; under scoverage instrumentation that is well past munit's
    * default 30 s, so the limit is raised rather than the assertions thinned.
    */
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(5, "min")

  private val materialBatch: (Array[GameState], Color) => Array[Int] =
    (states, color) => states.map(state => Evaluator.evaluateMaterial(state, color))

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  private def uci(moves: List[Move]): String =
    moves.map(m => m.fromSquare.toNotation + m.toSquare.toNotation).mkString(" ")

  /** F10: a developed opening position with a real reply tree, rolled `N B P`. */
  private val f10 = parse("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP")

  /** F7: `Nb3xc5` captures the king with the first die; `P P` dice would otherwise allow a longer turn. */
  private val f7 = parse("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP")

  /** F4: the only pawn is blocked and the roll is all pawns, so the mover has no legal turn at all. */
  private val f4 = parse("4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1 PPP")

  /** The same four-king-step position the main spec uses: one candidate completes, three are pruned. */
  private val kingSteps = parse("7k/8/8/b7/8/8/5P2/4K3 w - - 0 1").withDicePool(List(6, 2, 3))

  /** The sparse rook ending the JMH benchmark uses for depth 3: both recursive chance layers in milliseconds. */
  private val sparse = parse("k7/8/8/8/8/8/8/R3K3 w - - 0 1 NBK")

  /** F10 carries the depth-2 boundaries; its full depth-3 tree is minutes of work, so depth 3 uses `sparse`. */
  private def boundaryPosition(depth: Int): GameState = if depth == 2 then f10 else sparse

  final private class Probe(depth: Int, limit: Int = 2, tt: Option[TranspositionTable] = None):
    var stats: Option[RootSearchStats] = None
    val bot                            = ExpectimaxSearch(
      materialBatch,
      ExpectimaxConfig(candidateLimit = limit, searchDepth = depth),
      statsSink = s => stats = Some(s),
      tt = tt
    )
    def run(state: GameState, control: SearchControl): Option[ScoredSequence] =
      stats = None
      bot.findBestMove(state, control, Random(0))

  /** A clock that advances by one per read, so a deadline of `n` means "the n-th check cancels". */
  final private class StepClock:
    var reads               = 0
    def apply(): () => Long = () =>
      reads += 1
      reads.toLong

  for depth <- List(2, 3) do
    test(s"depth $depth: Unbounded and an ample budget reproduce the untimed search move for move"):
      // Depth 3 keeps to the sparse ending: the four-king-step tree is seconds per run once instrumented.
      for state <- if depth == 2 then List(f10, kingSteps) else List(sparse) do
        val probe     = new Probe(depth)
        val reference = probe.bot.findBestMove(state, Random(0))
        val refStats  = probe.stats
        val unbounded = probe.run(state, SearchControl.Unbounded)
        assertEquals(unbounded.map(s => (uci(s.moves), s.score)), reference.map(s => (uci(s.moves), s.score)))
        assertEquals(probe.stats, refStats)
        val budgeted = probe.run(state, SearchControl.withBudget(new BudgetedRules.Budget(Long.MaxValue)))
        assertEquals(budgeted.map(s => (uci(s.moves), s.score)), reference.map(s => (uci(s.moves), s.score)))
        assertEquals(probe.stats, refStats)
        assert(!probe.stats.get.deadlineTruncated)

  test("the deadline adapter is exactly SearchControl.untilDeadline"):
    val probe       = new Probe(2)
    val far         = Long.MaxValue - 1
    val viaDeadline = probe.bot.findBestMove(f10, far, Random(0)).map(s => uci(s.moves))
    val viaControl  = probe.run(f10, SearchControl.untilDeadline(far)).map(s => uci(s.moves))
    assertEquals(viaDeadline, viaControl)
    assert(!probe.stats.get.deadlineTruncated)

  test("the untimed and budget-only paths never read the clock"):
    val probe    = new Probe(2)
    val poisoned = () => fail("clock read on an untimed search"): Long
    assert(probe.run(f10, SearchControl(None, clockNanos = poisoned)).isDefined)
    assert(
      probe.run(f10, SearchControl(Some(new BudgetedRules.Budget(Long.MaxValue)), clockNanos = poisoned)).isDefined
    )

  for depth <- List(2, 3) do
    test(s"F10 at depth $depth: Budget(exact) completes identically, Budget(exact - 1) abandons, 0 and 1 fall back"):
      val probe  = new Probe(depth)
      val state  = boundaryPosition(depth)
      val ample  = new BudgetedRules.Budget(Long.MaxValue)
      val full   = probe.run(state, SearchControl.withBudget(ample)).map(s => (uci(s.moves), s.score))
      val fullSt = probe.stats.get
      val exact  = ample.used
      assert(exact > 0, "the search must have charged nested reply generation to the budget")
      assert(!fullSt.deadlineTruncated)

      val same    = new BudgetedRules.Budget(exact)
      val atExact = probe.run(state, SearchControl.withBudget(same)).map(s => (uci(s.moves), s.score))
      assertEquals(atExact, full)
      assertEquals(probe.stats, Some(fullSt))
      assertEquals(same.used, exact)
      assert(!same.exhausted, "an exactly sufficient budget is spent, not exhausted")

      val short   = new BudgetedRules.Budget(exact - 1)
      val cutMove = probe.run(state, SearchControl.withBudget(short))
      assert(cutMove.isDefined, "the anytime contract still owes a legal turn")
      assert(short.exhausted)
      val cutSt = probe.stats.get
      assertEquals(cutSt.candidatesAbandoned, 1)
      assert(cutSt.deadlineTruncated)
      assertEquals(cutSt.legalTurns, fullSt.legalTurns)
      assertEquals(cutSt.candidatesSelected, fullSt.candidatesSelected)

      for limit <- List(0L, 1L) do
        val tiny = new BudgetedRules.Budget(limit)
        val move = probe.run(state, SearchControl.withBudget(tiny))
        assert(move.isDefined, s"Budget($limit) must still return a legal turn")
        assert(probe.stats.get.fellBackToPreRank, s"Budget($limit): expected the pre-rank fallback, got ${probe.stats}")
        assertEquals(probe.stats.get.candidatesAbandoned, 1)
        assertEquals(probe.stats.get.candidatesCompleted, 0)

  test("an already-exhausted budget is cancelled at entry and yields the pre-ranker's top pick"):
    val probe  = new Probe(2, limit = 1)
    val budget = new BudgetedRules.Budget(0)
    assertEquals(BudgetedRules.turnPaths(f10, budget), BudgetedRules.Outcome.Incomplete(0L))
    val control = SearchControl.withBudget(budget)
    assert(control.isCancelled)
    val move = probe.run(f10, control)
    assert(move.isDefined)
    assert(probe.stats.get.fellBackToPreRank)
    assertEquals(probe.stats.get.candidatesAbandoned, 1)
    // With a single candidate the pre-ranker's pick is unambiguous: the material-best turn, scored by the pre-ranker.
    val preRanked = probe.run(f10, SearchControl.Unbounded)
    assertEquals(move.map(s => uci(s.moves)), preRanked.map(s => uci(s.moves)))

  test("cancellation is checked before the transposition-table hit path (F10)"):
    val table = new TranspositionTable(1 << 12)
    val probe = new Probe(2, tt = Some(table))
    // Warm the table: every root candidate's chance node is now an EXACT entry.
    assert(probe.run(f10, SearchControl.Unbounded).isDefined)
    val warm = probe.run(f10, SearchControl.Unbounded)
    assert(warm.isDefined)
    assert(probe.stats.get.ttHits > 0, s"the table must answer the second run, got ${probe.stats}")
    // An invocation that is already cancelled must not be rescued by those hits.
    val cancelled = probe.run(f10, SearchControl.untilDeadline(deadlineNanos = 0L, clockNanos = () => 1L))
    assert(cancelled.isDefined, "the anytime contract still owes a legal turn")
    val st = probe.stats.get
    assertEquals(st.ttHits, 0)
    assertEquals(st.ttCutoffs, 0)
    assertEquals(st.candidatesAbandoned, 1)
    assert(st.fellBackToPreRank, s"expected the pre-rank fallback, got $st")
    // And the table was not written by the cancelled run either: a fourth untimed run still hits.
    assert(probe.run(f10, SearchControl.Unbounded).isDefined)
    assert(probe.stats.get.ttHits > 0)

  for depth <- List(2, 3) do
    test(
      s"depth $depth: an injected clock cancels deterministically mid-tree and never ranks an interrupted candidate"
    ):
      val probe = new Probe(depth, limit = if depth == 2 then 4 else 2)
      val state = boundaryPosition(depth)
      // How many checks does the complete search make? Every deadline below that lands somewhere inside the tree.
      val counting = new StepClock
      val full     = probe.run(state, SearchControl.untilDeadline(Long.MaxValue - 1, counting()))
      assert(full.isDefined)
      val totalChecks = counting.reads
      assert(totalChecks > 10, s"expected many yield points, saw $totalChecks")
      val fullSt    = probe.stats.get
      val deadlines =
        if depth == 2 then List(1L, 2L, 3L, totalChecks / 4L, totalChecks / 2L, totalChecks - 1L)
        else List(1L, totalChecks / 2L, totalChecks - 1L)
      for deadline <- deadlines do
        val clock = new StepClock
        val move  = probe.run(state, SearchControl.untilDeadline(deadline, clock()))
        assert(move.isDefined, s"deadline $deadline: the anytime contract still owes a legal turn")
        assertEquals(clock.reads.toLong, deadline, s"deadline $deadline: the cancelling check must be the last read")
        val st = probe.stats.get
        assert(st.candidatesAbandoned <= 1, s"deadline $deadline: at most one candidate is cut, got $st")
        assert(st.deadlineTruncated, s"deadline $deadline: a mid-tree cut must report truncation, got $st")
        assert(
          st.candidatesCompleted + st.cutoffs + st.ttHits + st.ttCutoffs + st.candidatesAbandoned <= st.candidatesSelected,
          s"deadline $deadline: counters must not exceed the selected set, got $st"
        )
        assertEquals(st.legalTurns, fullSt.legalTurns)
        if st.candidatesCompleted == 0 && st.ttHits == 0 then
          assert(st.fellBackToPreRank, s"deadline $deadline: nothing completed, so the pre-ranker must decide, got $st")
        else assert(!st.fellBackToPreRank, s"deadline $deadline: a completed candidate must be ranked, got $st")

  test("nested interruption at depth 3: a budget that runs out inside a subtree abandons the whole root candidate"):
    val probe = new Probe(3)
    val ample = new BudgetedRules.Budget(Long.MaxValue)
    assert(probe.run(sparse, SearchControl.withBudget(ample)).isDefined)
    val fullSt = probe.stats.get
    // Probe every budget that cuts the first candidate somewhere inside its depth-3 subtree.
    val exact = ample.used
    for limit <- List(exact / 4, exact - 1) do
      val budget = new BudgetedRules.Budget(limit)
      val move   = probe.run(sparse, SearchControl.withBudget(budget))
      assert(move.isDefined, s"Budget($limit): the anytime contract still owes a legal turn")
      assert(budget.exhausted, s"Budget($limit) must be the limiting factor")
      val st = probe.stats.get
      assertEquals(st.candidatesAbandoned, 1, s"Budget($limit): exactly one candidate is abandoned, got $st")
      assert(st.deadlineTruncated, s"Budget($limit): expected truncation, got $st")
      assertEquals(st.legalTurns, fullSt.legalTurns)

  test("F7: an immediate root king capture short-circuits with TerminalWinScore before any control check"):
    val probe  = new Probe(2)
    val budget = new BudgetedRules.Budget(0)
    assertEquals(BudgetedRules.turnPaths(f10, budget), BudgetedRules.Outcome.Incomplete(0L)) // exhaust it
    val control = SearchControl(Some(budget), deadlineNanos = 0L, clockNanos = () => 1L)
    assert(control.isCancelled)
    val move = probe.run(f7, control)
    assertEquals(move.map(s => (uci(s.moves), s.score)), Some(("b3c5", SearchScoring.TerminalWinScore)))
    assertEquals(probe.stats, Some(RootSearchStats(legalTurns = probe.stats.get.legalTurns, 0, 0)))
    assert(probe.stats.get.legalTurns > 1, "F7 has normal turns too; the capture must win over them")
    assertEquals(budget.used, 0L, "the root never charges its own legal-turn generation to the budget")

  test("F4: a forced pass is None under every control and reports an all-zero record"):
    for control <- List(
        SearchControl.Unbounded,
        SearchControl.withBudget(new BudgetedRules.Budget(0)),
        SearchControl.untilDeadline(deadlineNanos = 0L, clockNanos = () => 1L)
      )
    do
      val probe = new Probe(2)
      assertEquals(probe.run(f4, control), None)
      assertEquals(probe.stats, Some(RootSearchStats(0, 0, 0)))

  test("F4 as a chance node: the opponent's blocked roll keeps its full forced-pass weight under a budget"):
    // Black to move; after any Black turn White's only pawn stays blocked, so White's P-only rolls are forced passes
    // that the chance node evaluates with their full weight. A budgeted run must agree with the untimed one exactly.
    val state     = parse("4k3/8/8/8/8/p7/P7/R3K3 b - - 0 1 K")
    val probe     = new Probe(2)
    val reference = probe.run(state, SearchControl.Unbounded).map(s => (uci(s.moves), s.score))
    val refStats  = probe.stats
    val budgeted  = probe.run(state, SearchControl.withBudget(new BudgetedRules.Budget(Long.MaxValue)))
    assertEquals(budgeted.map(s => (uci(s.moves), s.score)), reference)
    assertEquals(probe.stats, refStats)
    assert(
      refStats.exists(_.candidatesCompleted >= 1),
      s"the forced-pass rolls must still complete a candidate: $refStats"
    )
