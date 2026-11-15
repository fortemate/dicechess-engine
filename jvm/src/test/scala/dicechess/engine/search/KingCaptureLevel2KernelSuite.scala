// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.{LeaperAttacks, MagicBitboards, MoveGenerator, PawnGeneration}
import munit.FunSuite

class KingCaptureLevel2KernelSuite extends FunSuite:

  // Exercise the private kernel independently of the earlier roll-pruning fast paths.
  // Reflection stays in this JVM test; the production visibility and signature remain unchanged.
  private val kernel =
    val methods = Option(KingCaptureProbability.getClass.getDeclaredMethods).getOrElse(fail("Missing KCP methods"))
    val method  = methods.iterator
      .flatMap(m => Option(m))
      .find(m => Option(m.getName).contains("evaluateLevel2Move"))
      .getOrElse(fail("Missing evaluateLevel2Move"))
    method.setAccessible(true)
    method

  private def squares(targets: Bitboard): List[Square] =
    (0 until 64).map(Square.fromIndex).filter(targets.contains).toList

  private def union(targets: Bitboard)(attacks: Square => Bitboard): Long =
    squares(targets).foldLeft(0L)((acc, square) => acc | attacks(square).value)

  private def evaluate(state: GameState, move: Move, targets: Bitboard, attacker: Color): Int =
    val occupied = state.whitePieces | state.blackPieces
    val mine     = if attacker.isWhite then state.whitePieces else state.blackPieces
    val args     = Array[AnyRef](
      Int.box(move.toInt),
      Int.box(state.mailbox(move.fromSquare).pieceType.diceValue),
      Boolean.box(attacker.isWhite),
      Long.box(mine.value),
      Long.box(occupied.value),
      Long.box(state.pawns.value),
      Long.box(state.knights.value),
      Long.box(state.bishops.value),
      Long.box(state.rooks.value),
      Long.box(state.queens.value),
      Long.box(state.kings.value),
      Long.box(targets.value),
      Long.box(PawnGeneration.anyAttacks(targets, attacker.opponent).value),
      Long.box(union(targets)(LeaperAttacks.knightAttacksFor)),
      Long.box(union(targets)(LeaperAttacks.kingAttacksFor)),
      Long.box(union(targets)(sq => MagicBitboards.bishopAttacks(sq, occupied))),
      Long.box(union(targets)(sq => MagicBitboards.rookAttacks(sq, occupied)))
    )
    Option(kernel.invoke(KingCaptureProbability, args*))
      .getOrElse(fail("The KCP kernel returned no result"))
      .asInstanceOf[java.lang.Integer]
      .intValue()

  test("second-move capture masks match immutable move application for every moving type and special move") {
    val fens = List(
      "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
      "2k5/8/8/8/8/8/8/Q6K w - - 0 1",
      "1n1qk3/2P5/8/8/8/8/2p5/1N1QK3 w - - 0 1",
      "4k3/8/8/3pP3/8/8/8/4R1K1 w - d6 0 1",
      "4r1k1/8/8/8/3pP3/8/8/4K3 b - e3 0 1"
    )
    val cases = for
      fen      <- fens
      attacker <- List(Color.White, Color.Black)
      state = FenParser.parse(fen).fold(fail(_), identity).withActiveColor(attacker)
      move <- MoveGenerator.generateAllMoves(state).filterNot(_.isCastling)
    yield (state, move, attacker)

    for (state, move, attacker) <- cases do
      val enemy = if attacker.isWhite then state.blackPieces else state.whitePieces
      val die   = state.mailbox(move.fromSquare).pieceType.diceValue
      val after = state.withDicePool(List(die)).makeMove(move)
      for targets <- List(state.kings & enemy, state.queens & enemy).filterNot(_.isEmpty) do
        val expected = squares(targets).foldLeft(0)((mask, sq) => mask | AttackerTypes.mask(after, sq, attacker))
        assertEquals(evaluate(state, move, targets, attacker), expected, s"attacker=$attacker move=$move state=$state")

    val ordinaryTypes = cases.collect {
      case (state, move, _) if !move.isPromotion && move.flags != Move.EnPassantCapture =>
        state.mailbox(move.fromSquare).pieceType.diceValue
    }.toSet
    val promotionFlags = cases.collect { case (_, move, _) if move.isPromotion => move.flags }.toSet
    val epColors       = cases.collect {
      case (_, move, attacker) if move.flags == Move.EnPassantCapture => attacker.value
    }.toSet
    assertEquals(ordinaryTypes, (1 to 6).toSet)
    assertEquals(promotionFlags, (8 to 15).toSet)
    assertEquals(epColors, Set(Color.White.value, Color.Black.value))
  }

  test("a quiet queen second move creates a Queen capture bit that was absent before the move") {
    val state  = FenParser.parse("2k5/8/8/8/8/8/8/Q6K w - - 0 1").fold(fail(_), identity)
    val target = Square('c', 8)
    val move   = MoveGenerator
      .generatePieceMoves(state, PieceType.Queen)
      .find(_.toSquare == Square('a', 8))
      .getOrElse(fail("Missing a1-a8 Queen move"))
    assertEquals(AttackerTypes.mask(state, target, Color.White), AttackerTypes.Empty)
    val expected = AttackerTypes.mask(state.withDicePool(List(5)).makeMove(move), target, Color.White)
    assertEquals(expected, AttackerTypes.bit(PieceType.Queen))
    assertEquals(evaluate(state, move, Bitboard.fromSquare(target), Color.White), expected)
  }
