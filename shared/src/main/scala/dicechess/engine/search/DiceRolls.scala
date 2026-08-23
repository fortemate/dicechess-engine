package dicechess.engine.search

/** The probability distribution of a single Dice Chess roll (3d6), as the chance-node primitive shared by every
  * algorithm that has to integrate over an unknown roll.
  *
  * A turn rolls three six-sided dice. Order does not matter (the three pips are a multiset of piece types the mover
  * must play), so the 216 = 6³ ordered outcomes collapse into **56 distinct sorted multisets**, each carrying the
  * number of ordered rolls that map onto it as its weight:
  *
  * | Pattern | Example   | Weight | Count |
  * |:--------|:----------|-------:|------:|
  * | AAA     | `[1,1,1]` |      1 |     6 |
  * | AAB     | `[1,1,2]` |      3 |    30 |
  * | ABC     | `[1,2,3]` |      6 |    20 |
  *
  * Total: 6×1 + 30×3 + 20×6 = 216. The probability of a multiset is therefore `weight / 216`.
  *
  * This lives on its own (rather than inside a single consumer) because it is the exact distribution both
  * [[KingCaptureProbability]] and an expectimax chance node must sum over — the same 56 weighted outcomes, computed
  * once. Backed by an [[IArray]] so hot loops iterate it allocation-free.
  */
object DiceRolls:

  /** Number of ordered 3d6 outcomes — the denominator for turning a multiset weight into a probability. */
  val totalOrderedRolls: Int = 216

  /** The 56 distinct sorted dice multisets paired with their occurrence weight among the 216 ordered rolls.
    *
    * Enumerated directly in non-decreasing order (`d1 <= d2 <= d3`), which yields each sorted multiset exactly once;
    * the weight is the number of distinct piece types in the roll mapped to its permutation count (all-equal → 1,
    * two-distinct → 3, all-distinct → 6).
    */
  val weighted: IArray[(List[Int], Int)] =
    val builder = List.newBuilder[(List[Int], Int)]
    for
      d1 <- 1 to 6
      d2 <- d1 to 6
      d3 <- d2 to 6
    do
      val multiset = List(d1, d2, d3)
      val weight   = multiset.distinct.size match
        case 1 => 1 // AAA — one ordering
        case 2 => 3 // AAB / ABB — three orderings
        case _ => 6 // ABC — six orderings
      builder += ((multiset, weight))
    IArray.from(builder.result())

  /** The 56 distinct sorted dice multisets ordered by occurrence weight descending.
    *
    * The 20 weight-6 multisets come first (covering 55.6% of probability mass), followed by the 30 weight-3 multisets,
    * then the 6 weight-1 multisets. Sorting by descending weight collapses probability bounds fastest during
    * chance-node pruning (e.g. Star1).
    */
  val byWeightDescending: IArray[(List[Int], Int)] =
    IArray.from(weighted.toSeq.sortBy { case (_, w) => -w })
