// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class KingCaptureRootContractSuite extends FunSuite:

  private val cases = List(
    ("black root move solves the last roll", "8/8/8/8/b7/3k4/4pn2/r2K3q b - - 0 1", List(216, 0, 16, 0)),
    ("white root move solves the last roll", "R2k3Q/4PN2/3K4/B7/8/8/8/8 w - - 0 1", List(16, 0, 216, 0))
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
