package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.TurnGenerator
import munit.FunSuite

/** Pins the corpus-row parsing behind [[PreRankRecallProbeMain]].
  *
  * The probe's headline number — how often the candidate cut changes the search's decision — is computed over positions
  * built by this function. A dropped or mis-mapped die would shrink each position's legal-turn count, quietly moving
  * both the "exposed to the cut" population and the rate measured over it, with nothing in the output to show for it.
  */
class PreRankRecallProbeSpec extends FunSuite:

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"

  test("each die letter maps to its piece type, in order"):
    val state = parseState(startFen, "PNB").get
    assertEquals(
      state.dicePool.toList,
      List(PieceType.Pawn.diceValue, PieceType.Knight.diceValue, PieceType.Bishop.diceValue)
    )

  test("all six letters are recognised, across two rolls"):
    // Two rolls rather than one six-letter string, because a pool holds exactly three dice — see the truncation test.
    val first  = parseState(startFen, "PNB").get.dicePool.toSet
    val second = parseState(startFen, "RQK").get.dicePool.toSet
    assertEquals(first ++ second, PieceType.all.map(_.diceValue).toSet)

  test("a pool longer than three dice is silently truncated"):
    // Not a defect of the probe but of the platform it sits on: GameFlags packs three dice into bit fields via
    // lift(0..2), so a fourth is dropped without complaint. Harmless for this probe — a Dice Chess roll is always
    // three — but pinned here so nobody later feeds it a longer pool and trusts the result.
    val state = parseState(startFen, "PNBRQK").get
    assertEquals(state.dicePool.size, 3)
    assertEquals(
      state.dicePool.toList,
      List(PieceType.Pawn.diceValue, PieceType.Knight.diceValue, PieceType.Bishop.diceValue)
    )

  test("lowercase is accepted — the corpus stores the roll case-shifted by side to move"):
    val upper = parseState(startFen, "PQK").get
    val lower = parseState(startFen, "pqk").get
    assertEquals(lower.dicePool.toList, upper.dicePool.toList)

  test("a repeated die is kept, not collapsed"):
    // The pool is a multiset: three pawns is a legitimate roll and must not become one.
    assertEquals(parseState(startFen, "PPP").get.dicePool.size, 3)

  test("an empty or unrecognised roll yields no position rather than a dice-free one"):
    // A position with an empty pool would generate no legal turns and silently drop out of the sample.
    assertEquals(parseState(startFen, ""), None)
    assertEquals(parseState(startFen, "xyz"), None)

  test("a partly unreadable roll is rejected rather than under-filled"):
    // The dangerous case is not the wholly invalid roll but the mixed one: keeping just the letters we recognise
    // yields a VALID position with fewer dice, hence fewer legal turns, which would enter the sample and shift the
    // "exposed to the cut" population the probe measures. Verified unnecessary for today's corpus — 0 malformed
    // rolls in 1,311,296 rows — and kept so a future corpus cannot bias the number without failing here first.
    assertEquals(parseState(startFen, "P!B"), None)
    // The boundary the rule must NOT cross: a short roll whose every letter is readable is still a roll. The check
    // is about characters the parser cannot map, not about the pool's length — length is the platform's business.
    assertEquals(parseState(startFen, "PN").map(_.dicePool.size), Some(2))

  test("an unparseable FEN yields no position"):
    assertEquals(parseState("not-a-fen", "PNB"), None)

  test("the parsed position is playable — the probe's premise"):
    val state = parseState(startFen, "PNB").get
    assert(TurnGenerator.generateAllLegalTurnPaths(state).nonEmpty)
