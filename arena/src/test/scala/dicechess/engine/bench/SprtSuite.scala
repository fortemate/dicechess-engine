package dicechess.engine.bench

import dicechess.engine.bench.Sprt.{Pentanomial, Trinomial, Verdict}

/** Pure math — ported from `dicechess-play-api`'s `SprtSuite` (#522), same vectors, to cross-check this port against
  * the original. Anchors: the logistic score curve, the exact SPRT bounds, verdicts on clearly decided/undecided
  * corpora, and the antisymmetry that guarantees the two perspectives of one matchup can never both "win".
  */
class SprtSuite extends munit.FunSuite:

  private val alpha = 0.05
  private val beta  = 0.05

  test("scoreOfElo: 0 elo is an even game, +200 elo is the textbook 76%") {
    assertEqualsDouble(Sprt.scoreOfElo(0.0), 0.5, 1e-12)
    assertEqualsDouble(Sprt.scoreOfElo(200.0), 0.7597, 1e-4)
    assertEqualsDouble(Sprt.scoreOfElo(-200.0), 1.0 - Sprt.scoreOfElo(200.0), 1e-12)
  }

  test("bounds for alpha = beta = 0.05 are ±ln 19") {
    val r = Sprt.test(Pentanomial.Empty, Trinomial.Empty, 0, 20, alpha, beta)
    assertEqualsDouble(r.upper, math.log(19.0), 1e-12)
    assertEqualsDouble(r.lower, -math.log(19.0), 1e-12)
    assertEquals(r.verdict, Verdict.Continue)
    assertEquals(r.llr, 0.0)
  }

  test("a clearly superior bot is accepted; its mirror image is rejected") {
    // 60% pair-score corpus: mean 0.6125 over 100 pairs — decisive for the [0, 20] elo hypothesis gap.
    val strong = Pentanomial(5, 15, 30, 30, 20)
    val up     = Sprt.test(strong, Trinomial.Empty, 0, 20, alpha, beta)
    assertEquals(up.verdict, Verdict.AcceptH1)
    assert(up.llr > up.upper, s"llr ${up.llr} must clear ${up.upper}")
    // The same games seen from the other side: bins reversed, LLR exactly negated.
    val weak = Pentanomial(20, 30, 30, 15, 5)
    val down = Sprt.test(weak, Trinomial.Empty, 0, 20, alpha, beta)
    assertEquals(down.verdict, Verdict.AcceptH0)
  }

  test("perspective flip negates the LLR exactly (elo0 = -elo1 symmetric hypotheses)") {
    val penta    = Pentanomial(7, 18, 41, 23, 11)
    val forward  = Sprt.test(penta, Trinomial(3, 2, 5), -10, 10, alpha, beta)
    val backward = Sprt.test(Pentanomial(11, 23, 41, 18, 7), Trinomial(5, 2, 3), -10, 10, alpha, beta)
    assertEqualsDouble(forward.llr, -backward.llr, 1e-9)
  }

  test("an even corpus keeps the test running") {
    val even = Sprt.test(Pentanomial(10, 20, 40, 20, 10), Trinomial.Empty, 0, 20, alpha, beta)
    assertEquals(even.verdict, Verdict.Continue)
    assert(math.abs(even.llr) < 1.0, s"an exactly-even sample must sit near zero, got ${even.llr}")
  }

  test("a degenerate all-wins sample is decisive with a SANE magnitude, not an epsilon explosion") {
    val sweep = Sprt.test(Pentanomial(0, 0, 0, 0, 40), Trinomial.Empty, 0, 20, alpha, beta)
    assertEquals(sweep.verdict, Verdict.AcceptH1)
    assert(sweep.llr.isFinite && sweep.llr < 100.0, s"pseudo-count smoothing must keep the LLR honest: ${sweep.llr}")
  }

  test("one lucky single game cannot overwhelm a hundred balanced pairs (live-corpus regression)") {
    // A single decided game (n=1, variance 0) must not dictate the verdict over 92 near-even pairs — distribution
    // smoothing (not a variance floor) keeps its LLR contribution honest instead of exploding.
    val balancedPairs = Pentanomial(21, 0, 50, 1, 20)
    val oneLuckyWin   = Trinomial(losses = 0, draws = 0, wins = 1)
    val r             = Sprt.test(balancedPairs, oneLuckyWin, 0, 20, alpha, beta)
    assertEquals(r.verdict, Verdict.Continue)
    assert(math.abs(r.llr) < 3.0, s"a lone game must nudge, not decide: ${r.llr}")
  }

  test("trinomial singles contribute in the same direction and add to the pair evidence") {
    val pairsOnly = Sprt.test(Pentanomial(5, 15, 30, 30, 20), Trinomial.Empty, 0, 20, alpha, beta)
    val withWins  = Sprt.test(Pentanomial(5, 15, 30, 30, 20), Trinomial(2, 2, 16), 0, 20, alpha, beta)
    assert(withWins.llr > pairsOnly.llr, "extra winning singles must push the LLR further up")
    assertEquals(withWins.observations, pairsOnly.observations + 20)
  }

  test("tighter error rates demand more evidence (wider bounds)") {
    val strict = Sprt.test(Pentanomial.Empty, Trinomial.Empty, 0, 20, 0.01, 0.01)
    assert(strict.upper > math.log(19.0), "1% error bounds must sit beyond 5% bounds")
  }
