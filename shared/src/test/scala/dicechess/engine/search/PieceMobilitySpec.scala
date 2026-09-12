// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class PieceMobilitySpec extends FunSuite:

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  test("initial position move counts match known standard chess moves in dice order"):
    val state = parse(FenParser.InitialPosition)
    for color <- List(Color.White, Color.Black) do
      val counts = PieceMobility.counts(state, color)
      // 16 pawn moves (8 pawns x (1 step + 2 step)), 4 knight moves, 0 for sliding/king
      assertEquals(counts.toList, List(16, 4, 0, 0, 0, 0))
      assertEquals(PieceMobility.total(state, color), 20)
      assertEquals(PieceMobility.count(state, color, PieceType.Pawn), 16)
      assertEquals(PieceMobility.count(state, color, PieceType.Knight), 4)
      assertEquals(PieceMobility.count(state, color, PieceType.Bishop), 0)
      assertEquals(PieceMobility.count(state, color, PieceType.Rook), 0)
      assertEquals(PieceMobility.count(state, color, PieceType.Queen), 0)
      assertEquals(PieceMobility.count(state, color, PieceType.King), 0)

  test("bare kings have five moves on the edge and zero for other pieces"):
    val state = parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    for color <- List(Color.White, Color.Black) do
      val counts = PieceMobility.counts(state, color)
      assertEquals(counts.toList, List(0, 0, 0, 0, 0, 5))
      assertEquals(PieceMobility.total(state, color), 5)

  test("fully blocked pawn chain has zero pawn moves for both sides"):
    val state = parse("4k3/8/8/p1p1p1p1/P1P1P1P1/8/8/4K3 w - - 0 1")
    for color <- List(Color.White, Color.Black) do
      val counts = PieceMobility.counts(state, color)
      assertEquals(counts(0), 0)
      assertEquals(PieceMobility.count(state, color, PieceType.Pawn), 0)

  test("pinned piece still contributes its pseudo-legal moves"):
    val state = parse("k3r3/8/8/8/8/8/4N3/4K3 w - - 0 1")
    assertEquals(PieceMobility.count(state, Color.White, PieceType.Knight), 6)
    assertEquals(PieceMobility.counts(state, Color.White)(1), 6)

  test("sum(own_moves) - sum(opp_moves) strictly equals RichFeatures mobility_diff on diverse positions"):
    val fens = List(
      FenParser.InitialPosition,
      "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", // Kiwipete
      "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",                            // Endgame
      "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1",                   // Castling
      "k7/4P3/8/8/8/8/8/4K3 w - - 0 1",                                       // Promotion
      "4k3/8/8/pppppppp/PPPPPPPP/8/8/4K3 w - - 0 1",                          // Blocked pawns
      "4k3/pp6/8/8/3Q4/8/PP6/R3K3 b - - 0 1"
    )
    for
      fen   <- fens
      color <- List(Color.White, Color.Black)
    do
      val state        = parse(fen)
      val ownCounts    = PieceMobility.counts(state, color)
      val oppCounts    = PieceMobility.counts(state, color.opponent)
      val mobilityDiff = (ownCounts.sum - oppCounts.sum).toFloat
      val richFeatures = RichFeatures.extract(state, color)
      assertEquals(
        mobilityDiff,
        richFeatures(7),
        s"Divergence in mobility_diff for FEN: $fen, color: $color"
      )

  test("piece mobility is invariant to the dice pool"):
    val state = parse(FenParser.InitialPosition)
    for dice <- List(Nil, List(1), List(2, 3), List(4, 5, 6)) do
      val rolled = state.withDicePool(dice)
      assertEquals(
        PieceMobility.counts(rolled, Color.White).toList,
        PieceMobility.counts(state, Color.White).toList
      )

  test("extracting mobility preserves the input position"):
    val state      = parse("4k3/pp6/8/8/3Q4/8/PP6/R3K3 b - - 0 1").withDicePool(List(2, 3))
    val serialized = FenParser.serialize(state)
    val hash       = state.zobristHash
    PieceMobility.counts(state, Color.White)
    PieceMobility.counts(state, Color.Black)
    PieceMobility.total(state, Color.White)
    PieceMobility.count(state, Color.White, PieceType.Queen)
    assertEquals(FenParser.serialize(state), serialized)
    assertEquals(state.zobristHash, hash)
