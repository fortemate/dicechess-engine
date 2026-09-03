package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

import scala.util.Random

/** Behavioural tests for the 2-ply search, driven by the engine's own material evaluator (no model needed): the point
  * is the lookahead, not the evaluator. The key case is a position where the one-ply [[GreedySearch]] grabs material
  * and expectimax, seeing the reply, declines it.
  */
class ExpectimaxSearchSpec extends FunSuite:

  /** Material evaluator as a batch, so the search's injected `evalBatch` is exercised for real. */
  private val materialBatch: (Array[GameState], Color) => Array[Int] =
    (states, color) => states.map(state => Evaluator.evaluateMaterial(state, color))

  private def search(config: ExpectimaxConfig = ExpectimaxConfig()) =
    ExpectimaxSearch(materialBatch, config)

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  private def uci(moves: List[Move]): String =
    moves.map(m => m.fromSquare.toNotation + m.toSquare.toNotation).mkString(" ")

  test("returns a legal turn when one exists"):
    val state = parse("1r4k1/p4ppp/8/8/8/8/5PPP/R5K1 w - - 0 1").withDicePool(List(2, 2, 4))
    assert(search().findBestMove(state, Random(0)).isDefined)

  test("is deterministic for a fixed seed"):
    val state = parse("1r4k1/p4ppp/8/8/8/8/5PPP/R5K1 w - - 0 1").withDicePool(List(2, 2, 4))
    val a     = search().findBestMove(state, Random(7)).map(s => uci(s.moves))
    val b     = search().findBestMove(state, Random(7)).map(s => uci(s.moves))
    assertEquals(a, b)

  test("plays an immediate king capture and scores it as a terminal win"):
    // A rook die lets the a1 rook sweep the open a-file onto the black king at a8.
    val state  = parse("k7/8/8/8/8/8/8/R3K3 w - - 0 1").withDicePool(List(1, 1, 4))
    val result = search().findBestMove(state, Random(0))
    assert(result.isDefined)
    val chosen = result.get
    assertEquals(chosen.score, SearchScoring.TerminalWinScore)
    assertEquals(chosen.moves.last.toSquare.toNotation, "a8")

  test("declines a material grab that hangs to the opponent's reply — greedy walks in, expectimax does not"):
    // White: Ra1, Kg1, pawns f2/g2/h2. Black: Rb8, Kg8, pawns f7/g7/h7, and a loose pawn on a7.
    // Dice 2,2,4: only the rook die (4) is usable (no knights, no pawn die), so every turn is one rook move.
    // Grabbing a7 (Rxa7) wins a pawn now but abandons the first rank; over the opponent's replies that is far worse
    // than keeping the rook home. GreedySearch takes the pawn; the 2-ply search refuses it.
    val state = parse("1r4k1/p4ppp/8/8/8/8/5PPP/R5K1 w - - 0 1").withDicePool(List(2, 2, 4))

    val greedyMove = GreedySearch.findBestMove(state, Random(0)).map(s => uci(s.moves))
    assertEquals(greedyMove, Some("a1a7"), "precondition: greedy grabs the pawn")

    val expectimaxMove = search().findBestMove(state, Random(0)).map(s => uci(s.moves))
    assert(
      expectimaxMove.exists(_ != "a1a7"),
      s"expected the 2-ply search to decline the hanging grab a1a7, got $expectimaxMove"
    )

  test("every evaluated leaf is from the mover's perspective, including forced passes"):
    // The opponent is a lone king: on every roll without a king die it has no legal move and must pass. All leaves —
    // ordinary replies and passes alike — must be scored with the mover to move, so an evaluator that reads
    // side-to-move never sees the opponent's turn. This guards the forced-pass leaf against a regression to the
    // pre-endTurn state (invisible to material, but wrong for any richer evaluator).
    val state = parse("4k3/8/8/8/8/8/8/R3K3 w - - 0 1").withDicePool(List(1, 4, 6))
    val checkingBatch: (Array[GameState], Color) => Array[Int] =
      (states, color) =>
        states.foreach(s => assert(s.activeColor == color, s"leaf must be from the mover's perspective"))
        states.map(s => Evaluator.evaluateMaterial(s, color))
    assert(ExpectimaxSearch(checkingBatch).findBestMove(state, Random(0)).isDefined)

  test("honours an already-elapsed deadline and still returns a legal turn"):
    // Anytime contract: past the deadline no candidate can be expanded at all, so the turn comes from the pre-ranker
    // — but a legal turn always comes back (the exact turn under a deadline is non-deterministic and not asserted).
    val state = parse("1r4k1/p4ppp/8/8/8/8/5PPP/R5K1 w - - 0 1").withDicePool(List(2, 2, 4))
    assert(search().findBestMove(state, System.nanoTime(), Random(0)).isDefined)

  test("candidateLimit must be positive"):
    intercept[IllegalArgumentException](ExpectimaxConfig(candidateLimit = 0))

  test("searchDepth accepts the implemented depths and defaults to two"):
    assertEquals(ExpectimaxConfig().searchDepth, 2)
    ExpectimaxConfig(searchDepth = 2)
    ExpectimaxConfig(searchDepth = 3)
    intercept[IllegalArgumentException](ExpectimaxConfig(searchDepth = 1))
    intercept[IllegalArgumentException](ExpectimaxConfig(searchDepth = 4))

  test("depth 3 abandons the whole root candidate when an inner deadline has elapsed"):
    val state = parse("4k3/8/8/8/8/8/8/R3K3 w - - 0 1").withDicePool(List(1, 4, 6))
    var stats = Option.empty[RootSearchStats]
    val bot   = ExpectimaxSearch(
      materialBatch,
      ExpectimaxConfig(candidateLimit = 1, searchDepth = 3),
      statsSink = value => stats = Some(value)
    )
    assert(bot.findBestMove(state, System.nanoTime(), Random(0)).isDefined)
    assertEquals(stats.map(_.candidatesCompleted), Some(0))
    assertEquals(stats.map(_.candidatesAbandoned), Some(1))
    assert(stats.exists(_.fellBackToPreRank))

  test("RootRescore.weight must be in [0, 1]"):
    intercept[IllegalArgumentException](RootRescore((_, _) => Array.empty, -0.1))
    intercept[IllegalArgumentException](RootRescore((_, _) => Array.empty, 1.5))
    intercept[IllegalArgumentException](RootRescore((_, _) => Array.empty, Double.NaN))
    RootRescore((_, _) => Array.empty, 0.0)
    RootRescore((_, _) => Array.empty, 1.0) // no throw at either boundary that should succeed
    RootRescore((_, _) => Array.empty, 0.5)

  // White's king (e1) can step to d1, d2, e2, or f1 (f2 is blocked by White's own pawn, keeping the candidate set to
  // exactly these four; the only die White can use is the king-die, 6 — no knight/bishop on the board). Black's
  // bishop (a5) sits on the a5-e1 diagonal, so it directly threatens d2 (same diagonal, same square color) the
  // moment it rolls a bishop die — d2 is loss-tainted. A bishop is forever confined to one square color, so it can
  // NEVER reach d1, e2, or f1 (the opposite color) no matter how many micro-moves it chains: those three are
  // structurally, permanently safe, and tie exactly under material scoring (verified empirically: all three win
  // across different seeds with an identical score, d2 never wins).
  private val rootRescorePosition = parse("7k/8/8/b7/8/8/5P2/4K3 w - - 0 1").withDicePool(List(6, 2, 3))

  private def prefers(square: (Char, Int)): (Array[GameState], Color) => Array[Int] =
    (states, _) =>
      states.map(s =>
        if s.mailbox.get(Square(square._1, square._2)).exists(_.pieceType == PieceType.King) then 1 else 0
      )

  private def scoresKingSquares(
      d1: Int,
      d2: Int,
      e2: Int,
      f1: Int
  ): (Array[GameState], Color) => Array[Int] =
    (states, _) =>
      states.map: s =>
        if s.mailbox.get(Square('d', 1)).exists(_.pieceType == PieceType.King) then d1
        else if s.mailbox.get(Square('d', 2)).exists(_.pieceType == PieceType.King) then d2
        else if s.mailbox.get(Square('e', 2)).exists(_.pieceType == PieceType.King) then e2
        else if s.mailbox.get(Square('f', 1)).exists(_.pieceType == PieceType.King) then f1
        else 0

  test("rootRescore deterministically breaks a tie among otherwise-equal candidates"):
    val rescore = Some(RootRescore(prefers('d', 1), weight = 0.5))
    for seed <- 0 to 9 do
      val move = ExpectimaxSearch(materialBatch, rootRescore = rescore).findBestMove(rootRescorePosition, Random(seed))
      assertEquals(move.map(s => uci(s.moves)), Some("e1d1"), s"seed $seed")

  test("rootRescore never rescues a candidate that loses the king on some roll, even at weight 1.0"):
    // Rig the rescorer to WANT the loss-tainted d2 as strongly as possible; the search must still never choose it.
    val rescore = Some(RootRescore(prefers('d', 2), weight = 1.0))
    for seed <- 0 to 9 do
      val move = ExpectimaxSearch(materialBatch, rootRescore = rescore)
        .findBestMove(rootRescorePosition, Random(seed))
        .map(s => uci(s.moves))
      assert(move.exists(_ != "e1d2"), s"seed $seed: must never expose the king to the bishop, got $move")

  test("a transformed root alpha preserves exact blended ties for the random tie-break"):
    val equalBatch: (Array[GameState], Color) => Array[Int] = (states, _) => states.map(_ => 0)
    val tieRescore = RootRescore(scoresKingSquares(d1 = 10, d2 = 0, e2 = 10, f1 = 0), weight = 0.5)
    val bot        = ExpectimaxSearch(equalBatch, rootRescore = Some(tieRescore))
    val outcomes   =
      List(0, 4096).map(seed => bot.findBestMove(rootRescorePosition, Random(seed)).map(s => uci(s.moves))).toSet
    assertEquals(outcomes, Set(Option("e1d1"), Option("e1e2")))

  test("rescore-aware Star pruning matches exhaustive per-candidate search across edge-adjacent weights"):
    val targets     = List(('d', 1), ('d', 2), ('e', 2), ('f', 1))
    val rescoreEval = scoresKingSquares(d1 = 300, d2 = 10000, e2 = 100, f1 = 0)
    val weights     = List(0.25, 0.5, java.lang.Math.nextDown(1.0))

    weights.foreach: weight =>
      val rescore    = Some(RootRescore(rescoreEval, weight))
      val exhaustive = targets.map: target =>
        ExpectimaxSearch(
          materialBatch,
          ExpectimaxConfig(candidateLimit = 1),
          rootRescore = rescore,
          preRank = prefers(target)
        ).findBestMove(rootRescorePosition, Random(0)).get
      val expected = exhaustive.maxBy(_.score)

      var stats  = Option.empty[RootSearchStats]
      val pruned = ExpectimaxSearch(materialBatch, rootRescore = rescore, statsSink = s => stats = Some(s))
        .findBestMove(rootRescorePosition, Random(0))
        .get

      assertEquals(uci(pruned.moves), uci(expected.moves), s"weight=$weight")
      assertEquals(pruned.score, expected.score, s"weight=$weight")
      assert(stats.exists(_.cutoffs > 0), s"weight=$weight should keep Star pruning operational, got $stats")

  test("weight zero is exactly unrescored search and never invokes the configured evaluator"):
    var calls                                                 = 0
    val disabledEval: (Array[GameState], Color) => Array[Int] = (states, _) =>
      calls += 1
      states.map(_ => Int.MaxValue)
    val disabledRescore = RootRescore(disabledEval, weight = 0.0)
    for seed <- 0 to 5 do
      val actual = ExpectimaxSearch(materialBatch, rootRescore = Some(disabledRescore))
        .findBestMove(rootRescorePosition, Random(seed))
      val expected = ExpectimaxSearch(materialBatch).findBestMove(rootRescorePosition, Random(seed))
      assertEquals(actual, expected, s"seed=$seed")
    assertEquals(calls, 0)

  test("weight one explicitly disables transformed pruning while preserving root rescoring"):
    val rescore = Some(RootRescore(prefers('d', 1), weight = 1.0))
    var stats   = Option.empty[RootSearchStats]
    val result  = ExpectimaxSearch(materialBatch, rootRescore = rescore, statsSink = s => stats = Some(s))
      .findBestMove(rootRescorePosition, Random(0))
    assertEquals(result.map(s => uci(s.moves)), Some("e1d1"))
    val s = stats.getOrElse(fail("expected stats"))
    assertEquals(s.cutoffs, 0)
    assertEquals(s.candidatesCompleted, 4)

  test("transformed root bounds fail open without NaN leakage at numeric extremes"):
    val edgeCases = List(
      (Double.NegativeInfinity, 0.0, 0.5),
      (Double.PositiveInfinity, Int.MaxValue.toDouble, 0.5),
      (0.0, Int.MaxValue.toDouble, java.lang.Math.nextDown(1.0)),
      (0.0, Int.MinValue.toDouble, Double.MinPositiveValue),
      (Double.MaxValue, Double.PositiveInfinity, 0.5),
      (Double.NaN, 0.0, 0.5)
    )
    edgeCases.foreach: (best, rescore, weight) =>
      val alpha = ExpectimaxSearch.transformedRootAlpha(best, rescore, weight)
      assert(!alpha.isNaN, s"best=$best rescore=$rescore weight=$weight produced NaN")

    assertEquals(ExpectimaxSearch.transformedRootAlpha(42.0, 7.0, 0.0), 42.0)
    assertEquals(
      ExpectimaxSearch.transformedRootAlpha(42.0, 7.0, 1.0),
      Double.NegativeInfinity
    )

    val tiedSearch = 1234.5
    val rescore    = -987.0
    val weight     = java.lang.Math.nextDown(1.0)
    val tiedFinal  = ExpectimaxSearch.blendRootScore(tiedSearch, rescore, weight)
    val alpha      = ExpectimaxSearch.transformedRootAlpha(tiedFinal, rescore, weight)
    assert(alpha <= tiedSearch, s"a mathematically tied candidate must survive strict pruning: alpha=$alpha")

  test("without rootRescore inferior candidates are pruned by Star pruning"):
    val outcomes: Set[Option[String]] =
      (0 to 30).map(seed => search().findBestMove(rootRescorePosition, Random(seed)).map(s => uci(s.moves))).toSet
    assertEquals(outcomes, Set(Option("e1d1")))

  test("without rootRescore tied candidates break ties randomly"):
    val equalBatch: (Array[GameState], Color) => Array[Int] = (states, _) => states.map(_ => 0)
    val bot                                                 = ExpectimaxSearch(equalBatch)
    val outcomes = (0 to 30).map(seed => bot.findBestMove(rootRescorePosition, Random(seed)).map(s => uci(s.moves)))
    assert(outcomes.toSet.size > 1, s"expected random tie-breaking among equal candidates, got $outcomes")

  test("an injected preRank fully determines which single candidate reaches the chance node"):
    // candidateLimit=1 means exactly one path is expanded — whichever the pre-ranker scores highest — independent of
    // its chance-node value. Two different targets prove the injection is generic, not a coincidence of generation order.
    val toF1 = ExpectimaxSearch(materialBatch, ExpectimaxConfig(candidateLimit = 1), preRank = prefers('f', 1))
    val toE2 = ExpectimaxSearch(materialBatch, ExpectimaxConfig(candidateLimit = 1), preRank = prefers('e', 2))
    assertEquals(toF1.findBestMove(rootRescorePosition, Random(0)).map(s => uci(s.moves)), Some("e1f1"))
    assertEquals(toE2.findBestMove(rootRescorePosition, Random(0)).map(s => uci(s.moves)), Some("e1e2"))

  test("default preRank is exactly ExpectimaxSearch.materialBatch — no behaviour change when omitted"):
    val implicitDefault = search().findBestMove(rootRescorePosition, Random(3))
    val explicitDefault =
      ExpectimaxSearch(materialBatch, preRank = ExpectimaxSearch.materialBatch)
        .findBestMove(rootRescorePosition, Random(3))
    assertEquals(implicitDefault.map(_.moves), explicitDefault.map(_.moves))

  // ---- Root telemetry (statsSink, #494) ----

  test("statsSink reports full width when no deadline applies"):
    // rootRescorePosition offers four king steps: e1d1 completes (1), e1d2 is probe-pruned by Star2, e1e2 and e1f1 are pruned by Star1.
    var stats = Option.empty[RootSearchStats]
    val bot   = ExpectimaxSearch(materialBatch, statsSink = s => stats = Some(s))
    assert(bot.findBestMove(rootRescorePosition, Random(0)).isDefined)
    assertEquals(
      stats,
      Some(
        RootSearchStats(
          legalTurns = 4,
          candidatesSelected = 4,
          candidatesCompleted = 1,
          candidatesAbandoned = 0,
          cutoffs = 3,
          rollsSaved = 156,
          probeCutoffs = 1
        )
      )
    )
    assert(!stats.get.deadlineTruncated)

  test("an already-elapsed deadline completes nothing and falls back to the pre-ranker (#496)"):
    // Before #496 the top candidate was expanded whatever the clock said, so this reported 1 completed — and a
    // candidate can cost multiples of the whole budget. Now the chance node yields between dice rolls, so the very
    // first candidate is abandoned instead, and the turn comes from the pre-ranker alone.
    var stats = Option.empty[RootSearchStats]
    val bot   = ExpectimaxSearch(materialBatch, statsSink = s => stats = Some(s))
    assert(bot.findBestMove(rootRescorePosition, System.nanoTime(), Random(0)).isDefined)
    assertEquals(
      stats,
      Some(RootSearchStats(legalTurns = 4, candidatesSelected = 4, candidatesCompleted = 0, candidatesAbandoned = 1))
    )
    assert(stats.get.deadlineTruncated)
    assert(stats.get.fellBackToPreRank)

  test("an already-elapsed deadline skips root rescoring and preserves the pre-rank fallback"):
    var rescoreCalls                                             = 0
    val deadlineRescore: (Array[GameState], Color) => Array[Int] = (states, color) =>
      rescoreCalls += 1
      materialBatch(states, color)
    val rescore  = RootRescore(deadlineRescore, weight = 0.5)
    var stats    = Option.empty[RootSearchStats]
    val deadline = System.nanoTime()
    val actual   = ExpectimaxSearch(materialBatch, rootRescore = Some(rescore), statsSink = s => stats = Some(s))
      .findBestMove(rootRescorePosition, deadline, Random(0))
    val expected = ExpectimaxSearch(materialBatch).findBestMove(rootRescorePosition, deadline, Random(0))

    assertEquals(actual, expected)
    assertEquals(rescoreCalls, 0)
    assert(stats.exists(_.fellBackToPreRank), s"expected the existing anytime fallback, got $stats")

  test("a root-rescore batch that exhausts the deadline prevents subsequent chance-node work"):
    val budgetMs                                                = 1000L
    val deadline                                                = System.nanoTime() + budgetMs * 1_000_000L
    var leafCalls                                               = 0
    val recordingBatch: (Array[GameState], Color) => Array[Int] = (states, _) =>
      leafCalls += 1
      states.map(_ => 0)
    var rescoreCalls                                                       = 0
    val deadlineExhaustingRescore: (Array[GameState], Color) => Array[Int] = (states, _) =>
      rescoreCalls += 1
      while System.nanoTime() < deadline do ()
      states.map(_ => 0)

    var stats  = Option.empty[RootSearchStats]
    val result = ExpectimaxSearch(
      recordingBatch,
      rootRescore = Some(RootRescore(deadlineExhaustingRescore, weight = 0.5)),
      statsSink = s => stats = Some(s)
    ).findBestMove(rootRescorePosition, deadline, Random(0))

    assert(result.isDefined, "the anytime contract still owes the pre-ranker's legal turn")
    assertEquals(rescoreCalls, 1)
    assertEquals(leafCalls, 0, "the indivisible root batch consumed the budget, so chance search must not start")
    assert(stats.exists(_.fellBackToPreRank), s"expected pre-rank fallback after the root batch, got $stats")

  test("the pre-rank fallback returns the pre-ranker's own top pick, not an arbitrary turn (#496)"):
    // With candidateLimit = 1 the pre-ranker's choice is unambiguous, so the fallback is verifiable: whatever
    // `prefers` ranks first must be the turn played once the deadline leaves no room to search.
    for target <- List(('f', 1), ('e', 2), ('d', 1)) do
      val bot = ExpectimaxSearch(
        materialBatch,
        ExpectimaxConfig(candidateLimit = 1),
        preRank = prefers(target)
      )
      val move = bot.findBestMove(rootRescorePosition, System.nanoTime(), Random(0)).map(s => uci(s.moves))
      assertEquals(move, Some(s"e1${target._1}${target._2}"))

  test("a generous deadline still completes every candidate — the yield point costs nothing when there is time"):
    var stats = Option.empty[RootSearchStats]
    val bot   = ExpectimaxSearch(materialBatch, statsSink = s => stats = Some(s))
    val far   = System.nanoTime() + 60_000L * 1_000_000L
    assert(bot.findBestMove(rootRescorePosition, far, Random(0)).isDefined)
    assertEquals(
      stats,
      Some(
        RootSearchStats(
          legalTurns = 4,
          candidatesSelected = 4,
          candidatesCompleted = 1,
          candidatesAbandoned = 0,
          cutoffs = 3,
          rollsSaved = 156,
          probeCutoffs = 1
        )
      )
    )
    assert(!stats.get.deadlineTruncated)
    assert(!stats.get.fellBackToPreRank)

  test("a candidate abandoned mid-list still ranks the ones that finished (#496)"):
    // The production-typical case, and the one the two tests above miss between them: some candidates complete,
    // then the clock stops the next — so `results` is non-empty and the normal ranking path runs, unlike the
    // pre-rank fallback.
    //
    // Made deterministic by an evaluator that is FREE for the first candidate's 56 rolls and expensive after: the
    // first candidate therefore completes no matter how slow the machine is, and the second cannot survive three
    // rolls. Time is burned by spinning rather than sleeping because this suite also runs on the Scala.js and Wasm
    // runners, where `Thread.sleep` does not exist.
    val budgetMs      = 500L
    val burnPerCallMs = 200L
    val freeCalls     = DiceRolls.weighted.length // exactly one candidate's worth of leaf evaluations
    var calls         = 0
    val slowAfterFirst: (Array[GameState], Color) => Array[Int] = (states, color) =>
      calls += 1
      if calls > freeCalls then
        val until = System.nanoTime() + burnPerCallMs * 1_000_000L
        while System.nanoTime() < until do ()
      materialBatch(states, color)

    var stats = Option.empty[RootSearchStats]
    val bot   = ExpectimaxSearch(slowAfterFirst, statsSink = s => stats = Some(s))
    val move  = bot.findBestMove(rootRescorePosition, System.nanoTime() + budgetMs * 1_000_000L, Random(0))

    assert(move.isDefined, "the anytime contract still owes a legal turn")
    val s = stats.getOrElse(fail("expected a stats record"))
    assertEquals(s.candidatesCompleted, 1, s"expected exactly the free candidate to finish, got $s")
    assertEquals(s.candidatesAbandoned, 1, s"expected the next candidate to be cut mid-chance-node, got $s")
    assert(s.deadlineTruncated, "the selected set was not exhausted")
    assert(!s.fellBackToPreRank, "a completed candidate exists, so the pre-rank fallback must NOT be used")

  test("the untimed path is unaffected by the deadline plumbing — same turn as before, every candidate scored"):
    // The seeded arena's reproducibility rests on this: NoDeadline must never consult the clock or drop a candidate.
    for seed <- 0 to 5 do
      var stats     = Option.empty[RootSearchStats]
      val timedBot  = ExpectimaxSearch(materialBatch, statsSink = s => stats = Some(s))
      val untimed   = timedBot.findBestMove(rootRescorePosition, Random(seed)).map(s => uci(s.moves))
      val reference = search().findBestMove(rootRescorePosition, Random(seed)).map(s => uci(s.moves))
      assertEquals(untimed, reference, s"seed $seed")
      assertEquals(stats.map(_.candidatesCompleted), Some(1), s"seed $seed")
      assertEquals(stats.map(_.cutoffs), Some(3), s"seed $seed")
      assertEquals(stats.map(_.probeCutoffs), Some(1), s"seed $seed")
      assertEquals(stats.map(_.candidatesAbandoned), Some(0), s"seed $seed")

  test("Star1 pruning reduces processed rolls and records cutoffs in RootSearchStats"):
    // Position where candidate 0 achieves a strong score and subsequent candidates are provably worse.
    val state = parse("1r4k1/p4ppp/8/8/8/8/5PPP/R5K1 w - - 0 1").withDicePool(List(2, 2, 4))
    var stats = Option.empty[RootSearchStats]
    val bot   = ExpectimaxSearch(materialBatch, statsSink = s => stats = Some(s))
    assert(bot.findBestMove(state, Random(0)).isDefined)
    val s = stats.getOrElse(fail("expected stats"))
    assert(s.cutoffs > 0, s"expected cutoffs > 0, got $s")
    assert(s.rollsSaved > 0, s"expected rollsSaved > 0, got $s")

  test("Star1 and Star2 remain operational when rootRescore is configured"):
    val rescore = Some(RootRescore(prefers('d', 1), weight = 0.5))
    var stats   = Option.empty[RootSearchStats]
    val bot     = ExpectimaxSearch(materialBatch, rootRescore = rescore, statsSink = s => stats = Some(s))
    assert(bot.findBestMove(rootRescorePosition, Random(0)).isDefined)
    val s = stats.getOrElse(fail("expected stats"))
    assert(s.cutoffs > 0, s"rootRescore must propagate a search-space alpha, got $s")
    assert(s.probeCutoffs > 0, s"rootRescore must keep Star2 probing effective, got $s")
    assert(s.rollsSaved > 0, s"rootRescore must save roll expansions, got $s")

  test("TT upper bounds use the same transformed root alpha as Star pruning"):
    val table   = new TranspositionTable(256)
    val rescore = Some(RootRescore(prefers('d', 1), weight = 0.5))
    var stats   = Option.empty[RootSearchStats]
    val bot     = ExpectimaxSearch(
      materialBatch,
      rootRescore = rescore,
      statsSink = s => stats = Some(s),
      tt = Some(table)
    )

    val first  = bot.findBestMove(rootRescorePosition, Random(0))
    val second = bot.findBestMove(rootRescorePosition, Random(0))
    assertEquals(second, first)
    val s = stats.getOrElse(fail("expected second-search stats"))
    assert(s.ttHits > 0, s"expected the completed candidate to reuse an exact TT value, got $s")
    assert(s.ttCutoffs > 0, s"expected root-rescored candidates to reuse TT upper bounds, got $s")

  test("ttProbes counts every candidate the root loop reached, and stays zero without a table"):
    // The probe counter is incremented before the chance node runs, so it measures how many candidates consulted the
    // table — not how many it answered. Nothing else in this suite pins that distinction, and it is the one root
    // counter whose value is independent of what the search then decided.
    var probing = Option.empty[RootSearchStats]
    val withTt  = ExpectimaxSearch(
      materialBatch,
      statsSink = s => probing = Some(s),
      tt = Some(new TranspositionTable(256))
    )
    assert(withTt.findBestMove(rootRescorePosition, Random(0)).isDefined)
    assertEquals(probing.map(_.candidatesSelected), Some(4))
    assertEquals(probing.map(_.ttProbes), Some(4))

    var unprobed  = Option.empty[RootSearchStats]
    val withoutTt = ExpectimaxSearch(materialBatch, statsSink = s => unprobed = Some(s))
    assert(withoutTt.findBestMove(rootRescorePosition, Random(0)).isDefined)
    assertEquals(unprobed.map(_.candidatesSelected), Some(4))
    assertEquals(unprobed.map(_.ttProbes), Some(0))

  test("statsSink reports 0/0 on an immediate king capture — no candidate was ever expanded"):
    val state = parse("k7/8/8/8/8/8/8/R3K3 w - - 0 1").withDicePool(List(1, 1, 4))
    var stats = Option.empty[RootSearchStats]
    val bot   = ExpectimaxSearch(materialBatch, statsSink = s => stats = Some(s))
    assert(bot.findBestMove(state, Random(0)).isDefined)
    assert(stats.exists(s => s.legalTurns > 0 && s.candidatesSelected == 0 && s.candidatesCompleted == 0))

  test("statsSink fires exactly once, all-zero, on a forced pass — one record per findBestMove call"):
    // Bare kings and three pawn dice: no legal micro-move exists, the bot must pass (findBestMove == None).
    val state = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").withDicePool(List(1, 1, 1))
    var seen  = List.empty[RootSearchStats]
    val bot   = ExpectimaxSearch(materialBatch, statsSink = s => seen = s :: seen)
    assertEquals(bot.findBestMove(state, Random(0)), None)
    assertEquals(seen, List(RootSearchStats(0, 0, 0)))

  test("chance-node leaves reach the evaluator deduplicated, and the value is unchanged (#505)"):
    // Dice Chess turns are 1-3 micro-moves, so independent micro-moves played in either order reach the same board.
    // Those duplicates used to be scored once per path; now the evaluator sees each distinct position once.
    val state = parse("1r4k1/p4ppp/8/8/8/8/5PPP/R5K1 w - - 0 1").withDicePool(List(2, 2, 4))

    var batches                                                 = List.empty[Seq[String]]
    val recordingBatch: (Array[GameState], Color) => Array[Int] = (states, color) =>
      batches = states.toSeq.map(FenParser.serialize) :: batches
      materialBatch(states, color)

    val deduped  = ExpectimaxSearch(recordingBatch).findBestMove(state, Random(11)).map(s => uci(s.moves))
    val expected = search().findBestMove(state, Random(11)).map(s => uci(s.moves))

    // The minimum over a multiset equals the minimum over its distinct elements, so dedup cannot move the value.
    assertEquals(deduped, expected, "deduplication must not change the chosen turn")

    assert(batches.nonEmpty, "the evaluator was never called — the fixture does not exercise a chance node")
    batches.foreach: leaves =>
      assertEquals(
        leaves.distinct.size,
        leaves.size,
        s"a batch reached the evaluator with duplicate positions: $leaves"
      )
    // Guards against a vacuous pass: this fixture must actually contain reorderings, or the assertion above proves
    // nothing about deduplication.
    assert(
      batches.exists(_.size > 1),
      "no multi-leaf batch was produced, so distinctness within a batch is trivially true"
    )

  test("Star2 probing records probeCutoffs and prunes chance nodes before full expansion"):
    val state = parse("1r4k1/p4ppp/8/8/8/8/5PPP/R5K1 w - - 0 1").withDicePool(List(2, 2, 4))
    var stats = Option.empty[RootSearchStats]
    val bot   = ExpectimaxSearch(materialBatch, statsSink = s => stats = Some(s))
    assert(bot.findBestMove(state, Random(0)).isDefined)
    val s = stats.getOrElse(fail("expected stats"))
    assert(s.probeCutoffs > 0, s"expected probeCutoffs > 0, got $s")
    assert(s.cutoffs >= s.probeCutoffs, s"expected cutoffs >= probeCutoffs, got $s")

  test("Star2 probing preserves move decisions across scenarios"):
    val state      = parse("1r4k1/p4ppp/8/8/8/8/5PPP/R5K1 w - - 0 1").withDicePool(List(2, 2, 4))
    val move       = search().findBestMove(state, Random(42)).map(s => uci(s.moves))
    val greedyMove = GreedySearch.findBestMove(state, Random(42)).map(s => uci(s.moves))
    assert(move.isDefined && move != greedyMove, s"expected expectimax decision to decline hanging grab, got $move")
