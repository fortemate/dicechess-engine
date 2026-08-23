package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.util.Random

/** Gates for the Zobrist key (#59): fixed-seed reproducibility, pool canonicalization (including hole-y slot packings),
  * and incremental-equals-recomputed equivalence — proven on deterministic fixtures covering castling, promotion,
  * en-passant capture and multi-EP turns, plus a seeded random walk for breadth. The crafted fixtures exist because
  * random play cannot be trusted to reach the rare mutations; the walk exists because fixtures cannot be trusted to
  * reach the weird ones.
  */
class ZobristSpec extends FunSuite:

  private def parse(dfen: String): GameState = FenParser.parse(dfen).fold(fail(_), identity)

  private def assertIncrementalMatches(state: GameState, context: String): Unit =
    assertEquals(state.zobristHash, Zobrist.computeKey(state), s"incremental key diverged from recomputation: $context")

  /** Applies `path` micro-move by micro-move, asserting incremental == recomputed after every step and after endTurn.
    * Returns the post-turn state.
    */
  private def applyTurnChecked(rolled: GameState, path: List[Move], context: String): GameState =
    var cur = rolled
    for (move, idx) <- path.zipWithIndex do
      cur = cur.makeMove(move)
      assertIncrementalMatches(cur, s"$context, after micro-move ${idx + 1}/${path.size}")
    val ended = cur.endTurn()
    assertIncrementalMatches(ended, s"$context, after endTurn")
    ended

  // --- Gate: fixed-seed reproducibility -------------------------------------------------------------------------

  test("fixed-seed reproducibility: the initial position key is a stable constant"):
    val initial = parse(FenParser.InitialPosition)
    // Hard-coded from Zobrist.computeKey on the reference build. If this assertion ever fails, the table seed, the
    // SplitMix64 stream, or the key composition changed — all of which silently invalidate every stored table.
    assertEquals(Zobrist.computeKey(initial), ZobristSpec.InitialPositionKey)

  // --- Gate: pool canonicalization -------------------------------------------------------------------------------

  test("pool canonicalization: slot order does not change the key"):
    val base = parse(FenParser.InitialPosition)
    val a    = base.withDicePool(List(1, 3))
    val b    = base.withDicePool(List(3, 1))
    assertEquals(Zobrist.computeKey(a), Zobrist.computeKey(b))
    assertEquals(a.zobristHash, b.zobristHash)

  test("pool canonicalization: a hole-y slot packing (removeDie) hashes like the compacted pool"):
    val base = parse(FenParser.InitialPosition)
    val full = base.withDicePool(List(1, 3, 5))
    // GameFlags.removeDie clears the slot in place, leaving a hole: slots become (1, 0, 5).
    val holey   = full.withDiceSlotsOf(full.flags.removeDie(3))
    val compact = base.withDicePool(List(1, 5)) // slots (1, 5, 0)
    assertEquals(Zobrist.computeKey(holey), Zobrist.computeKey(compact))
    assertEquals(holey.zobristHash, compact.zobristHash, "incremental update must canonicalize hole-y packings too")

  // --- Gate: incremental == recomputed on the rare mutations (deterministic fixtures) ----------------------------

  test("castling keeps the incremental key in sync (king and rook both move)"):
    val state  = parse("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").withDicePool(List(6, 4))
    val paths  = TurnGenerator.generateAllLegalTurnPaths(state)
    val castle = paths.filter(_.exists(m => m.flags == Move.KingCastle || m.flags == Move.QueenCastle))
    assert(castle.nonEmpty, "fixture must offer castling")
    for (path, i) <- castle.zipWithIndex do applyTurnChecked(state, path, s"castle path $i")

  test("promotion keeps the incremental key in sync (pawn leaves, promoted piece arrives)"):
    val state = parse("8/P7/8/8/8/8/8/k6K w - - 0 1").withDicePool(List(1))
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    val promo = paths.filter(_.exists(_.isPromotion))
    assert(promo.nonEmpty, "fixture must offer promotion")
    for (path, i) <- promo.zipWithIndex do applyTurnChecked(state, path, s"promotion path $i")

  test("en-passant capture keeps the incremental key in sync across both turns"):
    val start  = parse("k7/8/8/8/3p4/8/4P3/K7 w - - 0 1").withDicePool(List(1))
    val double = TurnGenerator
      .generateAllLegalTurnPaths(start)
      .find(_.exists(_.flags == Move.DoublePawnPush))
      .getOrElse(fail("fixture must offer a double pawn push"))
    val afterWhite = applyTurnChecked(start, double, "double push turn")
    assert(afterWhite.enPassant.value != 0L, "double push must create an EP target")

    val rolledBlack = afterWhite.withDicePool(List(1))
    val epCapture   = TurnGenerator
      .generateAllLegalTurnPaths(rolledBlack)
      .find(_.exists(_.flags == Move.EnPassantCapture))
      .getOrElse(fail("black must be able to capture en passant"))
    applyTurnChecked(rolledBlack, epCapture, "en-passant capture turn")

  test("multiple simultaneous EP targets keep the incremental key in sync"):
    val state      = parse("k7/8/8/8/8/8/P1P5/K7 w - - 0 1").withDicePool(List(1, 1))
    val paths      = TurnGenerator.generateAllLegalTurnPaths(state)
    val twoDoubles = paths
      .find(p => p.count(_.flags == Move.DoublePawnPush) == 2)
      .getOrElse(fail("fixture must offer two double pushes in one turn"))
    val ended = applyTurnChecked(state, twoDoubles, "double double-push turn")
    assertEquals(java.lang.Long.bitCount(ended.enPassant.value), 2, "both EP targets must be present")

  // --- Gate: seeded random walk for breadth ----------------------------------------------------------------------

  test("incremental key matches recomputation at every step of seeded random games"):
    val games       = 25
    val maxTurns    = 80
    val rng         = new Random(42)
    var turnsPlayed = 0

    for game <- 0 until games do
      var state = parse(FenParser.InitialPosition)
      var turn  = 0
      var over  = false
      while turn < maxTurns && !over do
        val roll   = List.fill(3)(rng.nextInt(6) + 1)
        val rolled = state.withDicePool(roll)
        assertIncrementalMatches(rolled, s"game $game turn $turn, after withDicePool($roll)")
        val paths = TurnGenerator.generateAllLegalTurnPaths(rolled)
        state =
          if paths.isEmpty then
            val passed = rolled.endTurn()
            assertIncrementalMatches(passed, s"game $game turn $turn, forced pass")
            passed
          else applyTurnChecked(rolled, paths(rng.nextInt(paths.size)), s"game $game turn $turn")
        turnsPlayed += 1
        val whiteKingGone = (state.kings.value & state.whitePieces.value) == 0L
        val blackKingGone = (state.kings.value & state.blackPieces.value) == 0L
        over = whiteKingGone || blackKingGone
        turn += 1

    assert(turnsPlayed > games * 10, s"walk too short to mean anything: $turnsPlayed turns")

  // --- Kept from the original spec: exhaustive first-turn sweep ---------------------------------------------------

  test("incremental updates match full recalculation across every legal first turn"):
    val initial = parse(FenParser.InitialPosition).withDicePool(List(1, 2, 4))
    val paths   = TurnGenerator.generateAllLegalTurnPaths(initial)
    assert(paths.nonEmpty)
    for path <- paths do
      val ended = path.foldLeft(initial)((s, move) => s.makeMove(move)).endTurn()
      assertEquals(ended.zobristHash, Zobrist.computeKey(ended))

object ZobristSpec:
  /** Expected Zobrist key of the initial position (no dice rolled). See the reproducibility test. */
  val InitialPositionKey: Long = 147143207481438466L
