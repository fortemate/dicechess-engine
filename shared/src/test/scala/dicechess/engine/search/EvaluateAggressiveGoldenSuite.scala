package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Golden values for [[Evaluator.evaluateAggressive]] (#46).
  *
  * The expected numbers were captured from the pre-refactor implementation, before the three heuristic terms (pawn
  * storm, king proximity, king-ring pressure) were extracted into helpers to bring the method under the cognitive
  * complexity limit. The refactor is a pure decomposition, so every value must match exactly for both colours. The
  * positions cover the JMH benchmark set, the scenarios [[AggressiveSearchSuite]] and [[KingRingPressureSuite]] rely
  * on, and a kings-only position where every heuristic term is zero.
  */
class EvaluateAggressiveGoldenSuite extends FunSuite:

  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(err => fail(s"Failed to parse FEN: $err"), identity)

  /** `(fen, white score, black score)`. */
  private val golden: List[(String, Int, Int)] = List(
    ("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 150, 150),
    ("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 475, 455),
    ("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 120, 135),
    ("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1", 50, 50),
    ("k7/4P3/8/8/8/8/8/4K3 w - - 0 1", 175, -100),
    ("4k3/8/8/8/8/8/3P4/K7 w - - 0 1", 100, -100),
    ("7k/8/4NP2/8/8/8/8/K5R1 w - - 0 1", 1160, -900),
    ("7k/8/5P2/4N3/8/8/8/K5R1 w - - 0 1", 1135, -900),
    ("8/8/8/8/8/8/8/K6k w - - 0 1", 0, 0)
  )

  golden.foreach { case (fen, white, black) =>
    test(s"evaluateAggressive is unchanged for $fen"):
      val state = parse(fen)
      assertEquals(Evaluator.evaluateAggressive(state, Color.White), white, "white")
      assertEquals(Evaluator.evaluateAggressive(state, Color.Black), black, "black")
  }

  test("evaluateAggressive falls back to the standard evaluation when the enemy king is absent"):
    val state = parse("8/8/8/8/8/8/8/K6q w - - 0 1")
    assertEquals(Evaluator.evaluateAggressive(state, Color.White), Evaluator.evaluate(state, Color.White))
