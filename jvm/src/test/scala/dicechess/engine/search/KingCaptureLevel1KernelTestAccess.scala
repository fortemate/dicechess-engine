// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.{LeaperAttacks, MagicBitboards, PawnGeneration}

import java.lang.reflect.Method

private[search] object KingCaptureLevel1KernelTestAccess extends munit.Assertions:
  private val kernel: Method =
    val methods = Option(KingCaptureProbability.getClass.getDeclaredMethods)
      .getOrElse(fail("Missing KCP methods"))
    val matches = methods.iterator
      .flatMap(m => Option(m))
      .filter(m => Option(m.getName).contains("exploreLevel1$1") && m.getParameterCount == 25)
      .toList
    matches match
      case method :: Nil =>
        method.setAccessible(true)
        method
      case _ => fail("Expected exactly one 25-argument lifted exploreLevel1 method")

  private def union(targets: Bitboard)(attacks: Square => Bitboard): Long =
    (0 until 64)
      .map(Square.fromIndex)
      .filter(targets.contains)
      .foldLeft(0L)((acc, square) => acc | attacks(square).value)

  def evaluate(state: GameState, targets: Bitboard, attacker: Color, unsolved: Long, moves: Array[Int]): Long =
    val occupied   = state.whitePieces | state.blackPieces
    val mine       = if attacker.isWhite then state.whitePieces else state.blackPieces
    val enemy      = if attacker.isWhite then state.blackPieces else state.whitePieces
    val knightMask = Bitboard(union(targets)(LeaperAttacks.knightAttacksFor))
    val kingMask   = Bitboard(union(targets)(LeaperAttacks.kingAttacksFor))
    val args       = Array[AnyRef](
      Int.box(1),
      Boolean.box(attacker.isWhite),
      Long.box(mine.value),
      Long.box(state.bishops.value),
      Long.box(union(targets)(sq => MagicBitboards.bishopAttacks(sq, Bitboard.empty))),
      Long.box(state.queens.value),
      Long.box(state.rooks.value),
      Long.box(union(targets)(sq => MagicBitboards.rookAttacks(sq, Bitboard.empty))),
      Long.box(occupied.value),
      Long.box(state.pawns.value),
      Long.box(state.knights.value),
      Long.box(state.kings.value),
      Long.box(union(targets)(sq => MagicBitboards.bishopAttacks(sq, occupied))),
      Long.box(targets.value),
      Long.box(union(targets)(sq => MagicBitboards.rookAttacks(sq, occupied))),
      Long.box(PawnGeneration.anyAttacks(targets, attacker.opponent).value),
      Long.box(knightMask.value),
      Long.box(kingMask.value),
      Long.box(enemy.value),
      Long.box(union(knightMask)(LeaperAttacks.knightAttacksFor)),
      Long.box(union(kingMask)(LeaperAttacks.kingAttacksFor)),
      Long.box(0L), // No fixture root has an inherited en-passant target.
      Long.box((state.kings & enemy).value),
      moves,
      Long.box(unsolved)
    )
    Option(kernel.invoke(KingCaptureProbability, args*))
      .getOrElse(fail("No level-one result"))
      .asInstanceOf[java.lang.Long]
      .longValue()
