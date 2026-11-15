// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite

private[search] object KingCaptureMaskCases:
  private val attackers = List(('d', 3, 'P'), ('c', 3, 'N'), ('h', 7, 'B'), ('e', 1, 'R'), ('a', 4, 'Q'), ('d', 5, 'K'))

  val cases: List[(String, Color, Int)] =
    for
      color <- List(Color.White, Color.Black)
      mask  <- (0 until 64).toList
    yield (fen(mask, color), color, mask)

  val castleCases: List[(String, Color, List[Int])] = List(
    ("8/8/8/8/8/8/6k1/4K2R w K - 0 1", Color.White, List(16, 0, 32, 0)),
    ("4k2r/6K1/8/8/8/8/8/8 b k - 0 1", Color.Black, List(32, 0, 16, 0))
  )

  private def fen(mask: Int, attacker: Color): String =
    // The distant Pawn keeps the attacker nonempty even for mask zero, without reaching e4 in three moves.
    val pieces = scala.collection.mutable.Map(Square('a', 2).index -> 'P', Square('e', 4).index -> 'k')
    for ((file, rank, piece), bit) <- attackers.zipWithIndex do
      if (mask & (1 << bit)) != 0 then pieces(Square(file, rank).index) = piece
    val oriented = pieces.iterator.map { (index, piece) =>
      if attacker.isWhite then (index, piece)
      else (index ^ 56, if piece.isUpper then piece.toLower else piece.toUpper)
    }.toMap
    val ranks = (7 to 0 by -1).map(rank => encodeRank(oriented, rank))
    s"${ranks.mkString("/")} ${if attacker.isWhite then "w" else "b"} - - 0 1"

  private def encodeRank(pieces: Map[Int, Char], rank: Int): String =
    val text  = new StringBuilder
    var empty = 0
    for file <- 0 until 8 do
      pieces.get(rank * 8 + file) match
        case None        => empty += 1
        case Some(piece) =>
          if empty != 0 then text.append(empty)
          text.append(piece)
          empty = 0
    if empty != 0 then text.append(empty)
    text.toString

class KingCaptureMaskContractSuite extends FunSuite:
  for (fen, attacker, mask) <- KingCaptureMaskCases.cases do
    test(s"direct attacker mask $mask for ${if attacker.isWhite then "White" else "Black"}") {
      val state  = FenParser.parse(fen).fold(fail(_), identity)
      val target = if attacker.isWhite then Square('e', 4) else Square('e', 5)
      assertEquals(AttackerTypes.mask(state, target, attacker), mask, fen)
      val absentTypes = 6 - java.lang.Integer.bitCount(mask)
      val expected    = (216 - absentTypes * absentTypes * absentTypes).toDouble / 216
      assertEquals(KingCaptureProbability.kingCaptureProbability(state, attacker.opponent), expected, fen)
    }

  for (fen, attacker, counts) <- KingCaptureMaskCases.castleCases do
    test(s"castling adds the King attack type for ${if attacker.isWhite then "White" else "Black"}") {
      val state  = FenParser.parse(fen).fold(fail(_), identity).withDicePool(List(6, 4, 6))
      val target = if attacker.isWhite then Square('g', 2) else Square('g', 7)
      assertEquals(AttackerTypes.mask(state, target, attacker) & 32, 0)
      val castle =
        MoveGenerator.generatePieceMoves(state, PieceType.King).find(_.isCastling).getOrElse(fail("No castle"))
      assertEquals(AttackerTypes.mask(state.makeMove(castle), target, attacker) & 32, 32)
      val actual = List(
        KingCaptureProbability.kingCaptureProbability(state, Color.White),
        KingCaptureProbability.queenCaptureProbability(state, Color.White),
        KingCaptureProbability.kingCaptureProbability(state, Color.Black),
        KingCaptureProbability.queenCaptureProbability(state, Color.Black)
      )
      assertEquals(actual, counts.map(_.toDouble / 216), fen)
    }
