package dicechess.engine.bench

/** Turns a match's outcome histograms into the only number that decides whether a comparison was worth running: how
  * many games it takes to resolve a difference of a given size.
  *
  * This exists because a string of model changes was measured, reported and acted on at sample sizes that could not
  * have seen the effects being chased (issue #508). A verdict alone — "SPRT stopped after 122 games" — does not say
  * whether 2pp is resolvable at all, so every "offline better, win rate flat" result stayed ambiguous between "the idea
  * is worthless" and "the instrument is blind".
  *
  * Two binnings of the SAME games are compared:
  *   - '''trinomial''' — one observation per game (`0 / ½ / 1`), the classic independent-games view;
  *   - '''pentanomial''' — one observation per mirrored pair (`0 / ¼ / ½ / ¾ / 1`), where the pair's two games are
  *     colour-swapped over one shared dice stream.
  *
  * Pairing only pays if that shared stream actually correlates the two games. If it does not, a pair is just two
  * independent games and `sdPair = sdGame / sqrt(2)` exactly; [[Comparison.varianceReduction]] measures the departure
  * from that, so the benefit is reported rather than assumed.
  *
  * '''Measured on a 100-pair pilot at 3+2''' (the numbers this class exists to produce):
  *
  * ```text
  * cell                     score   sd_pair  sd_game  reduction   games for 2pp      games for 3pp
  * aggressive vs itself     51.5%   0.3053   0.5010     1.160     1792 / 2411        796 / 1072
  * aggressive vs greedy     64.5%   0.2687   0.4797     1.262     1388 / 2211        618 /  983
  *                                                                (paired/unpaired)  (paired/unpaired)
  * ```
  *
  * Three things follow. Bins `n1`/`n3` came back '''empty in both cells''' — Dice Chess games are decisive, so a pair
  * scores `0 / ½ / 1` and the pentanomial degenerates to three bins; the finer grid contributes nothing and every bit
  * of the gain is correlation. That correlation is real but modest: 63% of pairs split in both cells against the
  * ~46-50% independence would give, worth a 1.16-1.26x cut in standard error, so pairing saves roughly a quarter to a
  * third of the games rather than a factor of several. And the number that reframes a month of results — the customary
  * '''400-game gate resolves only ±3.7-4.2pp''', while the effects being chased were 2-3pp. Those runs could not have
  * seen what they were looking for.
  */
object PairVariance:

  /** Score values of the five pentanomial bins, in `Sprt.Pentanomial` field order. */
  private val PairScores: List[Double] = List(0.0, 0.25, 0.5, 0.75, 1.0)

  /** Score values of the three trinomial bins, in `Sprt.Trinomial` field order. */
  private val GameScores: List[Double] = List(0.0, 0.5, 1.0)

  /** Normal quantile for a two-sided 95% interval. Not derived from a distribution library — this is the one constant
    * the guidance is quoted at, and spelling it out keeps the arithmetic auditable.
    */
  val Z95 = 1.959964

  /** Mean, spread and standard error of one observation family.
    *
    * @param n
    *   number of observations (pairs, or games)
    * @param mean
    *   mean score in `[0, 1]`
    * @param sd
    *   sample standard deviation of a single observation
    * @param standardError
    *   standard error of `mean`; `NaN` when fewer than two observations make spread undefined
    */
  final case class Spread(n: Long, mean: Double, sd: Double, standardError: Double):
    /** Half-width of the two-sided 95% confidence interval on `mean`, in percentage points. */
    def ci95HalfWidthPercent: Double = Z95 * standardError * 100.0

    /** Observations needed for a 95% interval no wider than `±deltaPercent`, at this measured `sd`.
      *
      * Quoted as a CI half-width rather than as the power of a test: the question this answers is "could the run have
      * seen the effect", and a point estimate whose interval already spans the effect answers it without a hypothesis.
      */
    def observationsToResolve(deltaPercent: Double): Option[Long] =
      Option.when(sd.isFinite && sd > 0.0 && deltaPercent > 0.0):
        val delta = deltaPercent / 100.0
        math.ceil(math.pow(Z95 * sd / delta, 2)).toLong

  private def spread(counts: List[Long], scores: List[Double]): Spread =
    val n = counts.sum
    if n == 0 then Spread(0, Double.NaN, Double.NaN, Double.NaN)
    else
      val mean = scores.zip(counts).map((s, c) => s * c).sum / n
      if n == 1 then Spread(1, mean, Double.NaN, Double.NaN)
      else
        // Sample variance (n-1): the histogram is a sample from the match's outcome distribution, not the population.
        val ss = scores.zip(counts).map((s, c) => c * math.pow(s - mean, 2)).sum / (n - 1)
        val sd = math.sqrt(ss)
        Spread(n, mean, sd, sd / math.sqrt(n.toDouble))

  def ofPairs(p: Sprt.Pentanomial): Spread =
    spread(List(p.n0, p.n1, p.n2, p.n3, p.n4), PairScores)

  def ofGames(t: Sprt.Trinomial): Spread =
    spread(List(t.losses, t.draws, t.wins), GameScores)

  /** Both binnings of one match, plus what pairing bought.
    *
    * @param varianceReduction
    *   `sdGame / (sdPair * sqrt(2))`. Exactly `1.0` means the mirrored games were uncorrelated and pairing gained
    *   nothing; above `1.0` is a real reduction (the factor by which the standard error shrinks at equal game count);
    *   below `1.0` means the pairing made things worse, which shared dice can genuinely do if colour-swapped games
    *   anti-correlate.
    */
  final case class Comparison(pairs: Spread, games: Spread, varianceReduction: Double):
    /** Games needed to resolve `deltaPercent` under each binning — the deliverable of #508. Pair counts are doubled so
      * both sides are quoted in the same unit: games played.
      */
    def gamesToResolve(deltaPercent: Double): Option[(Long, Long)] =
      for
        pairsNeeded <- pairs.observationsToResolve(deltaPercent)
        gamesNeeded <- games.observationsToResolve(deltaPercent)
      yield (pairsNeeded * 2, gamesNeeded)

  def compare(p: Sprt.Pentanomial, t: Sprt.Trinomial): Comparison =
    val pairSpread = ofPairs(p)
    val gameSpread = ofGames(t)
    val reduction  =
      if pairSpread.sd.isFinite && pairSpread.sd > 0.0 && gameSpread.sd.isFinite then
        gameSpread.sd / (pairSpread.sd * math.sqrt(2.0))
      else Double.NaN
    Comparison(pairSpread, gameSpread, reduction)

/** One mirrored pair as it completed — the per-observation record an optional sink on [[BotMatchRunner.runTimedMatch]]
  * receives (#508).
  *
  * Exists so a run's raw observations can be re-analysed afterwards instead of only its collapsed verdict: the same
  * games can be re-binned, filtered, or checked for the correlation that pairing is supposed to exploit. Off by default
  * — a match that wants no per-pair record pays nothing.
  *
  * @param index
  *   zero-based pair number, matching the `seed + index` dice stream both games shared
  * @param bin
  *   the pentanomial bin `0..4` this pair fell into
  * @param whiteScore
  *   the bot-under-test's score in the game it played as White (`0 / ½ / 1`)
  * @param blackScore
  *   the same for the colour-swapped game
  */
final case class PairObservation(
    index: Int,
    bin: Int,
    whiteScore: Double,
    blackScore: Double,
    whiteGame: TimedGameResult,
    blackGame: TimedGameResult
):
  /** The pair's own score in `[0, 1]` — the observation [[PairVariance]] takes the variance of. */
  def pairScore: Double = (whiteScore + blackScore) / 2.0
