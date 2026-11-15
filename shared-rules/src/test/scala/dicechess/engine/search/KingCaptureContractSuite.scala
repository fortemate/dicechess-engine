// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class KingCaptureContractSuite extends FunSuite:

  private val corpus = List(
    (FenParser.InitialPosition, List(0, 0, 0, 0)),
    (
      "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
      List(9, 35, 4, 52)
    ),
    ("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", List(16, 0, 32, 0)),
    ("k7/4P3/8/8/8/8/8/4K3 w - - 0 1", List(0, 0, 60, 0)),
    ("4k3/8/8/3pP3/8/8/8/4R1K1 w - d6 0 1", List(0, 0, 32, 0))
  )

  private def parse(fen: String): GameState =
    FenParser.parse(fen).fold(err => fail(s"Invalid contract FEN: $err"), identity)

  private def probabilities(state: GameState): List[Double] = List(
    KingCaptureProbability.kingCaptureProbability(state, Color.White),
    KingCaptureProbability.queenCaptureProbability(state, Color.White),
    KingCaptureProbability.kingCaptureProbability(state, Color.Black),
    KingCaptureProbability.queenCaptureProbability(state, Color.Black)
  )

  test("next-turn probabilities ignore the current active side and every current dice multiset") {
    val dicePools = Nil :: DiceRolls.weighted.toList.map(_._1)
    for
      (fen, counts) <- corpus
      active        <- List(Color.White, Color.Black)
      dice          <- dicePools
    do
      val state = parse(fen).withActiveColor(active).withDicePool(dice)
      assertEquals(probabilities(state), counts.map(_.toDouble / 216), s"$fen active=$active dice=$dice")
  }

  test("interleaved king and queen queries leave the caller mailbox unchanged and are repeatable") {
    val states = corpus.map { (fen, counts) =>
      val state = parse(fen)
      (state, state.mailbox.toArray.toList, counts.map(_.toDouble / 216))
    }
    for iteration <- 1 to 3 do
      for (state, mailbox, expected) <- states.reverse do
        assertEquals(probabilities(state), expected, s"interleaved pass $iteration")
        assertEquals(state.mailbox.toArray.toList, mailbox, s"caller mailbox changed on pass $iteration")
  }

  test("each directly attacking piece type has exactly 91 winning ordered rolls") {
    val fens = List(
      "8/8/8/3p4/4K3/8/8/8 b - - 0 1",
      "8/5n2/8/4K3/8/8/8/8 b - - 0 1",
      "8/8/8/4K3/8/8/7b/8 b - - 0 1",
      "4r3/8/8/4K3/8/8/8/8 b - - 0 1",
      "4q3/8/8/4K3/8/8/8/8 b - - 0 1",
      "8/8/8/3k4/4K3/8/8/8 b - - 0 1"
    )
    for fen <- fens do
      assertEquals(KingCaptureProbability.kingCaptureProbability(parse(fen), Color.White), 91.0 / 216, fen)
  }
