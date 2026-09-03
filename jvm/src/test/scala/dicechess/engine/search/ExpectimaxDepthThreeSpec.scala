package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

import scala.concurrent.duration.*
import scala.util.Random

/** JVM-only structural regression tests for depth-3 expectimax (#60).
  *
  * The fixtures deliberately expand exact inner trees. Keeping them JVM-only preserves coverage without paying the same
  * multi-second exhaustive search again on the slower Scala.js and Wasm test runners.
  */
class ExpectimaxDepthThreeSpec extends FunSuite:

  // These fixtures intentionally expand exact depth-3 trees. They finish in ~11s together on an uninstrumented JVM,
  // but scoverage instruments the move-generation hot path; keep a bounded ceiling above munit's unit-sized default.
  override def munitTimeout: Duration = 3.minutes

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  private val materialBatch: (Array[GameState], Color) => Array[Int] =
    (states, color) => states.map(state => Evaluator.evaluateMaterial(state, color))

  private def uci(moves: List[Move]): String =
    moves.map(m => m.fromSquare.toNotation + m.toSquare.toNotation).mkString(" ")

  private def whiteKingOn(square: (Char, Int))(state: GameState): Boolean =
    state.mailbox
      .get(Square(square._1, square._2))
      .exists(piece => piece.color == Color.White && piece.pieceType == PieceType.King)

  /** A pre-ranker that forces exactly one root candidate: the turn that puts the white king on `square`. */
  private def prefers(square: (Char, Int)): (Array[GameState], Color) => Array[Int] =
    (states, _) => states.map(s => if whiteKingOn(square)(s) then 1 else 0)

  /** Distinct leaf values per white-king square, so the depth-3 tree has a strict ordering to prune against. */
  private def scoresKingSquares(d1: Int, d2: Int, e2: Int, f1: Int): (Array[GameState], Color) => Array[Int] =
    (states, _) =>
      states.map: s =>
        if whiteKingOn('d' -> 1)(s) then d1
        else if whiteKingOn('d' -> 2)(s) then d2
        else if whiteKingOn('e' -> 2)(s) then e2
        else if whiteKingOn('f' -> 1)(s) then f1
        else 0

  test("depth 3 window pruning matches an exhaustive per-candidate depth-3 search"):
    // The depth-2 equivalent of this test lives in ExpectimaxSearchSpec; depth 3 adds a whole second layer of window
    // arithmetic — per-roll alpha/beta transforms, the fail-soft re-search, and the leaf-ply nodes that provably never
    // read their window — none of which may change what the wide search decides.
    //
    // Only the king die (6) is usable (no rook or pawn die, and White has no knight or bishop), so the root offers
    // exactly the four king steps d1, d2, e2 and f1. Searched one at a time the root window is open, so no bound of
    // any kind can reach them; searched together every candidate after the first prunes against a real alpha.
    //
    // Black is a lone king on purpose: eight exact depth-3 candidate trees are affordable only if the opponent passes
    // on most rolls. A richer Black turns the same assertion into two minutes under scoverage instrumentation.
    val state   = parse("7k/8/8/8/8/8/5P2/4K3 w - - 0 1").withDicePool(List(6, 2, 3))
    val leaf    = scoresKingSquares(d1 = 300, d2 = 9000, e2 = 100, f1 = 0)
    val targets = List(('d', 1), ('d', 2), ('e', 2), ('f', 1))

    val exhaustive = targets.map: target =>
      ExpectimaxSearch(
        leaf,
        ExpectimaxConfig(candidateLimit = 1, searchDepth = 3),
        preRank = prefers(target)
      ).findBestMove(state, Random(0)).getOrElse(fail(s"no turn for target $target"))
    val bestScore = exhaustive.map(_.score).max

    var stats  = Option.empty[RootSearchStats]
    val pruned = ExpectimaxSearch(
      leaf,
      ExpectimaxConfig(candidateLimit = 4, searchDepth = 3),
      statsSink = s => stats = Some(s),
      tt = Some(new TranspositionTable(4096))
    ).findBestMove(state, Random(0)).getOrElse(fail("no turn from the wide search"))

    assertEquals(pruned.score, bestScore)
    val bestTurns = exhaustive.filter(_.score == bestScore).map(candidate => uci(candidate.moves))
    assert(bestTurns.contains(uci(pruned.moves)), s"pruned turn ${uci(pruned.moves)} is not among $bestTurns")

    val s = stats.getOrElse(fail("expected root stats"))
    assertEquals(s.candidatesSelected, 4)
    assert(s.cutoffs + s.ttCutoffs > 0, s"the wide search must prune something, or this proves nothing: $s")

  test("depth 3 values a future king capture above every non-terminal leaf"):
    // The root roll contains no rook die, so White cannot take Ka8 immediately. After White's forced king move and
    // Black's reply/pass, an inner rook roll lets Ra1 capture the king. A zero evaluator makes that terminal preference
    // the only possible source of a positive score.
    val state = parse("k7/8/8/8/8/8/8/R3K3 w - - 0 1").withDicePool(List(2, 3, 6))
    val zeroBatch: (Array[GameState], Color) => Array[Int] = (states, _) => Array.fill(states.length)(0)
    val table                                              = new TranspositionTable(4096)
    val result                                             = ExpectimaxSearch(
      zeroBatch,
      ExpectimaxConfig(candidateLimit = 2, searchDepth = 3),
      tt = Some(table)
    ).findBestMove(state, Random(0))
    assert(result.exists(_.score > 0), s"future king captures must dominate zero-valued leaves, got $result")
    val shallower = ExpectimaxSearch(
      zeroBatch,
      ExpectimaxConfig(candidateLimit = 1, searchDepth = 2),
      tt = Some(table)
    ).findBestMove(state, Random(0))
    assertEquals(shallower.map(_.score), Some(0), "depth 2 must not reuse a depth-3 TT value")

  test("depth 3 inner chance nodes use exact TT entries without changing the result"):
    val state        = parse("k7/8/8/8/8/8/8/R3K3 w - - 0 1").withDicePool(List(2, 3, 6))
    val config       = ExpectimaxConfig(candidateLimit = 1, exactOnlyMode = true, searchDepth = 3)
    val withoutTable = ExpectimaxSearch(materialBatch, config).findBestMove(state, Random(11))
    val table        = new TranspositionTable(4096)
    val withTable    = ExpectimaxSearch(materialBatch, config, tt = Some(table)).findBestMove(state, Random(11))
    assertEquals(withTable, withoutTable)
    assert(table.stores > 1L, s"expected both outer and inner TT stores, got ${table.stores}")
    assert(table.hits > 0L, "transposed inner chance nodes should produce TT hits")

  test("depth 3 expands castling only when the inner roll supplies both castling dice"):
    // White's root pawn move preserves KQ rights. The leaf evaluator rewards only kingside castling; depth 2 cannot
    // see it, while depth 3 can select it on an inner roll containing both rook (4) and king (6) dice. TurnGenerator's
    // maximal-dice rule is exercised here too: castling consumes two dice in one move and must remain a legal full turn.
    val state = parse("4k3/8/8/8/8/8/P7/R3K2R w KQ - 0 1").withDicePool(List(1, 2, 3))
    val rewardsCastling: (Array[GameState], Color) => Array[Int] = (states, _) =>
      states.map(s =>
        if s.mailbox
            .get(Square('g', 1))
            .exists(piece => piece.color == Color.White && piece.pieceType == PieceType.King)
        then 9000
        else 0
      )
    val depth2 = ExpectimaxSearch(
      rewardsCastling,
      ExpectimaxConfig(candidateLimit = 1, searchDepth = 2)
    ).findBestMove(state, Random(0))
    val depth3 = ExpectimaxSearch(
      rewardsCastling,
      ExpectimaxConfig(candidateLimit = 1, searchDepth = 3),
      tt = Some(new TranspositionTable(4096))
    ).findBestMove(state, Random(0))
    assertEquals(depth2.map(_.score), Some(0))
    assert(depth3.exists(_.score > 0), s"depth 3 must reach castled leaves, got $depth3")
