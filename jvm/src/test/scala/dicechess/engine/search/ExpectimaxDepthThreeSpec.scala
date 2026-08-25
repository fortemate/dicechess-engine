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
  override def munitTimeout: Duration = 2.minutes

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  private val materialBatch: (Array[GameState], Color) => Array[Int] =
    (states, color) => states.map(state => Evaluator.evaluateMaterial(state, color))

  test("depth 3 values a future king capture above every non-terminal leaf"):
    // The root roll contains no rook die, so White cannot take Ka8 immediately. After White's forced king move and
    // Black's reply/pass, an inner rook roll lets Ra1 capture the king. A zero evaluator makes that terminal preference
    // the only possible source of a positive score.
    val state = parse("k7/8/8/8/8/8/8/R3K3 w - - 0 1").withDicePool(List(2, 3, 6))
    val zeroBatch: (Array[GameState], Color) => Array[Int] = (states, _) => Array.fill(states.length)(0)
    val result                                             = ExpectimaxSearch(
      zeroBatch,
      ExpectimaxConfig(candidateLimit = 1, searchDepth = 3)
    ).findBestMove(state, Random(0))
    assert(result.exists(_.score > 0), s"future king captures must dominate zero-valued leaves, got $result")

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
      ExpectimaxConfig(candidateLimit = 1, searchDepth = 3)
    ).findBestMove(state, Random(0))
    assertEquals(depth2.map(_.score), Some(0))
    assert(depth3.exists(_.score > 0), s"depth 3 must reach castled leaves, got $depth3")
