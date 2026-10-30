// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.concurrent.duration.*

/** Exhaustive probability and legal-turn parity regressions for #387. The oracle visits ordered rolls directly,
  * independently of the canonical distribution used to build the production table.
  */
class ActiveDicePartitionerSuite extends FunSuite:

  // Exhaustive path comparisons also run under the slower Wasm test runner; this is not a speed assertion.
  override val munitTimeout: Duration = 120.seconds

  private val orderedRolls = (for a <- 1 to 6; b <- 1 to 6; c <- 1 to 6 yield List(a, b, c)).toList

  private def project(dice: List[Int], mask: Int, steps: Int): List[Int] =
    val canPromote = steps > 0 && dice.count(_ == 1) >= steps
    dice.sorted.filter(d => (mask & (1 << (d - 1))) != 0 || (canPromote && d >= 2 && d <= 5))

  test("all 192 partitions match direct enumeration of the 216 ordered outcomes"):
    for index <- 0 until 192 do
      val mask     = index % 64
      val steps    = List(0, 2, 1)(index / 64)
      val expected = orderedRolls.groupMapReduce(project(_, mask, steps))(_ => 1)(_ + _)
      val entries  = ActiveDicePartitioner.table(index).toList
      val actual   = entries.map(e => e.roll.toList -> e.weight)
      assertEquals(actual.toMap, expected, s"index=$index")
      assertEquals(actual.size, expected.size, "no duplicate representatives")
      assertEquals(entries.map(_.weight).sum, 216)
      assert(entries.forall(e => e.weight > 0 && e.weight <= 216))
      assertEquals(
        actual,
        actual.sortBy { (dice, weight) =>
          (-weight, -dice.length, dice.headOption.getOrElse(0), dice.lift(1).getOrElse(0), dice.lift(2).getOrElse(0))
        }
      )

  test("pure presence partitions have the exact combinatorial number of classes"):
    val counts = List(1, 4, 10, 20, 35, 56, 56)
    for mask <- 0 until 64 do assertEquals(ActiveDicePartitioner.table(mask).length, counts(Integer.bitCount(mask)))

  test("compact rolls round-trip every sorted pool of zero through three dice and replace only dice"):
    val base  = parse("r3k2r/8/8/8/3Pp3/8/8/R3K2R b KQkq d3 17 42 PNK")
    val pools = (List(Nil) ++ orderedRolls.flatMap(d => List(d.take(1).sorted, d.take(2).sorted, d.sorted))).distinct
    for dice <- pools do
      val roll = CompactRoll.fromSortedList(dice)
      assertEquals(roll.toList, dice)
      assertEquals(roll.arity, dice.length)
      assertEquals(roll.isEmpty, dice.isEmpty)
      assertEquals(roll.dieMask, dice.foldLeft(0)((m, d) => m | (1 << (d - 1))))
      val rolled   = roll.applyToState(base)
      val expected = base.withDicePool(dice)
      assertEquals(FenParser.serialize(rolled), FenParser.serialize(expected))
      assertEquals(rolled.zobristHash, expected.zobristHash)
    assertEquals(CompactRoll.Empty.toList, Nil)

  test("compact rolls reject unsorted, out-of-range and oversized pools"):
    List(List(2, 1), List(0), List(7), List(-1), List(1, 1, 1, 1)).foreach { dice =>
      intercept[IllegalArgumentException](CompactRoll.fromSortedList(dice))
    }

  test("index covers both colors, all presence masks, promotion ranks and the sign bit"):
    for mask <- 0 until 64; color <- List(Color.White, Color.Black); steps <- 0 to 2 do
      val state        = maskPosition(mask, color, steps)
      val expectedMode = if (mask & 1) == 0 || (mask & 30) == 30 then 0 else List(0, 2, 1)(steps)
      assertEquals(ActiveDicePartitioner.partitionIndex(state), mask + 64 * expectedMode)
      assertEquals(
        ActiveDicePartitioner
          .partitionsFor(state.withDicePool(List(1, 2, 3)))
          .map(e => (e.roll.toList, e.weight))
          .toList,
        ActiveDicePartitioner.table(mask + 64 * expectedMode).map(e => (e.roll.toList, e.weight)).toList
      )
    val signBit = parse("4k2Q/8/8/8/8/8/P7/K7 w - - 0 1")
    assertEquals(ActiveDicePartitioner.partitionIndex(signBit), 49)
    assertEquals(ActiveDicePartitioner.partitionIndex(signBit.withActiveColor(Color.Black)), 32)

  test("one-step promotion takes precedence when both promotion ranks contain pawns"):
    assertEquals(ActiveDicePartitioner.partitionIndex(parse("k7/P7/1P6/8/8/8/8/4K3 w - - 0 1")), 161)
    assertEquals(ActiveDicePartitioner.partitionIndex(parse("4k3/8/8/8/8/1p6/p7/K7 b - - 0 1")), 161)

  private val fixtures = List(
    "initial"                             -> FenParser.InitialPosition,
    "white castles"                       -> "4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1",
    "black castles"                       -> "r3k2r/8/8/8/8/8/8/4K3 b kq - 0 1",
    "white en passant"                    -> "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
    "black en passant"                    -> "4k3/8/8/8/3Pp3/8/8/4K3 b - d3 0 1",
    "white one-step promotion"            -> "k7/4P3/8/8/8/8/8/4K3 w - - 0 1",
    "white two-step promotion"            -> "k7/8/4P3/8/8/8/8/4K3 w - - 0 1",
    "black one-step promotion"            -> "4k3/8/8/8/8/8/4p3/K7 b - - 0 1",
    "black two-step promotion"            -> "4k3/8/8/8/8/4p3/8/K7 b - - 0 1",
    "blocked promotion"                   -> "k3r3/4P3/8/8/8/8/8/4K3 w - - 0 1",
    "capture promotion"                   -> "k4r2/4P3/8/8/8/8/8/4K3 w - - 0 1",
    "three pawn moves to promotion"       -> "k7/8/8/4P3/8/8/8/4K3 w - - 0 1",
    "lone king passes on absent dice"     -> "4k3/8/8/8/8/8/8/4K3 w - - 0 1",
    "blocked pawn passes on present dice" -> "4k3/8/8/8/8/p7/P7/4K3 w - - 0 1",
    "early king capture"                  -> "8/8/8/8/8/4k3/4R3/4K3 w - - 0 1"
  )

  fixtures.foreach { (name, fen) =>
    test(s"grouping preserves every legal path and full-turn outcome: $name"):
      assertParity(parse(fen))
  }

  // Separate cases bound work per test under coverage instrumentation and identify the failing material mask.
  for subset <- 0 until 32; color <- List(Color.White, Color.Black) do
    test(s"legal-turn parity for material subset $subset, color $color"):
      assertParity(maskPosition(subset | 32, color, subset % 3))

  test("promotion dice remain available for underpromotion and subsequent king capture"):
    val state = parse("k7/4P3/8/8/8/8/8/4K3 w - - 0 1")
    val pool  = List(1, 2, 2)
    assert(ActiveDicePartitioner.partitionsFor(state).exists(_.roll.toList == pool))
    val rolled = state.withDicePool(pool)
    val paths  = TurnGenerator.generateAllLegalTurnPaths(rolled)
    assert(paths.exists(p => p.length == 3 && p.foldLeft(rolled)((s, m) => s.makeMove(m)).blackPieces.isEmpty))

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  // Build masks independently from bitboards. Pawn placement controls promotion distance; other squares include h8.
  private def maskPosition(mask: Int, color: Color, steps: Int): GameState =
    val pawnRank = if color.isWhite then List(2, 7, 6)(steps) else List(7, 2, 3)(steps)
    val squares  = List((pawnRank - 1) * 8, 18, 29, 38, 63, 4)
    val chars    = if color.isWhite then "PNBRQK" else "pnbrqk"
    val board    = Array.fill(64)(' ')
    for i <- 0 until 6 if (mask & (1 << i)) != 0 do board(squares(i)) = chars(i)
    // Opponent king is disjoint from every material square.
    board(58) = if color.isWhite then 'k' else 'K'
    val rows = (7 to 0 by -1).map { rank =>
      val out   = new StringBuilder
      var empty = 0
      for file <- 0 until 8 do
        val piece = board(rank * 8 + file)
        if piece == ' ' then empty += 1
        else
          if empty > 0 then out.append(empty)
          empty = 0
          out.append(piece)
      if empty > 0 then out.append(empty)
      out.toString
    }
    parse(s"${rows.mkString("/")} ${if color.isWhite then "w" else "b"} - - 0 1")

  private def assertParity(state: GameState): Unit =
    val index           = ActiveDicePartitioner.partitionIndex(state)
    val mask            = index % 64
    val steps           = List(0, 2, 1)(index / 64)
    val classes         = ActiveDicePartitioner.partitionsFor(state)
    val representatives = classes.iterator.map { e =>
      val rolled = e.roll.applyToState(state)
      val paths  = TurnGenerator.generateAllLegalTurnPaths(rolled)
      e.roll.toList -> (paths.map(_.map(_.toInt)).toSet, outcomes(rolled, paths))
    }.toMap
    var referenceMass = Map.empty[Set[String], Int]
    // Independent 56-roll distribution obtained from all 216 ordered rolls, not DiceRolls.weighted.
    orderedRolls.groupMapReduce(_.sorted)(_ => 1)(_ + _).foreach { (dice, weight) =>
      val full                          = state.withDicePool(dice)
      val retained                      = project(dice, mask, steps)
      val paths                         = TurnGenerator.generateAllLegalTurnPaths(full)
      val (reducedPaths, reducedLeaves) = representatives(retained)
      assertEquals(reducedPaths, paths.map(_.map(_.toInt)).toSet, s"${FenParser.serialize(state)} roll=$dice")
      val leaves = outcomes(full, paths)
      assertEquals(reducedLeaves, leaves, s"${FenParser.serialize(state)} roll=$dice")
      referenceMass = referenceMass.updated(leaves, referenceMass.getOrElse(leaves, 0) + weight)
    }
    val groupedMass = classes.iterator.toList.groupMapReduce(e => representatives(e.roll.toList)._2)(_.weight)(_ + _)
    assertEquals(groupedMass, referenceMass, "the full distribution over legal successor sets is unchanged")

  private def outcomes(rolled: GameState, paths: List[List[Move]]): Set[String] =
    if paths.isEmpty then Set(FenParser.serialize(rolled.endTurn()))
    else paths.map(p => FenParser.serialize(p.foldLeft(rolled)((s, m) => s.makeMove(m)).endTurn())).toSet
