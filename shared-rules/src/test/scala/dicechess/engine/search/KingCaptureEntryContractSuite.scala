// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class KingCaptureEntryContractSuite extends FunSuite:

  private val cases = List(
    ("only White king", "8/8/8/8/8/8/8/4K3 w - - 0 1", List(0, 0, 0, 0)),
    ("only Black king", "4k3/8/8/8/8/8/8/8 b - - 0 1", List(0, 0, 0, 0)),
    ("only White queen", "8/8/8/8/8/8/8/4Q3 w - - 0 1", List(0, 0, 0, 0)),
    ("only Black queen", "4q3/8/8/8/8/8/8/8 b - - 0 1", List(0, 0, 0, 0)),
    ("all Black types attack directly", "8/8/8/8/b7/8/3kpn2/r2K3q b - - 0 1", List(216, 0, 91, 0)),
    ("all White types attack directly", "R2k3Q/3KPN2/8/B7/8/8/8/8 w - - 0 1", List(91, 0, 216, 0)),
    ("Black queen route solves the last roll", "8/8/8/8/b7/8/3kpn1q/r2K4 b - - 0 1", List(216, 0, 91, 0)),
    ("White queen route solves the last roll", "R2k4/3KPN1Q/8/B7/8/8/8/8 w - - 0 1", List(91, 0, 216, 0)),
    ("three King dice are needed", "4k3/8/8/4K3/8/8/8/8 w - - 0 1", List(1, 0, 1, 0)),
    ("no roll reaches the target", "4k3/8/8/8/4K3/8/8/8 b - - 0 1", List(0, 0, 0, 0))
  )

  for (name, fen, counts) <- cases do
    test(name) {
      val state  = FenParser.parse(fen).fold(fail(_), identity)
      val actual = List(
        KingCaptureProbability.kingCaptureProbability(state, Color.White),
        KingCaptureProbability.queenCaptureProbability(state, Color.White),
        KingCaptureProbability.kingCaptureProbability(state, Color.Black),
        KingCaptureProbability.queenCaptureProbability(state, Color.Black)
      )
      assertEquals(actual, counts.map(_.toDouble / 216), fen)
    }

  test("direct fast-path fixtures have all six attacking types") {
    val fixtures = List(
      ("8/8/8/8/b7/8/3kpn2/r2K3q b - - 0 1", Square('d', 1), Color.Black),
      ("R2k3Q/3KPN2/8/B7/8/8/8/8 w - - 0 1", Square('d', 8), Color.White)
    )
    for (fen, target, attacker) <- fixtures do
      val state = FenParser.parse(fen).fold(fail(_), identity)
      assertEquals(AttackerTypes.mask(state, target, attacker), 0x3f, fen)
  }
