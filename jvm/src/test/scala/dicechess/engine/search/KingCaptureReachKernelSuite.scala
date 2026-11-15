// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.{LeaperAttacks, MagicBitboards, MoveGenerator, PawnGeneration}
import munit.FunSuite

class KingCaptureReachKernelSuite extends FunSuite:
  // Keep the unchanged level-one boundary observable as its reach dispatcher is refactored.
  private val kernel =
    val methods = Option(KingCaptureProbability.getClass.getDeclaredMethods).getOrElse(fail("Missing KCP methods"))
    val method  = methods.iterator
      .flatMap(m => Option(m))
      .find(m => Option(m.getName).exists(_.startsWith("exploreLevel1")))
      .getOrElse(fail("Missing exploreLevel1"))
    method.setAccessible(true)
    method

  private def union(targets: Bitboard)(attacks: Square => Bitboard): Long =
    (0 until 64)
      .map(Square.fromIndex)
      .filter(targets.contains)
      .foldLeft(0L)((acc, square) => acc | attacks(square).value)

  private def evaluate(state: GameState, targets: Bitboard, attacker: Color, unsolved: Long): Long =
    val occupied                     = state.whitePieces | state.blackPieces
    val mine                         = if attacker.isWhite then state.whitePieces else state.blackPieces
    val enemy                        = if attacker.isWhite then state.blackPieces else state.whitePieces
    val knightMask                   = Bitboard(union(targets)(LeaperAttacks.knightAttacksFor))
    val kingMask                     = Bitboard(union(targets)(LeaperAttacks.kingAttacksFor))
    val context: Map[String, AnyRef] = Map(
      "d1"               -> Int.box(1),
      "isWhite"          -> Boolean.box(attacker.isWhite),
      "myPieces1"        -> Long.box(mine.value),
      "enemyPieces1"     -> Long.box(enemy.value),
      "allPieces1"       -> Long.box(occupied.value),
      "p1"               -> Long.box(state.pawns.value),
      "n1"               -> Long.box(state.knights.value),
      "bi1"              -> Long.box(state.bishops.value),
      "r1"               -> Long.box(state.rooks.value),
      "q1"               -> Long.box(state.queens.value),
      "k1"               -> Long.box(state.kings.value),
      "ep1"              -> Long.box(0L), // No fixture root has an inherited en-passant target.
      "enemyKings"       -> Long.box((state.kings & enemy).value),
      "targets"          -> Long.box(targets.value),
      "targetPawnMask"   -> Long.box(PawnGeneration.anyAttacks(targets, attacker.opponent).value),
      "targetKnightMask" -> Long.box(knightMask.value),
      "targetKingMask"   -> Long.box(kingMask.value),
      "targetDiagMask"   -> Long.box(union(targets)(sq => MagicBitboards.bishopAttacks(sq, Bitboard.empty))),
      "targetOrthoMask"  -> Long.box(union(targets)(sq => MagicBitboards.rookAttacks(sq, Bitboard.empty))),
      "knightReach2Mask" -> Long.box(union(knightMask)(LeaperAttacks.knightAttacksFor)),
      "kingReach2Mask"   -> Long.box(union(kingMask)(LeaperAttacks.kingAttacksFor)),
      "diagRay1"         -> Long.box(union(targets)(sq => MagicBitboards.bishopAttacks(sq, occupied))),
      "orthoRay1"        -> Long.box(union(targets)(sq => MagicBitboards.rookAttacks(sq, occupied))),
      "moves1"           -> new Array[Int](256),
      "startUnsolved"    -> Long.box(unsolved)
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
      .getOrElse(fail("No level-one result"))
      .asInstanceOf[java.lang.Long]
      .longValue()

  test("each selected type clears exactly its isolated roll when it can reach an attack") {
    for (fixture, attacker) <- KingCaptureReachCases.cases do
      val state   = FenParser.parse(fixture.fen).fold(fail(_), identity).withDicePool(List(1, fixture.die, fixture.die))
      val enemy   = if attacker.isWhite then state.blackPieces else state.whitePieces
      val targets = state.kings & enemy
      val (from, to) =
        if fixture.die == 1 then (Square('e', 2), Square('e', 4)) else (Square('h', 2), Square('h', 3))
      def oriented(square: Square): Square =
        if attacker.isWhite then square else Square.fromIndex(square.index ^ 56)
      val move = MoveGenerator
        .generatePieceMoves(state, PieceType.Pawn)
        .find(m => m.fromSquare == oriented(from) && m.toSquare == oriented(to))
        .getOrElse(fail("Missing preparation Pawn move"))
      val roll  = List(1, fixture.die, fixture.die).sorted
      val index = DiceRolls.weighted.indexWhere(_._1 == roll)
      assert(index >= 0, fixture.name)
      val bit      = 1L << index
      val expected = if fixture.reachesAttack then 0L else bit
      assertEquals(evaluate(state.makeMove(move), targets, attacker, bit), expected, fixture.name)
  }
