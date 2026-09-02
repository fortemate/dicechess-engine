package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.*
import munit.FunSuite
import scala.concurrent.duration.*
import scala.util.Random

class ExpectimaxTTSpec extends FunSuite:

  override def munitTimeout: Duration = 3.minutes

  private val evalMaterialBatch: (Array[GameState], Color) => Array[Int] =
    (states, color) => states.map(Evaluator.evaluateMaterial(_, color))

  test("Acceptance Gate 1: bit-exact search values with TT on vs off (exact-entry-only mode) on scenario suite"):
    val fixtures       = SearchFixtureCatalog.load(None).fold(fail(_), identity)
    val candidateLimit = 16

    for scenario <- fixtures.scenarios do
      val state = scenario.state
      val seed  = 42L

      val ttOff = new ExpectimaxSearch(
        evalBatch = evalMaterialBatch,
        config = ExpectimaxConfig(candidateLimit = candidateLimit, exactOnlyMode = true),
        tt = None
      )

      val ttTable = new TranspositionTable(1024)
      val ttOn    = new ExpectimaxSearch(
        evalBatch = evalMaterialBatch,
        config = ExpectimaxConfig(candidateLimit = candidateLimit, exactOnlyMode = true),
        tt = Some(ttTable)
      )

      val resOff = ttOff.findBestMove(state, new Random(seed))
      val resOn  = ttOn.findBestMove(state, new Random(seed))

      (resOff, resOn) match
        case (Some(off), Some(on)) =>
          // ScoredSequence exposes the truncated Int score — the strongest observable equality at this seam. The
          // Double-level bit-exactness lives one layer down: TT entries store raw Double bits
          // (TranspositionTableSpec round-trip test), and any Double divergence that changed a ranking would
          // surface here as a move-sequence mismatch.
          assertEquals(off.score, on.score, s"Score mismatch for scenario '${scenario.id}'")
          assertEquals(off.moves, on.moves, s"Move sequence mismatch for scenario '${scenario.id}'")
        case (None, None) => ()
        case _            => fail(s"Result mismatch (one None, one Some) for scenario '${scenario.id}'")

  test("Acceptance Gate 2: hit-rate telemetry on middlegame positions"):
    // Position with high branching where independent micro-moves transpose to identical positions
    val middlegameFen = "r1bqk2r/pppp1ppp/2n2n2/4p3/2B1P3/2P2N2/PPPP1PPP/RNBQK2R w KQkq - 0 1 PNB"
    val state         = FenParser.parse(middlegameFen).fold(fail(_), identity)

    val ttTable = new TranspositionTable(1024)
    val search  = new ExpectimaxSearch(
      evalBatch = evalMaterialBatch,
      config = ExpectimaxConfig(candidateLimit = 16),
      tt = Some(ttTable)
    )

    val result = search.findBestMove(state, new Random(42L))
    assert(result.isDefined)

    assert(ttTable.stores > 0L, "TT should store evaluated chance nodes")
    assert(ttTable.hits > 0L, "TT should register hits on transposed positions")
    assert(ttTable.hitRate > 0.0, s"TT hit rate should be positive, got ${ttTable.hitRate}")

  test("TT with Star1/Star2 upper bounds preserves the decision of a TT-less search and records stores"):
    val fen   = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1 PNB"
    val state = FenParser.parse(fen).fold(fail(_), identity)

    val config = ExpectimaxConfig(candidateLimit = 12, exactOnlyMode = false)
    val ttOff  = new ExpectimaxSearch(evalBatch = evalMaterialBatch, config = config, tt = None)

    val ttTable = new TranspositionTable(1024)
    val ttOn    = new ExpectimaxSearch(evalBatch = evalMaterialBatch, config = config, tt = Some(ttTable))

    val resOff = ttOff.findBestMove(state, new Random(123L))
    val resOn  = ttOn.findBestMove(state, new Random(123L))
    assertEquals(resOn.map(_.moves), resOff.map(_.moves), "TT with upper-bound cutoffs must not change the decision")
    assertEquals(resOn.map(_.score), resOff.map(_.score))
    assert(ttTable.stores > 0L, "TT should record stores")
