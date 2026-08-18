package dicechess.engine.search

import munit.FunSuite

/** Pins the 3d6 chance-node distribution: 56 sorted multisets whose weights partition the 216 ordered rolls. These
  * invariants are what [[KingCaptureProbability]] and any expectimax chance node rely on for a correct expectation.
  */
class DiceRollsSpec extends FunSuite:

  test("there are exactly 56 distinct multisets"):
    assertEquals(DiceRolls.weighted.length, 56)

  test("weights sum to the 216 ordered rolls"):
    val total = DiceRolls.weighted.foldLeft(0)((acc, entry) => acc + entry._2)
    assertEquals(total, DiceRolls.totalOrderedRolls)
    assertEquals(total, 216)

  test("every multiset is three dice in 1..6, sorted non-decreasing"):
    DiceRolls.weighted.foreach: (multiset, _) =>
      assertEquals(multiset.size, 3, s"$multiset is not three dice")
      assert(multiset.forall(d => d >= 1 && d <= 6), s"$multiset has an out-of-range die")
      assertEquals(multiset, multiset.sorted, s"$multiset is not sorted")

  test("weight matches the permutation count of the multiset (1 / 3 / 6 by distinct pips)"):
    DiceRolls.weighted.foreach: (multiset, weight) =>
      val expected = multiset.distinct.size match
        case 1 => 1
        case 2 => 3
        case _ => 6
      assertEquals(weight, expected, s"$multiset")

  test("no multiset appears twice"):
    val multisets = DiceRolls.weighted.map(_._1).toList
    assertEquals(multisets.distinct.size, multisets.size)

  test("weight buckets match the AAA/AAB/ABC partition"):
    val byWeight = DiceRolls.weighted.toList.groupBy(_._2).view.mapValues(_.size).toMap
    assertEquals(byWeight.get(1), Some(6), "AAA multisets")
    assertEquals(byWeight.get(3), Some(30), "AAB/ABB multisets")
    assertEquals(byWeight.get(6), Some(20), "ABC multisets")
