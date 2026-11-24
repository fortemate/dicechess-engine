// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

import dicechess.engine.search.TurnGenerator
import munit.FunSuite

/** Regression coverage for the rules presets and recorded rolls in #420. */
class RulesConfigSuite extends FunSuite:
  test("the five supported configurations resolve to immutable revision-one presets"):
    val presets = List(
      ("classical", "standard", RulesConfig.Standard),
      ("chess960", "standard", RulesConfig.Chess960),
      ("classical", "opening-pawn", RulesConfig.OpeningPawn),
      ("chess960", "opening-pawn", RulesConfig.Chess960OpeningPawn),
      ("classical", "choice-2x2d6", RulesConfig.FortemateChoice)
    )
    presets.foreach { (layoutId, policyId, preset) =>
      assert(RulesConfig.parse("dicechesscom", layoutId, policyId).toOption.contains(preset))
      assert(RulesConfig.of("dicechesscom", layoutId, policyId) eq preset)
      assertEquals(preset.profileId, "dicechesscom")
      assertEquals(preset.layoutId, layoutId)
      assertEquals(preset.rollPolicyId, policyId)
      assertEquals(preset.isChess960, layoutId == "chess960")
      assertEquals(preset.revision, 1)
    }

  test("unknown identifiers and unsupported combinations are rejected"):
    val invalid = List(
      ("chesshero", "classical", "standard"),
      ("dicechesscom", "unknown", "standard"),
      ("dicechesscom", "classical", "unknown"),
      ("dicechesscom", "chess960", "choice-2x2d6")
    )
    invalid.foreach { (profile, layout, policy) =>
      assert(RulesConfig.parse(profile, layout, policy).isLeft)
      intercept[IllegalArgumentException](RulesConfig.of(profile, layout, policy))
    }

  private def pool(roll: Either[String, PreparedRoll]): List[Int] =
    roll.toOption.get match
      case prepared: PreparedRoll.Pool => prepared.dice
      case _: PreparedRoll.Offers      => fail("Expected a ready dice pool")

  private def offers(roll: Either[String, PreparedRoll]): PreparedRoll.Offers =
    roll.toOption.get match
      case prepared: PreparedRoll.Offers => prepared
      case _: PreparedRoll.Pool          => fail("Expected ordered offers")

  test("each player's first turn adds one pawn to their own two random faces"):
    List(RulesConfig.OpeningPawn, RulesConfig.Chess960OpeningPawn).foreach { config =>
      val whiteFirstDraw = List(1, 1)
      val blackFirstDraw = List(2, 1)
      assertEquals(pool(config.prepareRoll(whiteFirstDraw, firstTurnForPlayer = true)), List(1, 1, 1))
      assertEquals(pool(config.prepareRoll(blackFirstDraw, firstTurnForPlayer = true)), List(1, 2, 1))
      assertEquals(pool(config.prepareRoll(List(2, 3, 4), firstTurnForPlayer = false)), List(2, 3, 4))
      assertEquals(pool(config.prepareRoll(List(4, 5, 6), firstTurnForPlayer = false)), List(4, 5, 6))
      assertEquals(pool(config.prepareRoll(blackFirstDraw, firstTurnForPlayer = true)), List(1, 2, 1))
    }

  test("standard rolling ignores opening entitlement and preserves all three supplied faces"):
    List(RulesConfig.Standard, RulesConfig.Chess960).foreach { config =>
      val faces = List(6, 2, 5)
      assertEquals(pool(config.prepareRoll(faces, firstTurnForPlayer = true)), faces)
      assertEquals(pool(config.prepareRoll(faces, firstTurnForPlayer = false)), faces)
    }

  test("choice resumes from recorded offers and commits only the whole selected pair"):
    val config = RulesConfig.FortemateChoice
    val rolled = offers(config.prepareRoll(List(5, 1, 3, 2), firstTurnForPlayer = true))
    assertEquals(rolled.first, List(5, 1))
    assertEquals(rolled.second, List(3, 2))
    val first  = rolled.select(0).toOption.get
    val second = rolled.select(1).toOption.get
    assertEquals(first.dice, List(5, 1))
    assertEquals(second.dice, List(3, 2))
    assertEquals(second.index, 1)
    val resumed = offers(config.prepareRoll(second.offers.first ++ second.offers.second, firstTurnForPlayer = true))
    assertEquals(resumed.select(second.index).toOption.get.dice, second.dice)

  test("a player can commit a pair that forces a pass even when the other pair is playable"):
    val rolled = offers(RulesConfig.FortemateChoice.prepareRoll(List(3, 4, 1, 2), firstTurnForPlayer = true))
    val board  = FenParser.parse(FenParser.InitialPosition).toOption.get
    val pass   = rolled.select(0).toOption.get
    val play   = rolled.select(1).toOption.get
    assert(TurnGenerator.generateAllLegalTurnPaths(board.withDicePool(pass.dice)).isEmpty)
    assert(TurnGenerator.generateAllLegalTurnPaths(board.withDicePool(play.dice)).nonEmpty)

  test("choice preserves the selected index even when offers are equal"):
    val rolled = offers(RulesConfig.FortemateChoice.prepareRoll(List(4, 6, 4, 6), firstTurnForPlayer = false))
    val first  = rolled.select(0).toOption.get
    val second = rolled.select(1).toOption.get
    assertEquals(first.dice, second.dice)
    assertEquals(first.index, 0)
    assertEquals(second.index, 1)
    assert(rolled.select(-1).isLeft)
    assert(rolled.select(2).isLeft)

  test("roll preparation validates dice count and faces"):
    assert(RulesConfig.Standard.prepareRoll(List(1, 2), firstTurnForPlayer = true).isLeft)
    assert(RulesConfig.Standard.prepareRoll(List(1, 2, 7), firstTurnForPlayer = false).isLeft)
    assert(RulesConfig.OpeningPawn.prepareRoll(List(0, 1), firstTurnForPlayer = true).isLeft)
    assert(RulesConfig.OpeningPawn.prepareRoll(List(1, 2, 3), firstTurnForPlayer = true).isLeft)
    assert(RulesConfig.OpeningPawn.prepareRoll(List(1, 2), firstTurnForPlayer = false).isLeft)
    assert(RulesConfig.FortemateChoice.prepareRoll(List(1, 2, 3), firstTurnForPlayer = false).isLeft)
