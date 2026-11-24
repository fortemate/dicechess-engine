// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.RulesConfig
import munit.FunSuite

/** Complete paths use the configured layout's notation (#420). */
class ConfiguredTurnsSpec extends FunSuite:
  test("each layout accepts only its own castle coordinates"):
    val dfen     = "4k3/8/8/8/8/8/8/4K2R w K - 0 1 KR"
    val result   = Right("4k3/8/8/8/8/8/8/5RK1 b - - 1 1")
    val standard = RulesConfig.Standard
    val chess960 = RulesConfig.Chess960
    assertEquals(ConfiguredTurns.applyTurn(dfen, List("e1g1"), standard), result)
    assertEquals(ConfiguredTurns.applyTurn(dfen, List("e1h1"), chess960), result)
    assert(ConfiguredTurns.applyTurn(dfen, List("e1h1"), standard).isLeft)
    assert(ConfiguredTurns.applyTurn(dfen, List("e1g1"), chess960).isLeft)

  test("classical application never reinterprets classical or explicit rights as variant castling"):
    val variant = "4k3/8/8/8/8/8/8/R3KR1R w FQ - 0 1 KR"
    assert(ConfiguredTurns.applyTurn(variant, List("e1f1"), RulesConfig.Standard).isLeft)
    assert(ConfiguredTurns.applyTurn(variant, List("e1f1"), RulesConfig.Chess960).isRight)
    val shorthand = "4k3/8/8/8/8/8/8/5KR1 w K - 0 1 KR"
    assert(ConfiguredTurns.applyTurn(shorthand, List("f1g1"), RulesConfig.Standard).isLeft)
    assert(ConfiguredTurns.applyTurn(shorthand, List("f1g1"), RulesConfig.Chess960).isRight)

  test("a classical turn must be complete, rolled, and played before the game ends"):
    val dfen = "4k3/8/8/8/8/8/P7/4K3 w - - 0 1"
    assertEquals(
      ConfiguredTurns.applyTurn(dfen + " PP", List("a2a4", "a4a5"), RulesConfig.Standard),
      Right("4k3/8/8/P7/8/8/8/4K3 b - - 0 1")
    )
    assert(ConfiguredTurns.applyTurn(dfen + " PP", List("a2a4"), RulesConfig.Standard).isLeft)
    assert(ConfiguredTurns.applyTurn(dfen + " PP", Nil, RulesConfig.Standard).isLeft)
    assert(ConfiguredTurns.applyTurn(dfen + " P", List("A2a4"), RulesConfig.Standard).isLeft)
    assert(ConfiguredTurns.applyTurn(dfen, Nil, RulesConfig.Standard).isLeft)
    val ended = "8/8/8/8/8/8/P7/4K3 w - - 0 1 P"
    assert(ConfiguredTurns.applyTurn(ended, List("a2a4"), RulesConfig.Standard).isLeft)

  test("a classical pass advances the turn but a king capture leaves the winner active"):
    val standard = RulesConfig.Standard
    assertEquals(
      ConfiguredTurns.applyTurn("4k3/8/8/3p4/P7/8/8/4K3 b - a3d6 7 12 n", Nil, standard),
      Right("4k3/8/8/3p4/P7/8/8/4K3 w - d6 7 13")
    )
    val lastMove = Int.MaxValue
    val dfen     = s"4k2r/8/8/8/8/8/8/4K2R b Kk - 7 $lastMove kr"
    assertEquals(
      ConfiguredTurns.applyTurn(dfen, List("h8h1", "e8f8"), standard),
      Left("The next full-move number exceeds the DFEN range")
    )
    assertEquals(
      ConfiguredTurns.applyTurn(dfen, List("h8h1", "h1e1"), standard),
      Left("Expected a complete legal turn")
    )
    val capture = s"4k3/8/8/8/8/8/4r3/R3K2R b KQ - 7 $lastMove kr"
    assertEquals(
      ConfiguredTurns.applyTurn(capture, List("e2e1"), standard),
      Right(s"4k3/8/8/8/8/8/8/R3r2R b - - 0 $lastMove")
    )
