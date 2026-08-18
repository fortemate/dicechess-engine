package dicechess.engine.bench

import munit.FunSuite

/** Pins [[PairVariance]] against cases whose answers are known by hand, because this is the arithmetic that will be
  * quoted to justify (or refuse) a sample size — an error here would silently license the very underpowered-comparison
  * mistake issue #508 exists to stop.
  */
class PairVarianceSuite extends FunSuite:

  test("an empty histogram yields no usable spread rather than a fabricated zero"):
    val s = PairVariance.ofPairs(Sprt.Pentanomial.Empty)
    assertEquals(s.n, 0L)
    assert(s.mean.isNaN && s.sd.isNaN && s.standardError.isNaN)
    assertEquals(s.observationsToResolve(2.0), None)

  test("a single observation has a mean but no spread"):
    val s = PairVariance.ofPairs(Sprt.Pentanomial(0, 0, 0, 0, 1))
    assertEquals(s.n, 1L)
    assertEquals(s.mean, 1.0)
    assert(s.sd.isNaN)
    assertEquals(s.observationsToResolve(2.0), None)

  test("an all-identical sample has zero spread, so no n can resolve anything"):
    // Guarding the degenerate case explicitly: 0 / delta would otherwise be "0 games needed".
    val s = PairVariance.ofPairs(Sprt.Pentanomial(0, 0, 10, 0, 0))
    assertEquals(s.mean, 0.5)
    assertEquals(s.sd, 0.0)
    assertEquals(s.observationsToResolve(2.0), None)

  test("mean and sample sd match a hand-computed histogram"):
    // Four pairs: 0, 0.5, 0.5, 1 -> mean 0.5; deviations -0.5, 0, 0, 0.5 -> ss 0.5, /(4-1) -> sd = sqrt(1/6).
    val s = PairVariance.ofPairs(Sprt.Pentanomial(1, 0, 2, 0, 1))
    assertEquals(s.n, 4L)
    assertEqualsDouble(s.mean, 0.5, 1e-12)
    assertEqualsDouble(s.sd, math.sqrt(1.0 / 6.0), 1e-12)
    assertEqualsDouble(s.standardError, math.sqrt(1.0 / 6.0) / 2.0, 1e-12)

  test("observationsToResolve inverts the CI half-width formula"):
    val s = PairVariance.ofPairs(Sprt.Pentanomial(25, 0, 50, 0, 25))
    val n = s.observationsToResolve(2.0).get
    // n = ceil((z*sd/delta)^2); feeding n back must give a half-width at or just under the target.
    val achieved = PairVariance.Z95 * s.sd / math.sqrt(n.toDouble) * 100.0
    assert(achieved <= 2.0 + 1e-9, s"half-width $achieved should be within 2.0")
    assert(achieved > 2.0 - 0.05, s"half-width $achieved should not overshoot the target wildly")

  test("resolving a smaller difference costs quadratically more"):
    val s  = PairVariance.ofPairs(Sprt.Pentanomial(25, 0, 50, 0, 25))
    val n2 = s.observationsToResolve(2.0).get
    val n4 = s.observationsToResolve(4.0).get
    // Halving the target delta must roughly quadruple n — the fact that makes 2pp expensive.
    assert(math.abs(n2.toDouble / n4.toDouble - 4.0) < 0.05, s"$n2 vs $n4")

  test("uncorrelated mirrored games give a variance reduction of exactly 1"):
    // Construct a pair histogram that is the independent product of a 50/50 game histogram:
    // P(0,0)=.25 -> n0, P(split)=.5 -> n2, P(1,1)=.25 -> n4. sdPair = sdGame/sqrt(2) by construction.
    val c = PairVariance.compare(Sprt.Pentanomial(250, 0, 500, 0, 250), Sprt.Trinomial(1000, 0, 1000))
    assertEqualsDouble(c.varianceReduction, 1.0, 0.01)

  test("perfectly correlated pairs report a reduction above 1"):
    // Every pair splits: pair score is always 0.5 (zero variance) while games are still 50/50.
    val c = PairVariance.compare(Sprt.Pentanomial(0, 0, 1000, 0, 0), Sprt.Trinomial(1000, 0, 1000))
    assert(c.pairs.sd == 0.0)
    assert(!c.varianceReduction.isFinite || c.varianceReduction.isNaN, "zero pair spread has no finite ratio")

  test("gamesToResolve quotes both binnings in games, not pairs"):
    val c           = PairVariance.compare(Sprt.Pentanomial(25, 0, 50, 0, 25), Sprt.Trinomial(100, 0, 100))
    val (byPair, _) = c.gamesToResolve(3.0).get
    val pairsNeeded = c.pairs.observationsToResolve(3.0).get
    assertEquals(byPair, pairsNeeded * 2)

  test("a match with no games resolves nothing under either binning"):
    assertEquals(PairVariance.compare(Sprt.Pentanomial.Empty, Sprt.Trinomial.Empty).gamesToResolve(2.0), None)
