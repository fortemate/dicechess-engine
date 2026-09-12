// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import scala.concurrent.duration.*

import dicechess.engine.domain.*
import munit.FunSuite

class KcpMobilityFeaturesSpec extends FunSuite:

  override def munitTimeout: Duration = 3.minutes

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  private val Expected27Columns = KcpFeatures.columnNames ++ List(
    "own_moves_p",
    "own_moves_n",
    "own_moves_b",
    "own_moves_r",
    "own_moves_q",
    "own_moves_k",
    "opp_moves_p",
    "opp_moves_n",
    "opp_moves_b",
    "opp_moves_r",
    "opp_moves_q",
    "opp_moves_k",
    "own_pdi",
    "opp_pdi"
  )

  private val Expected31Columns = Expected27Columns ++ List(
    "own_passed_pawns",
    "opp_passed_pawns",
    "own_passed_max_rank",
    "opp_passed_max_rank"
  )

  test("schemaId and columnNames are pinned for both contracts"):
    assertEquals(KcpMobilityFeatures.schemaId, "kcp-mobility-27-v1")
    assertEquals(KcpMobilityFeatures.columnNames, Expected27Columns)
    assertEquals(KcpMobilityFeatures.columnNames.length, 27)

    assertEquals(KcpMobilityPawnsFeatures.schemaId, "kcp-mobility-pawns-31-v1")
    assertEquals(KcpMobilityPawnsFeatures.columnNames, Expected31Columns)
    assertEquals(KcpMobilityPawnsFeatures.columnNames.length, 31)

  test("first 13 columns of 27-schema are byte-identical to KcpFeatures.extract"):
    val fens = List(
      FenParser.InitialPosition,
      "4k3/8/8/8/8/8/8/4K3 w - - 0 1",
      "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
      "4k3/pp6/8/8/3Q4/8/PP6/R3K3 b - - 0 1"
    )
    for
      fen   <- fens
      color <- List(Color.White, Color.Black)
    do
      val state    = parse(fen)
      val kcp      = KcpFeatures.extract(state, color)
      val mobility = KcpMobilityFeatures.extract(state, color)
      assertEquals(mobility.length, 27)
      for i <- 0 until 13 do
        val kcpBits = java.lang.Float.floatToRawIntBits(kcp(i))
        val mobBits = java.lang.Float.floatToRawIntBits(mobility(i))
        assertEquals(mobBits, kcpBits, s"Divergence at column $i (${KcpFeatures.columnNames(i)}) for $color")

  test("first 27 columns of 31-schema are byte-identical to KcpMobilityFeatures.extract"):
    val fens = List(
      FenParser.InitialPosition,
      "4k3/8/8/8/8/8/8/4K3 w - - 0 1",
      "k7/4P3/8/8/8/8/8/4K3 w - - 0 1",
      "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"
    )
    for
      fen   <- fens
      color <- List(Color.White, Color.Black)
    do
      val state   = parse(fen)
      val mob27   = KcpMobilityFeatures.extract(state, color)
      val pawns31 = KcpMobilityPawnsFeatures.extract(state, color)
      assertEquals(pawns31.length, 31)
      for i <- 0 until 27 do
        val mobBits   = java.lang.Float.floatToRawIntBits(mob27(i))
        val pawnsBits = java.lang.Float.floatToRawIntBits(pawns31(i))
        assertEquals(pawnsBits, mobBits, s"Divergence at column $i (${KcpMobilityFeatures.columnNames(i)}) for $color")

  test("both schemas are mover-canonical on core benchmark and edge positions"):
    val positions = List(
      "initial"          -> parse(FenParser.InitialPosition),
      "bare kings"       -> parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1"),
      "empty board"      -> parse("8/8/8/8/8/8/8/8 w - - 0 1"),
      "full board"       -> parse("rnbqkbnr/pppppppp/nnnnnnnn/rrrrrrrr/RRRRRRRR/NNNNNNNN/PPPPPPPP/RNBQKBNR w - - 0 1"),
      "pinned piece"     -> parse("k3r3/8/8/8/8/8/4N3/4K3 w - - 0 1"),
      "blocked chain"    -> parse("4k3/8/8/p1p1p1p1/P1P1P1P1/8/8/4K3 w - - 0 1"),
      "promotion-passed" -> parse("k7/4P3/8/8/8/8/8/4K3 w - - 0 1"),
      "kiwipete"         -> parse("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"),
      "endgame"          -> parse("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
    )
    for (name, state) <- positions do
      val flipped = Symmetry.colorFlip(state)
      // Mover perspective (state.activeColor vs flipped.activeColor)
      assertEquals(
        KcpMobilityFeatures.extract(state, state.activeColor).toList,
        KcpMobilityFeatures.extract(flipped, flipped.activeColor).toList,
        s"27-schema mover-canonical check failed for $name"
      )
      assertEquals(
        KcpMobilityPawnsFeatures.extract(state, state.activeColor).toList,
        KcpMobilityPawnsFeatures.extract(flipped, flipped.activeColor).toList,
        s"31-schema mover-canonical check failed for $name"
      )
      // Explicit color perspective
      for color <- List(Color.White, Color.Black) do
        assertEquals(
          KcpMobilityFeatures.extract(state, color).toList,
          KcpMobilityFeatures.extract(flipped, color.opponent).toList,
          s"27-schema color perspective failed for $name with $color"
        )
        assertEquals(
          KcpMobilityPawnsFeatures.extract(state, color).toList,
          KcpMobilityPawnsFeatures.extract(flipped, color.opponent).toList,
          s"31-schema color perspective failed for $name with $color"
        )

  test("blocked pawn chain has zero own_moves_p and opp_moves_p"):
    val state = parse("4k3/8/8/p1p1p1p1/P1P1P1P1/8/8/4K3 w - - 0 1")
    val f27   = KcpMobilityFeatures.extract(state, Color.White)
    val f31   = KcpMobilityPawnsFeatures.extract(state, Color.White)
    val ownP  = KcpMobilityFeatures.columnNames.indexOf("own_moves_p")
    val oppP  = KcpMobilityFeatures.columnNames.indexOf("opp_moves_p")
    assertEquals(f27(ownP), 0f)
    assertEquals(f27(oppP), 0f)
    assertEquals(f31(ownP), 0f)
    assertEquals(f31(oppP), 0f)

  test("promotion-eligible passed pawn reflects in passed columns of 31-schema"):
    val state   = parse("k7/4P3/8/8/8/8/8/4K3 w - - 0 1")
    val f31     = KcpMobilityPawnsFeatures.extract(state, Color.White)
    val ownPass = KcpMobilityPawnsFeatures.columnNames.indexOf("own_passed_pawns")
    val oppPass = KcpMobilityPawnsFeatures.columnNames.indexOf("opp_passed_pawns")
    val ownMaxR = KcpMobilityPawnsFeatures.columnNames.indexOf("own_passed_max_rank")
    val oppMaxR = KcpMobilityPawnsFeatures.columnNames.indexOf("opp_passed_max_rank")

    assertEquals(f31(ownPass), 1f)
    assertEquals(f31(oppPass), 0f)
    assertEquals(f31(ownMaxR), 6f) // e7 is rank 7 -> 7 - 1 = 6
    assertEquals(f31(oppMaxR), 0f)

  test("PDI columns reflect duplicate versus last-piece captures in 27- and 31-schemas"):
    val before   = parse("r3k3/8/8/8/NN6/8/8/4K3 b - - 0 1 rr")
    val oneLeft  = before.makeMove(Move(Square('a', 8), Square('a', 4), Move.Capture))
    val noneLeft = oneLeft.makeMove(Move(Square('a', 4), Square('b', 4), Move.Capture))

    val ownPdiIdx = KcpMobilityFeatures.columnNames.indexOf("own_pdi")
    val oppPdiIdx = KcpMobilityFeatures.columnNames.indexOf("opp_pdi")

    // White has only Knights (and King)
    assertEquals(KcpMobilityFeatures.extract(before, Color.White)(ownPdiIdx), 0.2f)
    assertEquals(KcpMobilityFeatures.extract(oneLeft, Color.White)(ownPdiIdx), 0.2f)
    assertEquals(KcpMobilityFeatures.extract(noneLeft, Color.White)(ownPdiIdx), 0.0f)

    // Opponent perspective
    assertEquals(KcpMobilityFeatures.extract(before, Color.Black)(oppPdiIdx), 0.2f)
    assertEquals(KcpMobilityFeatures.extract(oneLeft, Color.Black)(oppPdiIdx), 0.2f)
    assertEquals(KcpMobilityFeatures.extract(noneLeft, Color.Black)(oppPdiIdx), 0.0f)

    // Same for 31-schema
    assertEquals(KcpMobilityPawnsFeatures.extract(before, Color.White)(ownPdiIdx), 0.2f)
    assertEquals(KcpMobilityPawnsFeatures.extract(noneLeft, Color.White)(ownPdiIdx), 0.0f)

  test("dice pool does not change the feature vector"):
    val state = parse(FenParser.InitialPosition)
    for dice <- List(Nil, List(1), List(2, 3), List(4, 5, 6)) do
      val rolled = state.withDicePool(dice)
      assertEquals(
        KcpMobilityFeatures.extract(rolled, Color.White).toList,
        KcpMobilityFeatures.extract(state, Color.White).toList
      )
      assertEquals(
        KcpMobilityPawnsFeatures.extract(rolled, Color.White).toList,
        KcpMobilityPawnsFeatures.extract(state, Color.White).toList
      )
