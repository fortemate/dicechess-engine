// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class KingCaptureLevel2ContractSuite extends FunSuite:

  private val cases = List(
    ("white king unblocks", "4k3/8/8/8/8/3nK3/4P3/4R3 w - - 0 1", List(1, 0, 7, 0)),
    ("black king unblocks", "4r3/4p3/3Nk3/8/8/8/8/4K3 b - - 0 1", List(7, 0, 1, 0)),
    ("white second-move promotion", "2k5/8/4P3/8/8/8/8/4K3 w - - 0 1", List(0, 0, 6, 0)),
    ("black second-move promotion", "4k3/8/8/8/8/4p3/8/2K5 b - - 0 1", List(6, 0, 0, 0))
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
