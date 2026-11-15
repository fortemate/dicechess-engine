// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.{LeaperAttacks, MagicBitboards, MoveGenerator, PawnGeneration}
import munit.FunSuite

class KingCaptureRootCastleKernelSuite extends FunSuite:

  // Exercise the private kernel independently of the earlier roll-pruning fast paths.
  // Named scalar inputs keep this observer independent of compiler capture ordering.
  private val kernel =
    val methods = Option(KingCaptureProbability.getClass.getDeclaredMethods).getOrElse(fail("Missing KCP methods"))
    val method  = methods.iterator
      .flatMap(m => Option(m))
      .find(m => Option(m.getName).exists(_.startsWith("applyRootCastle")))
      .getOrElse(fail("Missing applyRootCastle"))
    method.setAccessible(true)
    method

  private def squares(targets: Bitboard): List[Square] =
    (0 until 64).map(Square.fromIndex).filter(targets.contains).toList

  private def union(targets: Bitboard)(attacks: Square => Bitboard): Long =
    squares(targets).foldLeft(0L)((acc, square) => acc | attacks(square).value)

  private def evaluate(state: GameState, move: Move, targets: Bitboard, attacker: Color, unsolved: Long): Long =
    val occupied                     = state.whitePieces | state.blackPieces
    val mine                         = if attacker.isWhite then state.whitePieces else state.blackPieces
    val context: Map[String, AnyRef] = Map(
      "mv0"              -> Int.box(move.toInt),
      "flags"            -> Int.box(move.flags),
      "unsolved"         -> Long.box(unsolved),
      "isWhite"          -> Boolean.box(attacker.isWhite),
      "myPieces0"        -> Long.box(mine.value),
      "allPieces0"       -> Long.box(occupied.value),
      "p0"               -> Long.box(state.pawns.value),
      "n0"               -> Long.box(state.knights.value),
      "bi0"              -> Long.box(state.bishops.value),
      "r0"               -> Long.box(state.rooks.value),
      "q0"               -> Long.box(state.queens.value),
      "k0"               -> Long.box(state.kings.value),
      "targets"          -> Long.box(targets.value),
      "targetPawnMask"   -> Long.box(PawnGeneration.anyAttacks(targets, attacker.opponent).value),
      "targetKnightMask" -> Long.box(union(targets)(LeaperAttacks.knightAttacksFor)),
      "targetKingMask"   -> Long.box(union(targets)(LeaperAttacks.kingAttacksFor)),
      "diagRay0"         -> Long.box(union(targets)(sq => MagicBitboards.bishopAttacks(sq, occupied))),
      "orthoRay0"        -> Long.box(union(targets)(sq => MagicBitboards.rookAttacks(sq, occupied)))
    )
    val names = Option(kernel.getParameters)
      .getOrElse(fail("Missing kernel parameters"))
      .iterator
      .flatMap(p => Option(p))
      .map(p => Option(p.getName).getOrElse(fail("Missing parameter name")).takeWhile(_ != '$'))
      .toList
    assertEquals(names.toSet, context.keySet)
    assertEquals(names.size, context.size)
    val args = names.map(name => context(name)).toArray
    Option(kernel.invoke(KingCaptureProbability, args*))
      .getOrElse(fail("The KCP kernel returned no result"))
      .asInstanceOf[java.lang.Long]
      .longValue()

  private def captureRolls(mask: Int): Long =
    DiceRolls.weighted.zipWithIndex.foldLeft(0L) { case (bits, ((dice, _), index)) =>
      val captures = (1 to 6).exists(die => (mask & (1 << (die - 1))) != 0 && dice.sorted == List(6, 4, die).sorted)
      if captures then bits | (1L << index) else bits
    }

  for (fixture, attacker) <- KingCaptureRootCastleCases.cases do
    test(fixture.name) {
      val state = FenParser.parse(fixture.fen).fold(fail(_), identity).withDicePool(List(6, 4, 1))
      val enemy = if attacker.isWhite then state.blackPieces else state.whitePieces
      val move  = MoveGenerator
        .generatePieceMoves(state, PieceType.King)
        .find(_.flags == fixture.flags)
        .getOrElse(fail("Missing characterized castle"))
      val after   = state.makeMove(move)
      val targets = List(state.kings & enemy, state.queens & enemy).filterNot(_.isEmpty)
      for target <- targets do
        val expectedMask = squares(target).foldLeft(0)((bits, sq) => bits | AttackerTypes.mask(after, sq, attacker))
        val inputs       = List(0L, (1L << 56) - 1L, 1L) ++ DiceRolls.weighted.indices.map(i => 1L << i)
        for unsolved <- inputs do
          assertEquals(evaluate(state, move, target, attacker, unsolved), unsolved & ~captureRolls(expectedMask))
    }
