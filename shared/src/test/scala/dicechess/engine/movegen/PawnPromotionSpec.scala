package dicechess.engine.movegen

import munit.FunSuite
import dicechess.engine.domain.{FenParser, Move, Square}

/** Integration tests verifying that [[MoveGenerator]] correctly produces exactly 4 promotion
  * [[dicechess.engine.domain.MicroMove]]s (Queen, Rook, Bishop, Knight) per promotion target square, while leaving
  * non-promotion pawn moves unchanged.
  *
  * Dice roll 1 → Pawn piece type.
  */
class PawnPromotionSpec extends FunSuite:

  /** Parses a FEN string and panics on failure — safe for test fixtures. */
  private def parseUnsafe(fen: String) =
    FenParser.parse(fen).fold(err => sys.error(err), identity)

  // Dice value for Pawn
  private val PawnDice = 1

  test("White pawn on e7: single push to e8 generates exactly 4 promotions") {
    // Only the white pawn exists; e8 is empty; Black king on a8
    val state = parseUnsafe("k7/4P3/8/8/8/8/8/4K3 w - - 0 1")
    val moves = MoveGenerator.generateMoves(state.withDicePool(List(PawnDice)))

    val promotions = moves.filter(_.isPromotion)
    assertEquals(promotions.size, 4, s"Expected 4 promotions, got: $moves")

    // All 4 promotion moves land on e8
    val toSquares = promotions.map(_.toSquare).toSet
    assertEquals(toSquares, Set(Square('e', 8)))
  }

  test("Black pawn on d2: single push to d1 generates exactly 4 promotions") {
    // Black pawn on d2; d1 is empty; White king on h1
    val state = parseUnsafe("4k3/8/8/8/8/8/3p4/7K b - - 0 1")
    val moves = MoveGenerator.generateMoves(state.withDicePool(List(PawnDice)))

    val promotions = moves.filter(_.isPromotion)
    assertEquals(promotions.size, 4, s"Expected 4 promotions, got: $moves")

    val toSquares = promotions.map(_.toSquare).toSet
    assertEquals(toSquares, Set(Square('d', 1)))
  }

  test("White pawn promotion capture: g7 captures h8 generates 4 promotion-captures") {
    // White pawn on g7; black rook on h8; g8 is empty; Black king on a8
    val state = parseUnsafe("k6r/6P1/8/8/8/8/8/4K3 w - - 0 1")
    val moves = MoveGenerator.generateMoves(state.withDicePool(List(PawnDice)))

    // Promotion-captures land on h8
    val promoCapturesH8 = moves.filter(m => m.isPromotion && m.toSquare == Square('h', 8))
    assertEquals(promoCapturesH8.size, 4, s"Expected 4 promotion-captures on h8, got: $moves")

    // Quiet push-promotions land on g8
    val promoPushG8 = moves.filter(m => m.isPromotion && m.toSquare == Square('g', 8))
    assertEquals(promoPushG8.size, 4, s"Expected 4 push-promotions on g8, got: $moves")

    assertEquals(moves.filter(_.isPromotion).size, 8)
  }

  test("Black pawn promotion capture: b2 captures a1 generates 4 promotion-captures") {
    // Black pawn on b2; white rook on a1; b1 is empty; White king on h1
    val state = parseUnsafe("4k3/8/8/8/8/8/1p6/R6K b - - 0 1")
    val moves = MoveGenerator.generateMoves(state.withDicePool(List(PawnDice)))

    // Promotion-captures land on a1
    val promoCapturesA1 = moves.filter(m => m.isPromotion && m.isCapture && m.toSquare == Square('a', 1))
    assertEquals(promoCapturesA1.size, 4, s"Expected 4 promotion-captures on a1, got: $moves")

    // Quiet push-promotions land on b1
    val promoPushB1 = moves.filter(m => m.isPromotion && !m.isCapture && m.toSquare == Square('b', 1))
    assertEquals(promoPushB1.size, 4, s"Expected 4 push-promotions on b1, got: $moves")
  }

  test("Standard pawn on e2 produces no promotions") {
    val state = parseUnsafe("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1")
    val moves = MoveGenerator.generateMoves(state.withDicePool(List(PawnDice)))

    val promotions = moves.filter(_.isPromotion)
    assert(promotions.isEmpty, s"Expected no promotions for e2 pawn, got: $promotions")

    // Should have a quiet single push (e3) and a double push (e4)
    assert(moves.exists(m => m.toSquare == Square('e', 3) && !m.isPromotion))
    assert(moves.exists(m => m.toSquare == Square('e', 4) && m.flags == Move.DoublePawnPush))
  }
