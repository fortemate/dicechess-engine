package dicechess.engine.search

import dicechess.engine.domain.Color

/** A documented king-capture scenario with an exact count of successful ordered 3d6 rolls.
  *
  * The defender is explicit: KCP evaluates a fresh attacker turn, independently of the FEN's active color and dice. The
  * denominator is deliberately a literal test oracle, independent of the production dice distribution.
  */
case class KingCaptureTestCase(
    name: String,
    description: String,
    fen: String,
    defenderColor: Color,
    winningRolls: Int,
    rationale: String
):
  def expectedKingProbability: Double = winningRolls.toDouble / 216.0

/** The 14 original JSON scenarios, shared by JVM, JavaScript, Wasm and the documentation generator.
  *
  * Counts come from the dice events explained in each rationale, rather than rounded decimal probabilities. Keep the
  * original names, descriptions, FEN strings and order so the published catalog remains recognizable.
  */
object KingCaptureFixtures:
  val cases: List[KingCaptureTestCase] = List(
    KingCaptureTestCase(
      name = "Kings at a distance",
      description = "Kings positioned on opposite ends of the board with no intervening or threatening pieces.",
      fen = "4k3/8/8/8/8/8/8/4K3 b - -",
      defenderColor = Color.White,
      winningRolls = 0,
      rationale = "No king can cross the seven-rank gap in three micro-moves: 0 winning rolls."
    ),
    KingCaptureTestCase(
      name = "At least one bishop",
      description = "A single bishop directly attacks the king, making it vulnerable whenever that piece type appears.",
      fen = "rnbqkbnr/pppppBpp/8/8/4P3/8/PPPP1PPP/RNB1K1NR w KQkq - 0 1",
      defenderColor = Color.Black,
      winningRolls = 91,
      rationale = "Bishop f7 captures e8 directly. At least one Bishop die: 216 - 5^3 = 91."
    ),
    KingCaptureTestCase(
      name = "At least one of two pieces",
      description = "Multiple attacking pieces are present, creating a threat if either piece type is rolled.",
      fen = "rnb1qbnr/pppppkpp/8/6N1/8/4PQ2/PPPP1PPP/RNB1K2R w KQ - 0 1",
      defenderColor = Color.Black,
      winningRolls = 152,
      rationale = "Knight g5 or Queen f3 captures f7 directly. At least one of these two types: 216 - 4^3 = 152."
    ),
    KingCaptureTestCase(
      name = "Two pieces (different)",
      description = "Two different attacking pieces must both appear on the dice to facilitate a capture threat.",
      fen = "rnbq1bnr/ppppkppp/8/4p3/1P6/B7/P1PPPPPP/R3KBNR w KQ - 0 1",
      defenderColor = Color.Black,
      winningRolls = 30,
      rationale = "Pawn b4-b5 clears Bishop a3-e7. At least one Pawn and one Bishop: 216 - 2 * 5^3 + 4^3 = 30."
    ),
    KingCaptureTestCase(
      name = "Two identical pieces (doubles)",
      description = "Vulnerability depends on rolling a specific pair of identical attacking pieces.",
      fen = "rnbqkbnr/pppppppp/8/8/2B1P3/8/PPPP1PPP/RNB1K1NR w KQ - 0 1",
      defenderColor = Color.Black,
      winningRolls = 16,
      rationale = "Bishop c4-f7-e8 needs at least two Bishop dice: 3 * 5 + 1 = 16."
    ),
    KingCaptureTestCase(
      name = "Three identical pieces (triplets)",
      description = "The king is threatened only if three identical attacking pieces are rolled simultaneously.",
      fen = "rnbqkbnr/pppppppp/8/8/8/2N5/PPPPPPPP/R1BQKBNR w KQ - 0 1",
      defenderColor = Color.Black,
      winningRolls = 1,
      rationale = "Knight c3-e4-d6-e8 needs three Knight dice: 1 ordered roll."
    ),
    KingCaptureTestCase(
      name = "One of three pieces",
      description = "Multiple attacking piece types are capable of threatening the king if any one of them appears.",
      fen = "rnbq1bnr/ppppkppp/4p3/3N4/3P3B/Q7/PPP1PPPP/R3KBNR w KQ - 0 1",
      defenderColor = Color.Black,
      winningRolls = 189,
      rationale = "Knight d5, Bishop h4 or Queen a3 captures e7 directly. At least one of three types: 216 - 3^3 = 189."
    ),
    KingCaptureTestCase(
      name = "One of four pieces",
      description =
        "High likelihood of king capture threat as it depends on any one of four different attacker types appearing.",
      fen = "rnbq1bnr/ppppkppp/8/3N2B1/P2P4/Q3R3/1PP1PPPP/4KBNR w K - 0 1",
      defenderColor = Color.Black,
      winningRolls = 208,
      rationale =
        "Knight d5, Bishop g5, Rook e3 or Queen a3 captures e7 directly. At least one of four types: 216 - 2^3 = 208."
    ),
    KingCaptureTestCase(
      name = "One of five pieces",
      description = "Almost certain capture threat as nearly any piece type appearing on the dice enables the attack.",
      fen = "4R3/8/8/Q3k3/3P4/5N2/7B/6K1 w - - 0 1",
      defenderColor = Color.Black,
      winningRolls = 215,
      rationale =
        "Pawn d4, Knight f3, Bishop h2, Rook e8 or Queen a5 captures e5 directly. Only three King dice fail: 216 - 1 = 215."
    ),
    KingCaptureTestCase(
      name = "Three different pieces",
      description = "Threat requires the specific combination of three different attacking piece types.",
      fen = "rnbq1bnr/pppppkpp/8/3N4/2P5/1B1P2P1/PP2PP1P/R1B1K2R w KQ - 0 1",
      defenderColor = Color.Black,
      winningRolls = 6,
      rationale =
        "Pawn c4-c5 and a Knight move from d5 clear Bishop b3-f7. One Pawn, Knight and Bishop: 3! = 6 ordered rolls."
    ),
    KingCaptureTestCase(
      name = "Two identical pieces + one different piece",
      description =
        "Threat relies on a specific combination consisting of a pair of identical pieces and one different piece.",
      fen = "rnbq1bnr/pppppkpp/8/8/8/2P5/PP1PPPPP/R1B1KB1R w KQ - 0 1",
      defenderColor = Color.Black,
      winningRolls = 3,
      rationale = "Pawn e2-e3 clears Bishop f1-c4-f7. One Pawn and two Bishop dice: 3 ordered rolls."
    ),
    KingCaptureTestCase(
      name = "Queen and Bishop threatening the King",
      description = "Combined attacking influence from both the queen and bishop.",
      fen = "rnbqkbnr/pppppppp/8/1B6/4P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 0 1",
      defenderColor = Color.Black,
      winningRolls = 32,
      rationale =
        "Bishop b5-d7-e8 or Queen f3-f7-e8 needs a matching pair. These events are disjoint in three dice: 16 + 16 = 32."
    ),
    KingCaptureTestCase(
      name = "A bishop and the three queens",
      description = "Direct attacking pressure combined with support from multiple queen threats.",
      fen = "rnbqkbnr/pppppBpp/8/8/4P3/8/PPPP1PPP/RNBQK1NR w KQkq - 0 1",
      defenderColor = Color.Black,
      winningRolls = 92,
      rationale =
        "Bishop f7-e8 needs any Bishop die (91 rolls); Queen d1-g4-d7-e8 adds the disjoint triple-Queen roll: 91 + 1 = 92."
    ),
    KingCaptureTestCase(
      name = "Two pieces or queens",
      description = "Capture threat necessitates specific different piece types to be rolled.",
      fen = "rnbq1bnr/ppppkppp/8/4p3/1P6/B7/P1PPPPPP/RN1QKBNR w KQ - 0 1",
      defenderColor = Color.Black,
      winningRolls = 31,
      rationale =
        "Pawn b4-b5 clears Bishop a3-e7 (30 rolls); Knight b1-c3-d5-e7 adds the disjoint triple-Knight roll: 30 + 1 = 31. The historical title mentions queens, but the extra winning roll is Knights."
    )
  )
