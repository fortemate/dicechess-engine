package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Regression suite for #513: the king-ring pressure term counts every attacker, not just the first matching piece
  * type.
  *
  * The term used to read its count from `MoveGenerator.isSquareAttacked`, which returns the attackers of the first
  * matching type and stops. Probing order being pawns → knights → diagonal → orthogonal → king, a ring square attacked
  * by a pawn *and* a rook scored the same as one attacked by the pawn alone — so a broad multi-piece attack could rank
  * below a narrow same-type one.
  *
  * Both tests hold material, pawn structure and piece-to-king distances constant across the pair they compare, and move
  * a single piece between an attacking and a non-attacking square. Without that, a material or proximity delta would
  * pass for the ring signal under test and the suite would go green while proving nothing.
  */
class KingRingPressureSuite extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)

  /** Black king h8, so its ring is g7 / g8 / h7. White rook g1 attacks g7 and g8 up the open g-file in every position
    * below, giving a constant backdrop against which one extra attacker on g7 is the only variable.
    */
  private val pawnOffRing = "7k/8/1P6/8/8/8/8/K5R1 w - - 0 1"   // pawn b6 attacks a7/c7 — outside the ring
  private val pawnOnRing  = "7k/8/5P2/8/8/8/8/K5R1 w - - 0 1"   // pawn f6 attacks e7/g7 — one ring square
  private val knightOff   = "7k/8/5P2/4N3/8/8/8/K5R1 w - - 0 1" // knight e5 attacks no ring square
  private val knightOn    = "7k/8/4NP2/8/8/8/8/K5R1 w - - 0 1"  // knight e6 also attacks g7

  test("a second attacker of a different type raises ring pressure by exactly one RingWeight"):
    // Both pawns sit on rank 6, so the pawn-storm bonus is identical; pawns do not enter the proximity
    // term at all. The only difference is that g7 goes from one attacker (rook) to two (rook + pawn).
    // Under the old early-exiting probe the pawn was returned first and counted as one, so these two
    // positions scored the same — this assertion is what the bug made impossible.
    val withOne = Evaluator.evaluateAggressive(parse(pawnOffRing), Color.White)
    val withTwo = Evaluator.evaluateAggressive(parse(pawnOnRing), Color.White)
    assertEquals(
      withTwo - withOne,
      Evaluator.RingWeight,
      "adding a pawn to a ring square already attacked by a rook must add one attacker's worth"
    )

  test("ring pressure keeps scaling with a third attacker, and does not care about the mix of types"):
    // e5 and e6 are both Chebyshev distance 3 from h8, so the knight contributes the same proximity
    // bonus either way; only its attack on g7 appears or disappears. g7 therefore goes from two
    // attackers (rook + pawn) to three (rook + pawn + knight) — three distinct types, counted in full.
    val twoAttackers   = Evaluator.evaluateAggressive(parse(knightOff), Color.White)
    val threeAttackers = Evaluator.evaluateAggressive(parse(knightOn), Color.White)
    assertEquals(
      threeAttackers - twoAttackers,
      Evaluator.RingWeight,
      "a knight joining a rook and a pawn on the same ring square must count as the third attacker"
    )
