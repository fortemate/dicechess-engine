package dicechess.engine.search

import munit.FunSuite

class TimeManagerSuite extends FunSuite:

  private val empiricalManager = TimeManager(TimePolicies.EmpiricalV1)
  private val legacyManager    = TimeManager(TimePolicies.LegacyLinear)

  // ---- Exact, hand-computed budgets (golden table). Pure math, no wall-clock. ----
  // Columns: label, clock, expected target, expected hard cap.
  private val cases: List[(String, ClockState, Long, Long)] = List(
    // Sudden death 1+0, move 1: reserve 3000, spendable 57000, mtg 29 -> 57000/29 = 1965; cap 12000.
    ("sudden-death 1+0 @ move 1", ClockState(60000, 0, 1), 1965L, 12000L),
    // Fischer 10+10, move 1: reserve 30000, spendable 570000, mtg 29 -> 10000 + 19655 = 29655; cap 120000.
    ("fischer 10+10 @ move 1", ClockState(600000, 10000, 1), 29655L, 120000L),
    // Sudden death late: reserve 300, spendable 3700, mtg floored at 12 -> 3700/12 = 308; cap 800.
    ("sudden-death late @ move 40", ClockState(4000, 0, 40), 308L, 800L),
    // Panic with a big increment: capped to 400 by hardCap, then clamped to PanicBudgetMs 200.
    ("panic with increment", ClockState(2000, 10000, 40), 200L, 400L),
    // Empty clock: everything floors to MinThinkMs.
    ("empty clock", ClockState(0, 0, 1), 20L, 20L),
    // Explicit movesToGo overrides the taper: 57000/10 = 5700.
    ("explicit movesToGo=10", ClockState(60000, 0, 1, Some(10)), 5700L, 12000L),
    // movesToGo=0 must not divide by zero: clamped to 1, so target hits the hardCap 12000.
    ("movesToGo=0 is division-safe", ClockState(60000, 0, 1, Some(0)), 12000L, 12000L)
  )

  cases.foreach { case (label, clock, expectedTarget, expectedCap) =>
    test(s"legacy budget: $label") {
      val b = legacyManager.budget(clock)
      assertEquals(b.targetMs, expectedTarget, s"target for $clock")
      assertEquals(b.hardCapMs, expectedCap, s"hardCap for $clock")
    }
  }

  test("the compatibility facade uses empirical-v1 while legacy remains selectable") {
    assertEquals(TimeManager.default.policy.id, "empirical-v1")
    assertEquals(TimePolicies.default.id, "empirical-v1")
    assertEquals(TimePolicies.available.map(_.id), List("empirical-v1", "legacy-linear-v1"))
    assertEquals(TimePolicies.get("EMPIRICAL-V1").map(_.id), Some("empirical-v1"))
    assertEquals(TimePolicies.get("LEGACY-LINEAR-V1").map(_.id), Some("legacy-linear-v1"))
    assertEquals(TimePolicies.get("unknown"), None)
  }

  test("empirical-v1 reproduces measured knots and interpolates between them") {
    val knots = List(
      1  -> 7.517,
      3  -> 6.017,
      5  -> 5.471,
      8  -> 5.176,
      10 -> 5.136,
      15 -> 5.431,
      20 -> 6.185,
      30 -> 8.830,
      40 -> 12.050
    )

    knots.foreach { case (moveNumber, estimate) =>
      assertEqualsDouble(
        empiricalManager.movesToGo(ClockState(60000, 0, moveNumber)),
        estimate,
        0.000001
      )
    }
    assertEqualsDouble(empiricalManager.movesToGo(ClockState(60000, 0, 4)), 5.744, 0.000001)
  }

  test("empirical-v1 clamps outside the measured range and preserves the long-game tail") {
    val beforeFirst = empiricalManager.movesToGo(ClockState(60000, 0, -10))
    val turn10      = empiricalManager.movesToGo(ClockState(60000, 0, 10))
    val turn20      = empiricalManager.movesToGo(ClockState(60000, 0, 20))
    val turn40      = empiricalManager.movesToGo(ClockState(60000, 0, 40))
    val afterLast   = empiricalManager.movesToGo(ClockState(60000, 0, 100))

    assertEqualsDouble(beforeFirst, 7.517, 0.000001)
    assert(turn20 > turn10)
    assert(turn40 > turn20)
    assertEqualsDouble(afterLast, 12.050, 0.000001)
  }

  test("empirical-v1 changes normal allocation while retaining the shared safety envelope") {
    assertEquals(empiricalManager.budget(ClockState(60000, 0, 1)), TimeBudget(7582L, 12000L))
    assertEquals(empiricalManager.budget(ClockState(600000, 10000, 1)), TimeBudget(85828L, 120000L))
    assertEquals(empiricalManager.budget(ClockState(4000, 0, 40)), TimeBudget(307L, 800L))
    assertEquals(
      empiricalManager.budget(ClockState(2000, 10000, 40)),
      TimeBudget(TimeManager.PanicBudgetMs, 400L)
    )
    assertEquals(empiricalManager.budget(ClockState(60000, 0, 1, Some(10))).targetMs, 5700L)
  }

  test("an explicit policy changes allocation without bypassing the shared safety envelope") {
    val shortGame = new TimePolicy:
      override val id: String                                    = "test-short-game"
      override def estimateMovesToGo(_clock: ClockState): Double = 5.0

    val manager = TimeManager(shortGame)
    assertEqualsDouble(manager.movesToGo(ClockState(60000, 0, 1)), 5.0, 0.0)
    assertEquals(manager.budget(ClockState(60000, 0, 1)), TimeBudget(11400L, 12000L))
    assertEquals(manager.budget(ClockState(2000, 10000, 1)).targetMs, TimeManager.PanicBudgetMs)
  }

  test("fractional policy estimates are not rounded before budget allocation") {
    val interpolated = new TimePolicy:
      override val id: String                                    = "test-interpolated"
      override def estimateMovesToGo(_clock: ClockState): Double = 10.5

    val manager = TimeManager(interpolated)
    assertEqualsDouble(manager.movesToGo(ClockState(60000, 0, 1)), 10.5, 0.0)
    assertEquals(manager.budget(ClockState(60000, 0, 1)).targetMs, 5428L)
  }

  test("an explicit moves-to-go value overrides the configured policy and remains division-safe") {
    val unusableEstimate = new TimePolicy:
      override val id: String                                    = "test-unusable-estimate"
      override def estimateMovesToGo(_clock: ClockState): Double = -100.0

    val manager = TimeManager(unusableEstimate)
    assertEqualsDouble(manager.movesToGo(ClockState(60000, 0, 1, Some(7))), 7.0, 0.0)
    assertEqualsDouble(manager.movesToGo(ClockState(60000, 0, 1, Some(0))), 1.0, 0.0)
    assertEqualsDouble(manager.movesToGo(ClockState(60000, 0, 1)), 1.0, 0.0)
  }

  test("a non-finite policy estimate is clamped to a division-safe value") {
    val invalidEstimate = new TimePolicy:
      override val id: String                                    = "test-invalid-estimate"
      override def estimateMovesToGo(_clock: ClockState): Double = Double.NaN

    assertEqualsDouble(TimeManager(invalidEstimate).movesToGo(ClockState(60000, 0, 1)), 1.0, 0.0)
  }

  test("budgetMs subtracts the overhead buffer and floors at MinThinkMs") {
    // target 1965, buffer 150 -> 1815.
    assertEquals(legacyManager.budgetMs(ClockState(60000, 0, 1), 150L), 1815L)
    // target 29655, buffer 150 -> 29505.
    assertEquals(legacyManager.budgetMs(ClockState(600000, 10000, 1), 150L), 29505L)
    // target 20, buffer 150 -> floored to MinThinkMs (20), never negative.
    assertEquals(legacyManager.budgetMs(ClockState(0, 0, 1), 150L), TimeManager.MinThinkMs)

    assertEquals(TimeManager.budgetMs(ClockState(60000, 0, 1), 150L), 7432L)
    assertEquals(TimeManager.budgetMs(ClockState(600000, 10000, 1), 150L), 85678L)
  }

  test("legacy movesToGo tapers with move number and floors at MinMovesToGo") {
    assertEqualsDouble(legacyManager.movesToGo(ClockState(60000, 0, 1)), 29.0, 0.0)
    assertEqualsDouble(
      legacyManager.movesToGo(ClockState(60000, 0, 25)),
      TimeManager.MinMovesToGo.toDouble,
      0.0
    )
    assertEqualsDouble(
      legacyManager.movesToGo(ClockState(60000, 0, 100)),
      TimeManager.MinMovesToGo.toDouble,
      0.0
    )
    assertEqualsDouble(legacyManager.movesToGo(ClockState(60000, 0, 1, Some(7))), 7.0, 0.0)
    assertEqualsDouble(legacyManager.movesToGo(ClockState(60000, 0, 1, Some(0))), 1.0, 0.0)
  }

  // ---- Invariants (properties that must hold for every reasonable clock) ----

  private val sampleClocks: List[ClockState] =
    for
      remaining <- List(0L, 500L, 2000L, 5000L, 60000L, 180000L, 600000L)
      increment <- List(0L, 2000L, 10000L)
      move      <- List(1, 10, 30, 80)
    yield ClockState(remaining, increment, move)

  test("invariant: target is always within [MinThinkMs, hardCap]") {
    sampleClocks.foreach { c =>
      val b = TimeManager.budget(c)
      assert(b.targetMs >= TimeManager.MinThinkMs, s"target ${b.targetMs} below MinThink for $c")
      assert(b.targetMs <= b.hardCapMs, s"target ${b.targetMs} exceeds hardCap ${b.hardCapMs} for $c")
    }
  }

  test("invariant: never aims to spend more than the clock holds (no self-flag) above the panic floor") {
    // Once remaining comfortably exceeds MinThinkMs, the hard cap (a fraction of remaining) keeps the
    // target strictly below the clock, so a single turn can never flag.
    sampleClocks.filter(_.remainingMs >= 1000L).foreach { c =>
      val b = TimeManager.budget(c)
      assert(b.targetMs < c.remainingMs, s"target ${b.targetMs} >= remaining ${c.remainingMs} for $c")
    }
  }

  test("invariant: more remaining time never decreases the target (monotonic)") {
    val increasing = List(2000L, 5000L, 60000L, 180000L, 600000L)
    increasing.sliding(2).foreach {
      case List(lo, hi) =>
        val tLo = TimeManager.budget(ClockState(lo, 0, 10)).targetMs
        val tHi = TimeManager.budget(ClockState(hi, 0, 10)).targetMs
        assert(tHi >= tLo, s"target at $hi ($tHi) < target at $lo ($tLo)")
      case _ => ()
    }
  }

  test("invariant: a Fischer increment never lowers the target versus sudden death") {
    sampleClocks.foreach { c =>
      val suddenDeath = TimeManager.budget(c.copy(incrementMs = 0)).targetMs
      val withInc     = TimeManager.budget(c).targetMs
      assert(withInc >= suddenDeath, s"increment lowered target for $c ($withInc < $suddenDeath)")
    }
  }
