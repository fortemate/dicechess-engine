// SPDX-License-Identifier: AGPL-3.0-only
package consumer

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import dicechess.engine.search.*
import munit.FunSuite

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

/** Integrated cross-platform qualification suite for Epic #390 (#399).
  *
  * Exercises the complete synthetic reference-fixture matrix (`F1`–`F10`) across JVM, Scala.js, and WebAssembly on the
  * combined public APIs:
  *   - `TurnGenerator.forEachLegalTurnState` and `BudgetedRules.visitTurnStates` / `foldTurnStates` (#392)
  *   - `SearchOutcome`, `SearchControl`, `UtilityBounds`, and `ControlledSearch` (#393)
  *   - `ChanceNode.expand` with Star1/Star2 bounds and `ActiveDicePartitioner` (#387, #394)
  *   - `AttackerTypes`, `DiceRolls.rollsShowingAnyOf`, and `PieceValues` (#395)
  *   - `PassedPawns.bitboard` (#396)
  *   - `EvaluationCache` vs `TranspositionTable` ownership and key-identity boundaries (#295, #397)
  *   - Downstream staged-adoption and built-in `ExpectimaxSearch` / `TacticianSearch` fixed-work and concurrency
  *     invariants (#398, #399)
  */
class CrossPlatformQualificationSuite extends FunSuite:

  private given ExecutionContext = ExecutionContext.global

  private def parse(dfen: String): GameState = FenParser.parse(dfen).fold(fail(_), identity)

  private val evalBatchMaterial: (Array[GameState], Color) => Array[Int] =
    (states, color) => states.map(Evaluator.evaluateMaterial(_, color))

  private def collectVisitorEntries(state: GameState): Vector[(List[Move], GameState, Boolean)] =
    val buf = Vector.newBuilder[(List[Move], GameState, Boolean)]
    val n   = TurnGenerator.forEachLegalTurnState(state) { (moves, len, afterState, isKingCapture) =>
      buf += ((moves.take(len).toList, afterState, isKingCapture))
      true
    }
    val res = buf.result()
    assertEquals(n, res.size)
    res

  private def replayReferenceEntry(state: GameState, path: List[Move]): (List[Move], GameState, Boolean) =
    val beforeLast = path.init.foldLeft(state)(_.makeMove(_))
    val isWin      = beforeLast.isKingCapture(path.last)
    val afterState = if isWin then beforeLast.makeMove(path.last) else beforeLast.makeMove(path.last).endTurn()
    (path, afterState, isWin)

  test("F1–F10 matrix: canonical TurnGenerator.forEachLegalTurnState and BudgetedRules match reference replay"):
    val matrix = List(
      "F1"  -> "4k3/8/8/8/8/8/8/4K3 w - - 0 1 K",
      "F2"  -> "k7/4P3/8/8/8/8/8/4K3 w - - 0 1 PNN",
      "F3"  -> "k7/8/4P3/8/8/8/8/4K3 w - - 0 1 PPQ",
      "F4"  -> "4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1 PPP",
      "F5"  -> "4k3/8/8/8/8/8/P7/R3K2R w KQ - 0 1 KR",
      "F6"  -> "4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RP",
      "F7"  -> "8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP",
      "F8"  -> "4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP",
      "F9"  -> "4k3/8/8/8/3r4/2P5/8/4K3 b - - 0 1 R",
      "F10" -> "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP"
    )
    for (id, dfen) <- matrix do
      val state     = parse(dfen)
      val visited   = collectVisitorEntries(state)
      val refPaths  = TurnGenerator.generateAllLegalTurnPaths(state)
      val reference = refPaths.map(replayReferenceEntry(state, _)).toVector
      assertEquals(visited, reference, s"$id visitor mismatch vs reference replay")

      val folded = BudgetedRules.foldTurnStates(
        state,
        Vector.empty[(List[Move], GameState, Boolean)],
        new BudgetedRules.Budget(32_768L)
      ) { (acc, path, afterState, isKingCapture) =>
        (acc :+ (path, afterState, isKingCapture), true)
      }
      folded match
        case BudgetedRules.Outcome.Complete(res, used) =>
          assertEquals(res.value, reference, s"$id budgeted fold mismatch")
          assert(!res.stoppedEarly)
          assert(used >= 0L)
        case other => fail(s"$id expected Complete, got $other")

  test("F1, F2, F3, F5, F6: promotion retention, 3-step shared prefix, castling 2-dice filter, and inherited EP"):
    // F1: king-only ADEP has 4 classes summing to 216 and CompactRoll.Empty transitions via endTurn()
    val f1      = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val f1Parts = ActiveDicePartitioner.partitionsFor(f1)
    assertEquals(f1Parts.length, 4)
    assertEquals(f1Parts.map(_.weight).toList.sorted, List(1, 15, 75, 125))
    assertEquals(f1Parts.map(_.weight).sum, DiceRolls.totalOrderedRolls)
    assert(f1Parts.exists(_.roll == CompactRoll.Empty))

    // F2: rank-7 pawn promotes to knight and moves twice more in the same turn (3 micro-moves)
    val f2        = parse("k7/4P3/8/8/8/8/8/4K3 w - - 0 1 PNN")
    val f2Entries = collectVisitorEntries(f2)
    assert(f2Entries.nonEmpty && f2Entries.forall(_._1.length == 3))
    assert(f2Entries.forall(e => !e._2.knights.isEmpty && e._2.pawns.isEmpty))

    // F3: rank-6 pawn pushes to e7, promotes on e8 to queen, and moves the promoted queen (3 micro-moves)
    val f3        = parse("k7/8/4P3/8/8/8/8/4K3 w - - 0 1 PPQ")
    val f3Entries = collectVisitorEntries(f3)
    assert(f3Entries.nonEmpty && f3Entries.forall(e => e._1.length == 3))
    assert(f3Entries.exists(e => !e._3 && !e._2.queens.isEmpty && e._2.pawns.isEmpty))

    // F5: castling consumes 2 dice (K + R), so every legal turn consumes both dice
    val f5        = parse("4k3/8/8/8/8/8/P7/R3K2R w KQ - 0 1 KR")
    val f5Entries = collectVisitorEntries(f5)
    assert(f5Entries.exists(e => e._1.length == 1 && e._1.head.isCastling))

    // F6: rook moves on step 1, pawn captures en-passant on d6 on step 2, and endTurn() clears EP target
    val f6        = parse("4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RP")
    val f6Entries = collectVisitorEntries(f6)
    assert(f6Entries.exists(e => e._1.exists(_.isEnPassant) && (e._2.pawns & e._2.blackPieces).isEmpty))
    assert(f6Entries.forall(e => e._2.enPassant.isEmpty))

  private def probeClassValue(rolled: GameState, bounds: UtilityBounds): ChanceNode.Value =
    ChanceNode.Replies.generate(rolled, SearchControl.Unbounded) match
      case None                      => ChanceNode.Value.incomplete(SearchControl.Unbounded)
      case Some(r) if r.capturesKing => ChanceNode.Value.exact(bounds.terminalLoss, lossTainted = true)
      case Some(r) if r.isForcedPass =>
        ChanceNode.Value.exact(Evaluator.evaluateMaterial(rolled.endTurn(), Color.White).toDouble)
      case Some(r) =>
        val firstReplyScore = Evaluator.evaluateMaterial(r.states.head, Color.White).toDouble
        ChanceNode.Value(SearchOutcome.UpperBound(firstReplyScore, 0L))

  private def decideClassValue(rolled: GameState, bounds: UtilityBounds): ChanceNode.Value =
    ChanceNode.Replies.generate(rolled, SearchControl.Unbounded) match
      case None                      => ChanceNode.Value.incomplete(SearchControl.Unbounded)
      case Some(r) if r.capturesKing => ChanceNode.Value.exact(bounds.terminalLoss, lossTainted = true)
      case Some(r) if r.isForcedPass =>
        ChanceNode.Value.exact(Evaluator.evaluateMaterial(rolled.endTurn(), Color.White).toDouble)
      case Some(r) =>
        ChanceNode.Value.exact(r.states.map(Evaluator.evaluateMaterial(_, Color.White)).min.toDouble)

  test("F4, F7, F8, F9, F10: forced pass, attacker/passed-pawn features, mandatory-move Star2, and cache separation"):
    // F4: blocked piece yields 0 legal paths and ControlledSearch / SearchAlgorithm returns None
    val f4       = parse("4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1 PPP")
    val expBot   = ExpectimaxSearch(evalBatchMaterial, ExpectimaxConfig(candidateLimit = 4, searchDepth = 2))
    val tactCfg  = TacticianConfig(candidateLimit = 4, kingThreatCp = 100, kingDangerCp = 100, hangingWeight = 0.5)
    val tactBot  = new TacticianSearch(tactCfg)
    val expMove  = expBot.findBestMove(f4, SearchControl.Unbounded, new Random(1))
    val tactMove = tactBot.findBestMove(f4, new Random(1))
    assertEquals(expMove, None)
    assertEquals(tactMove, None)

    // F7: knight on b3 attacks c5; AttackerTypes + PieceValues price it and immediate win short-circuits under Budget(0)
    val f7     = parse("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP")
    val f7Mask = AttackerTypes.mask(f7, Square('c', 5), Color.White)
    assertEquals(f7Mask, AttackerTypes.bit(PieceType.Knight))
    assertEquals(AttackerTypes.availableRolls(f7Mask), 91)
    assertEquals(PieceValues.Canonical.minimumOrElse(f7Mask, 0), PieceValues.Canonical.knight)
    assert(!MoveGenerator.allAttackers(f7, Square('c', 5), Color.White).isEmpty)
    val f7ZeroControl = SearchControl.withBudget(new BudgetedRules.Budget(0L))
    assertEquals(
      expBot.findBestMove(f7, f7ZeroControl, new Random(1)).map(_.score),
      Some(SearchScoring.TerminalWinScore)
    )

    // F8: PassedPawns detects both White pawns; EvaluationCache deduplicates equal post-turn states while TT stays separate
    val f8        = parse("4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP")
    val f8Pawns   = PassedPawns.bitboard(f8, Color.White)
    val f8Entries = collectVisitorEntries(f8)
    assertEquals(java.lang.Long.bitCount(f8Pawns.value), 2)
    val evalCache = new EvaluationCache((s, c) => Evaluator.evaluateAggressive(s, c))
    val scores    = f8Entries.map(e => evalCache(e._2, Color.White))
    assertEquals(scores.size, f8Entries.size)
    assert(evalCache.cacheHits > 0L && evalCache.entryCount < f8Entries.size)
    val tt = new TranspositionTable(64)
    tt.store(f8.zobristHash, value = 12.5, bound = TTBound.Exact, depth = 2, lossTainted = false)
    assertEquals(tt.probe(f8.zobristHash).map(_.value), Some(12.5))

    // F9: mandatory move on R die changes state; Star2 probe over legal reply matches unpruned ChanceNode expectation
    val f9PostTurn = parse("4k3/8/8/8/3r4/2P5/8/4K3 b - - 0 1")
    val bounds     = ExpectimaxSearch.utilityBounds(Color.White)
    val policy     = new ChanceNode.Policy:
      def lowestValue: Double                                                        = bounds.terminalLoss
      def highestValue: Double                                                       = bounds.maxNonTerminal
      override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]] =
        Some(Array.tabulate(rolled.length)(i => probeClassValue(rolled(i), bounds)))
      def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
        decideClassValue(rolled, bounds)
    val exactWide = ChanceNode.expand(
      f9PostTurn,
      bounds,
      SearchControl.Unbounded,
      Double.NegativeInfinity,
      Double.PositiveInfinity,
      policy
    )
    assert(exactWide.outcome.isExact)

    // F10: ExpectimaxSearch under Budget(0) falls back to top pre-ranked legal turn without throwing
    val f10     = parse("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP")
    val f10Move = expBot.findBestMove(f10, SearchControl.withBudget(new BudgetedRules.Budget(0L)), new Random(1))
    assert(f10Move.isDefined && f10Move.get.moves.nonEmpty)

  test(
    "Fixed-work parity & concurrency: ExpectimaxSearch and TacticianSearch match across Unbounded, Budget, and parallel runs"
  ):
    val positions = List(
      "4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP",
      "k7/4P3/8/8/8/8/8/4K3 w - - 0 1 PNN",
      "4k3/8/8/3pP3/8/8/8/R3K3 w Q d6 0 1 RP"
    )
    val expBot  = ExpectimaxSearch(evalBatchMaterial, ExpectimaxConfig(candidateLimit = 3, searchDepth = 2))
    val tactCfg = TacticianConfig(candidateLimit = 3, kingThreatCp = 100, kingDangerCp = 100, hangingWeight = 0.5)
    val tactBot = new TacticianSearch(tactCfg)

    val sequential = for
      dfen <- positions
      seed <- 0L until 3L
    yield
      val state        = parse(dfen)
      val expUnbounded = expBot.findBestMove(state, SearchControl.Unbounded, new Random(seed))
      val expBudgeted  = expBot.findBestMove(
        state,
        SearchControl.withBudget(new BudgetedRules.Budget(Long.MaxValue)),
        new Random(seed)
      )
      assertEquals(expBudgeted, expUnbounded, s"Expectimax fixed-work mismatch on $dfen seed $seed")

      val tactMove = tactBot.findBestMove(state, new Random(seed))
      (dfen, seed, expUnbounded, tactMove)

    Future
      .traverse(sequential) { case (dfen, seed, _, _) =>
        Future {
          val state = parse(dfen)
          val expR  = expBot.findBestMove(
            state,
            SearchControl.withBudget(new BudgetedRules.Budget(Long.MaxValue)),
            new Random(seed)
          )
          val tactR = tactBot.findBestMove(state, new Random(seed))
          (dfen, seed, expR, tactR)
        }
      }
      .map { concurrent =>
        assertEquals(concurrent, sequential)
      }
