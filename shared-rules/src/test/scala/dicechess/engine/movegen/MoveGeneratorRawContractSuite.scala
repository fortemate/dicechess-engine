// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.movegen

import dicechess.engine.domain.*
import munit.FunSuite

class MoveGeneratorRawContractSuite extends FunSuite:

  private val Sentinel  = 0x5a5a5a5a
  private val CastleFen = "r3k2r/8/1n6/8/8/1N6/8/R3K2R w KQkq - 0 1"

  private def square(value: String): Square = Square.fromNotation(value).getOrElse(fail(s"Bad square: $value"))

  private def parse(fen: String): GameState = FenParser.parse(fen).fold(fail(_), identity)

  private def buffer(): Array[Int] = Array.fill(256)(Sentinel)

  private def pieces(state: GameState, color: Color): Long =
    if color.isWhite then state.whitePieces.value else state.blackPieces.value

  private def bits(values: List[String]): Long =
    values.foldLeft(0L)((mask, value) => mask | Bitboard.fromSquare(square(value)).value)

  private def play(state: GameState, from: String, to: String, kind: PieceType): GameState =
    val move = Move(square(from), square(to))
    assert(MoveGenerator.generatePieceMoves(state, kind).contains(move))
    state.makeMove(move)

  private def assertMoves(out: Array[Int], count: Int, expected: List[(String, String, Int)]): Unit =
    val encoded = expected.map { case (from, to, flags) => Move(square(from), square(to), flags).toInt }
    assertEquals(count, encoded.length)
    assertEquals(out.take(count).toList, encoded)
    assert(out.drop(count).forall(_ == Sentinel), "Only the returned output prefix may be overwritten")

  for (name, fen, rookFrom, rookTo, from, quiet, capture) <- List(
      ("white", "4k3/8/8/8/8/2p5/3P4/RN2K1N1 w - - 0 1", "a1", "a2", "b1", "a3", "c3"),
      ("black", "rn2k1n1/3p4/2P5/8/8/8/8/4K3 b - - 0 1", "a8", "a7", "b8", "a6", "c6")
    )
  do
    test(s"$name filtered knight keeps full occupancy and exact target order after a rook move") {
      val before   = parse(fen).withDicePool(List(4, 2, 2))
      val state    = play(before, rookFrom, rookTo, PieceType.Rook)
      val color    = before.activeColor
      val mine     = pieces(state, color)
      val enemy    = pieces(state, color.opponent)
      val active   = bits(List(from))
      val expected = List((from, quiet, Move.QuietMove), (from, capture, Move.Capture))
      val out      = buffer()
      val count    = RawMoveGeneratorTestAccess.continuation(
        mine,
        enemy,
        active,
        enemy & state.kings.value,
        0L,
        Piece(color, PieceType.Knight),
        out
      )
      assertMoves(out, count, expected)
      val rootOut = buffer()
      assertMoves(rootOut, RawMoveGeneratorTestAccess.root(state, color, active, 2, rootOut), expected)
    }

  for (name, fen, rookFrom, rookTo, rank, nextRank, target) <- List(
      ("white", "4k3/8/8/2PpP3/8/8/8/R3K3 w - d6 0 1", "a1", "a2", "5", "6", "d6"),
      ("black", "r3k3/8/8/8/2pPp3/8/8/4K3 b - d3 0 1", "a8", "a7", "4", "3", "d3")
    )
  do
    test(s"$name continuation EP applies only to selected pawn candidates") {
      val before = parse(fen).withDicePool(List(4, 1, 1))
      val state  = play(before, rookFrom, rookTo, PieceType.Rook)
      val color  = before.activeColor
      val mine   = pieces(state, color)
      val enemy  = pieces(state, color.opponent)
      // KCP retains this valid target when another piece moves without capturing its victim.
      val ep = before.enPassant.value
      for selected <- List(List("c"), List("e"), List("c", "e")) do
        val from     = selected.map(_ + rank)
        val expected = selected.flatMap(file =>
          List((file + rank, file + nextRank, Move.QuietMove), (file + rank, target, Move.EnPassantCapture))
        )
        val out   = buffer()
        val count = RawMoveGeneratorTestAccess.continuation(
          mine,
          enemy,
          bits(from),
          enemy & state.kings.value,
          ep,
          Piece(color, PieceType.Pawn),
          out
        )
        assertMoves(out, count, expected)
    }

  for color <- List(Color.White, Color.Black) do
    test(s"root castles remain available for $color while continuation castles stay disabled") {
      val state    = parse(CastleFen)
      val rank     = if color.isWhite then "1" else "8"
      val ordinary =
        if color.isWhite then List("d1", "f1", "d2", "e2", "f2")
        else List("d7", "e7", "f7", "d8", "f8")
      val expected = ordinary.map(to => ("e" + rank, to, Move.QuietMove))
      val castles  = List(("e" + rank, "g" + rank, Move.KingCastle), ("e" + rank, "c" + rank, Move.QueenCastle))
      val out      = buffer()
      val active   = pieces(state, color) & state.kings.value
      assertMoves(out, RawMoveGeneratorTestAccess.root(state, color, active, 6, out), expected ++ castles)

      val knightFrom = if color.isWhite then "b3" else "b6"
      val knightTo   = if color.isWhite then "a5" else "a4"
      val after = play(state.withActiveColor(color).withDicePool(List(2, 6, 6)), knightFrom, knightTo, PieceType.Knight)
      assert(MoveGenerator.generatePieceMoves(after, PieceType.King).exists(_.isCastling))
      val mine     = pieces(after, color)
      val enemy    = pieces(after, color.opponent)
      val childOut = buffer()
      val count    = RawMoveGeneratorTestAccess.continuation(
        mine,
        enemy,
        mine & after.kings.value,
        enemy & after.kings.value,
        after.enPassant.value,
        Piece(color, PieceType.King),
        childOut
      )
      assertMoves(childOut, count, expected)
    }

  for (name, fen, from, nextRank) <- List(
      ("white", "r1k5/1P6/8/8/8/8/8/4K3 w - - 0 1", "b7", "8"),
      ("black", "4k3/8/8/8/8/8/1p6/R1K5 b - - 0 1", "b2", "1")
    )
  do
    test(s"$name pawn promotion order preserves a single king-capture encoding") {
      val state    = parse(fen)
      val color    = state.activeColor
      val mine     = pieces(state, color)
      val enemy    = pieces(state, color.opponent)
      val expected = List(Move.QueenPromotion, Move.RookPromotion, Move.BishopPromotion, Move.KnightPromotion)
        .map(flag => (from, "b" + nextRank, flag)) ++
        List((from, "c" + nextRank, Move.Capture)) ++
        List(Move.QueenPromoCapture, Move.RookPromoCapture, Move.BishopPromoCapture, Move.KnightPromoCapture)
          .map(flag => (from, "a" + nextRank, flag))
      val out   = buffer()
      val count = RawMoveGeneratorTestAccess.continuation(
        mine,
        enemy,
        bits(List(from)),
        enemy & state.kings.value,
        0L,
        Piece(color, PieceType.Pawn),
        out
      )
      assertMoves(out, count, expected)
      val rootOut = buffer()
      assertMoves(rootOut, RawMoveGeneratorTestAccess.root(state, color, bits(List(from)), 1, rootOut), expected)
    }

  test("empty candidates write no output for either color and every valid die") {
    val state = parse(CastleFen)
    for color <- List(Color.White, Color.Black); die <- 1 to 6 do
      val mine  = pieces(state, color)
      val enemy = pieces(state, color.opponent)
      val out   = buffer()
      val count = RawMoveGeneratorTestAccess.continuation(
        mine,
        enemy,
        0L,
        enemy & state.kings.value,
        0L,
        Piece(color, PieceType(die)),
        out
      )
      assertMoves(out, count, Nil)
      val rootOut = buffer()
      assertMoves(rootOut, RawMoveGeneratorTestAccess.root(state, color, 0L, die, rootOut), Nil)
  }
