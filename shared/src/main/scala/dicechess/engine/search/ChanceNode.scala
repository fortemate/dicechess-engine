// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.search.ActiveDicePartitioner.WeightedRoll

/** The shared weighted chance node of stochastic Dice Chess search: one unknown 3d6 roll, expanded over the
  * promotion-aware equivalence classes of [[ActiveDicePartitioner]], accumulated with exact probability mass and pruned
  * with Ballard's Star1 and Star2 bounds — stated on a caller-declared [[UtilityBounds]] scale and reported as a
  * [[SearchOutcome]].
  *
  * The mechanism owns what holds for every consumer: which classes exist and what they weigh, that no class is ever
  * dropped or renormalised (a forced pass keeps its full weight), the arithmetic of the expectation, the per-class
  * window transform, the soundness of every cutoff, and the cooperative cancellation protocol of [[SearchControl]]. The
  * consumer owns what makes one search differ from another: how a roll's decision node is valued ([[Policy.decide]]),
  * which single legal reply bounds it for a Star2 probe ([[Policy.probe]]), and in which order the classes are visited.
  * Shortlists, selective reply filters, urgency heuristics and root blending therefore stay outside this object.
  *
  * ## Classes and weights
  *
  * `afterTurn` is the position **after** the previous turn ended: `endTurn()` has been applied, the dice pool is empty
  * and both kings are on the board (a king capture is terminal and must be settled by the caller before a chance node
  * is opened). `ActiveDicePartitioner.partitionsFor(afterTurn)` yields the classes for `afterTurn.activeColor`, with
  * positive integer weights summing to [[DiceRolls.totalOrderedRolls]] (`216`); the expectation is
  *
  * $$E = \frac{1}{216} \sum_i w_i \, V(\text{class}_i)$$
  *
  * accumulated as the integer-weighted sum $\sum_i w_i V_i$ in `Double` and divided by `216` **once**, so that for
  * integer-valued leaves the result is the exact rational and does not depend on the order in which the classes were
  * visited. The empty class ([[CompactRoll.Empty]]) and any non-empty class whose retained dice admit no legal turn are
  * forced passes: the policy values `rolled.endTurn()` for them, with their full weight.
  *
  * ## Roles, windows and the mandatory-move invariant
  *
  * The decision node below the chance node belongs to `afterTurn.activeColor`; it is **MAX** when that colour is
  * `bounds.perspective` and **MIN** otherwise, and every value is stated for `bounds.perspective`. The window
  * `[alpha, beta]` is the parent's: the node fails low with an [[SearchOutcome.UpperBound]] once its value is proven
  * strictly below `alpha`, and fails high with a [[SearchOutcome.LowerBound]] once proven strictly above `beta`.
  * Cutoffs are strict so that an exact tie always reaches [[SearchOutcome.Exact]].
  *
  * Unexpanded classes are bounded only by what is **proven**: the policy's declared range `[lowestValue, highestValue]`
  * for the values its decision nodes can take (within `[bounds.terminalLoss, bounds.terminalWin]`), and the bounds
  * returned by Star2 probes of legal replies. Passing is illegal whenever a roll admits a legal turn, so the static
  * value of `rolled.endTurn()` is never used as a bound for such a roll — a mandatory move can be worse for the mover
  * than standing still, which is exactly what fixture F9 of the contracts page shows. At a MIN chance node the
  * pessimistic bound is `terminalLoss` unless the policy has ruled out an opponent king capture below it.
  *
  * ## Star1 and Star2
  *
  * Before every class the mechanism checks whether the accumulated mass plus the best the remaining classes could add
  * can still reach `alpha` (and, symmetrically, whether the worst they could add still exceeds `beta`), and stops with
  * the proven bound when it cannot (Star1). When the node's own side of the window is finite, [[Policy.probe]] is asked
  * first for a bound per class — a single legal reply's value, exact for terminal and forced-pass classes — and the
  * resulting suffix sums both tighten every Star1 check and may prune the node before a single class is expanded
  * (Star2), unless the policy explicitly disables probing. A probe that is already [[SearchOutcome.Exact]] settles its
  * class outright and is never re-searched.
  *
  * Each class is searched inside the window its own weight implies: a child value below `childAlpha` cannot lift the
  * expectation to `alpha` even if every remaining class scores `highestValue`. A child that answers with a bound that
  * settles the parent's window ends the node; one that does not is re-searched with an open window, because only an
  * exact value can join an expectation.
  *
  * ## Cancellation and outcomes
  *
  * `control` is consulted before the first expensive operation, after the probe phase, and before every expanded class;
  * a cancelled node returns [[SearchOutcome.Incomplete]] with no value, so a partial accumulation can never be ranked
  * or cached. The policy returns `Incomplete` itself when a nested reply generation exhausts the shared
  * [[BudgetedRules.Budget]]. [[Result.lossTainted]] reports whether any resolved class or child lost `perspective`'s
  * king, which root blending rules ([[RootRescore]]) treat as unrecoverable.
  *
  * ## Ordering and scratch
  *
  * The classes are visited in partition order (descending weight) unless the caller passes a permutation in its own
  * invocation-local array; the shared `IArray[WeightedRoll]` is never reordered or exposed. All scratch is allocated
  * per call, so nested and concurrent independent invocations never interfere. Cancelled entries allocate no expansion
  * scratch; open windows need no suffix arrays, and an initial unprobed cut uses only scalar reverse-fold totals.
  * Settlement arrays exist only when an exact probe needs retaining. Policies with no probes can explicitly set
  * [[Policy.probingEnabled]] to `false`, constructing rolled states only for classes that are actually expanded.
  *
  * ## Example
  *
  * A one-ply policy that values each class by the opponent's material-best reply — the chance layer of a two-ply
  * expectimax over the engine's material evaluator:
  *
  * ```scala
  * import dicechess.engine.domain.{Color, FenParser, GameState}
  * import dicechess.engine.search.{ChanceNode, Evaluator, ExpectimaxSearch, SearchControl}
  *
  * // Black is to move on a fresh, unknown roll; White's king-only opponent retains 0..3 King dice.
  * val afterTurn = FenParser.parse("4k3/8/8/8/8/8/8/4K3 b - - 0 1").toOption.get
  * val bounds    = ExpectimaxSearch.utilityBounds(perspective = Color.White)
  *
  * val policy = new ChanceNode.Policy:
  *   // A one-ply MIN node can lose White's king but can never win for White: [terminalLoss, maxNonTerminal].
  *   def lowestValue: Double              = bounds.terminalLoss
  *   def highestValue: Double             = bounds.maxNonTerminal
  *   override def probingEnabled: Boolean = false // this policy uses only exact decisions
  *   def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
  *     ChanceNode.Replies.generate(rolled, SearchControl.Unbounded) match
  *       case None                                  => ChanceNode.Value.incomplete(SearchControl.Unbounded)
  *       case Some(replies) if replies.capturesKing => ChanceNode.Value.exact(bounds.terminalLoss, lossTainted = true)
  *       case Some(replies) if replies.isForcedPass =>
  *         ChanceNode.Value.exact(Evaluator.evaluateMaterial(rolled.endTurn(), Color.White).toDouble)
  *       case Some(replies) =>
  *         ChanceNode.Value.exact(replies.states.map(Evaluator.evaluateMaterial(_, Color.White)).min.toDouble)
  *
  * val result = ChanceNode.expand(
  *   afterTurn,
  *   bounds,
  *   SearchControl.Unbounded,
  *   alpha = Double.NegativeInfinity,
  *   beta = Double.PositiveInfinity,
  *   policy
  * )
  * assert(result.outcome.isExact)
  * assert(result.classes == 4 && result.classesResolved == 4)
  * ```
  */
object ChanceNode:

  /** What a policy reports for one class's decision node: the outcome for `perspective`, and whether the subtree
    * contained a line in which `perspective`'s king was captured.
    *
    * An [[SearchOutcome.Exact]] outcome joins the expectation; a bound is checked against the parent's window and
    * otherwise re-searched; [[SearchOutcome.Incomplete]] cancels the whole node.
    */
  final case class Value(outcome: SearchOutcome[Double], lossTainted: Boolean = false) derives CanEqual

  object Value:
    /** An exact decision value with the given loss taint; `workUsed` is the invocation's cumulative budget count. */
    def exact(value: Double, lossTainted: Boolean = false, workUsed: Long = 0L): Value =
      Value(SearchOutcome.Exact(value, workUsed), lossTainted)

    /** The value a policy returns when `control` cancelled its work: no score, only the work consumed. */
    def incomplete(control: SearchControl): Value = Value(SearchOutcome.Incomplete(control.workUsed))

  /** The outcome of one chance node.
    *
    * @param outcome
    *   the expectation, a proven bound on it, or [[SearchOutcome.Incomplete]]
    * @param lossTainted
    *   whether any resolved class or probed child lost `perspective`'s king
    * @param classes
    *   how many equivalence classes the partition had
    * @param classesResolved
    *   how many of them contributed a value to the expectation before the node finished or was cut; the difference is
    *   the work pruning saved
    * @param probePruned
    *   whether the Star2 probe sum alone settled the node, before any class was expanded
    */
  final case class Result(
      outcome: SearchOutcome[Double],
      lossTainted: Boolean,
      classes: Int,
      classesResolved: Int,
      probePruned: Boolean
  ) derives CanEqual

  /** The consumer's side of a chance node: how a roll's decision node is valued and, optionally, bounded.
    *
    * Both callbacks receive `rolled` — `afterTurn` with the class's retained dice applied and the same side to move —
    * and must handle the forced pass themselves: a class with no legal turn is valued at `rolled.endTurn()` (see
    * [[Replies.isForcedPass]]). The same `classIndex` identifies a class in [[probe]] and [[decide]], so a policy can
    * keep per-class scratch such as generated replies between the two phases.
    */
  trait Policy:
    /** The least value [[decide]] can return at this node, at or above `bounds.terminalLoss`. `terminalLoss` itself
      * whenever the opponent may capture `perspective`'s king below this node.
      */
    def lowestValue: Double

    /** The greatest value [[decide]] can return at this node, at or below `bounds.terminalWin`. */
    def highestValue: Double

    /** Explicitly opt out when this policy has no probes. `false` suppresses [[probe]] and builds rolled states only
      * for expanded classes. The default preserves every existing override's callback semantics; the mechanism never
      * infers absence from a policy's implementation type. Keep this declaration stable for the expansion.
      */
    def probingEnabled: Boolean = true

    /** Star2 probes: one bound per class, in class order, or `None` to skip probing. Called at most once per node, only
      * when the node's own side of the window is finite, and before any [[decide]] call.
      *
      * A probe must be **sound in the role's direction**: at a MIN node an [[SearchOutcome.UpperBound]] (the value of
      * any single legal reply is at least the minimum), at a MAX node a [[SearchOutcome.LowerBound]]. An
      * [[SearchOutcome.Exact]] probe — natural for a terminal class or a forced pass — settles its class without a
      * later [[decide]]. A bound pointing the wrong way is ignored (the declared range stands in), and any
      * [[SearchOutcome.Incomplete]] cancels the node.
      */
    def probe(rolled: IArray[GameState]): Option[Array[Value]] = None

    /** Values the decision node reached by `rolled` inside `[alpha, beta]`, both stated for `perspective` and already
      * transformed to this class's own scale. A result strictly outside the window may be reported as the matching
      * bound; a call with an open window (`-∞`, `+∞`) must return [[SearchOutcome.Exact]] or
      * [[SearchOutcome.Incomplete]].
      */
    def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): Value

  /** The legal replies to one rolled position, materialised once through the canonical turn-state traversal.
    *
    * `states` holds the position after each legal full turn **and** its `endTurn()`, in canonical generation order, for
    * every turn that does not capture the king; it is empty when the roll is a forced pass or when some reply captures
    * the king ([[capturesKing]]), since a terminal reply makes the other replies irrelevant. The array is owned by the
    * policy that generated it: read it freely, never hand it to a caller that mutates it.
    */
  final class Replies private[search] (val states: Array[GameState], val capturesKing: Boolean):
    /** Number of non-terminal legal replies. */
    def count: Int = states.length

    /** No legal turn at all: the mover passes and the position to value is `rolled.endTurn()`. */
    def isForcedPass: Boolean = !capturesKing && states.isEmpty

  object Replies:
    /** The replies of a forced pass: shared, since it carries no state. */
    val ForcedPass: Replies = new Replies(Array.empty, capturesKing = false)

    /** The replies of a roll that captures the king: shared, since the capture settles the node. */
    val KingCapture: Replies = new Replies(Array.empty, capturesKing = true)

    /** Generates every legal reply to `rolled` — through [[BudgetedRules.visitTurnStates]] on the control's shared
      * budget when it has one, otherwise through [[TurnGenerator.forEachLegalTurnState]] — stopping at the first king
      * capture. Returns `None` when the budget ran out, in which case `control.isCancelled` is already `true` and the
      * caller must answer [[Value.incomplete]] rather than treat the missing list as a forced pass. A roll with no dice
      * is a forced pass without a generator call.
      */
    def generate(rolled: GameState, control: SearchControl): Option[Replies] =
      if rolled.flags.isDicePoolEmpty then Some(ForcedPass)
      else
        val collector = new ReplyCollector
        val complete  = control.budget match
          case Some(budget) =>
            BudgetedRules.visitTurnStates(rolled, budget)(collector.visit) match
              case BudgetedRules.Outcome.Complete(_, _) => true
              case BudgetedRules.Outcome.Incomplete(_)  => false
          case None =>
            TurnGenerator.forEachLegalTurnState(rolled)(collector.visit)
            true
        if !complete then None
        else if collector.captured then Some(KingCapture)
        else if collector.isEmpty then Some(ForcedPass)
        else Some(new Replies(collector.result(), capturesKing = false))

  final private class ReplyCollector:
    private val buffer = scala.collection.mutable.ArrayBuffer.empty[GameState]
    var captured       = false

    val visit: (Array[Move], Int, GameState, Boolean) => Boolean = (_, _, resState, isKing) =>
      if isKing then
        captured = true
        false
      else
        buffer += resState
        true

    def isEmpty: Boolean           = buffer.isEmpty
    def result(): Array[GameState] = buffer.toArray

  /** One policy's lazy cross-class traversal. Never retained across chance nodes, even when they share a control.
    * Evaluators and selective reply policies consume the same canonical arrays as before. A terminal reply settles its
    * class during discovery; an interrupted traversal never exposes its partially collected array. Timed controls check
    * the clock at class entry and every 64 internal cancellation polls. Work-budget exhaustion remains checked at every
    * admission; individual geometry and callback operations remain non-preemptive.
    */
  final private[search] class ReplyTraversal(afterTurn: GameState, control: SearchControl):
    private inline val ClockPollInterval = 64
    private var pollsBeforeClock         = 0
    private val traversal                = new TurnGenerator.SharedTraversal(
      afterTurn,
      control.budget,
      if control.isTimed then () => timedCancellation() else () => control.isCancelled
    )

    private inline def timedCancellation(): Boolean =
      if pollsBeforeClock > 0 then
        pollsBeforeClock -= 1
        false
      else
        pollsBeforeClock = ClockPollInterval - 1
        control.isCancelled

    def generate(rolled: GameState): Option[Replies] =
      if rolled.flags.isDicePoolEmpty && !control.isCancelled then Some(Replies.ForcedPass)
      else if control.isTimed && control.isCancelled then None
      else
        if control.isTimed then pollsBeforeClock = ClockPollInterval - 1
        val collector = new ReplyCollector
        traversal.visitFlags(rolled.flags)(collector.visit) match
          case BudgetedRules.Outcome.Incomplete(_)  => None
          case BudgetedRules.Outcome.Complete(_, _) =>
            if collector.captured then Some(Replies.KingCapture)
            else if collector.isEmpty then Some(Replies.ForcedPass)
            else Some(new Replies(collector.result(), capturesKing = false))

  /** Expands the chance node at `afterTurn` in partition order. See the object documentation for the contract. */
  def expand(
      afterTurn: GameState,
      bounds: UtilityBounds,
      control: SearchControl,
      alpha: Double,
      beta: Double,
      policy: Policy
  ): Result =
    expand(afterTurn, bounds, control, alpha, beta, policy, PartitionOrder)

  /** Expands the chance node at `afterTurn` visiting the classes in `order`, a caller-owned permutation of
    * `0 until classes.length` (an urgency ordering, for instance). The empty array means partition order. The shared
    * partition array is never touched; only the visiting order changes, and with it which bound a cutoff proves first —
    * never the exact expectation.
    */
  def expand(
      afterTurn: GameState,
      bounds: UtilityBounds,
      control: SearchControl,
      alpha: Double,
      beta: Double,
      policy: Policy,
      order: Array[Int]
  ): Result =
    val classes = ActiveDicePartitioner.partitionsFor(afterTurn)
    requireChanceBoundary(afterTurn)
    requireRange(bounds, policy)
    requirePermutation(order, classes.length)
    if control.isCancelled then Result(SearchOutcome.Incomplete(control.workUsed), false, classes.length, 0, false)
    else
      val node = new Expansion(afterTurn, classes, order, bounds, control, alpha, beta, policy)
      node.probePhase()
      node.run()

  private val PartitionOrder: Array[Int] = Array.empty

  // Zero-length sentinels cannot hold mutable contents and are never written. Every non-empty scratch array is
  // allocated and owned by one Expansion, including across nested calls.
  private val EmptyDoubles: Array[Double]    = Array.empty
  private val EmptyFlags: Array[Boolean]     = Array.empty
  private val EmptyStates: IArray[GameState] = IArray.empty

  private def requireChanceBoundary(afterTurn: GameState): Unit =
    require(afterTurn.flags.isDicePoolEmpty, "a chance node opens on a fresh roll: the dice pool must be empty")
    require(
      !(afterTurn.kings & afterTurn.whitePieces).isEmpty && !(afterTurn.kings & afterTurn.blackPieces).isEmpty,
      "a chance node needs both kings on the board: a captured king is terminal and is settled by the caller"
    )

  private def requireRange(bounds: UtilityBounds, policy: Policy): Unit =
    require(
      bounds.terminalLoss <= policy.lowestValue && policy.lowestValue <= policy.highestValue &&
        policy.highestValue <= bounds.terminalWin,
      s"the policy's value range [${policy.lowestValue}, ${policy.highestValue}] must lie inside " +
        s"[${bounds.terminalLoss}, ${bounds.terminalWin}]"
    )

  private def requirePermutation(order: Array[Int], classes: Int): Unit =
    if order.nonEmpty then
      require(order.length == classes, s"order must have one entry per class ($classes), got ${order.length}")
      var seen = 0L
      var i    = 0
      while i < order.length do
        val index = order(i)
        require(index >= 0 && index < classes, s"order entry $index is not a class index in 0 until $classes")
        require((seen & (1L << index)) == 0L, s"order visits class $index twice")
        seen |= 1L << index
        i += 1

  /** One expansion's lazy scratch and loop: owned by this call, read by one thread. */
  final private class Expansion(
      afterTurn: GameState,
      classes: IArray[WeightedRoll],
      order: Array[Int],
      bounds: UtilityBounds,
      control: SearchControl,
      alpha: Double,
      beta: Double,
      policy: Policy
  ):
    private val count      = classes.length
    private val maximizing = bounds.isMaximizing(afterTurn.activeColor)
    private val mass       = DiceRolls.totalOrderedRolls.toDouble

    /** Suffix sums of `weight * ceiling` and `weight * floor` over the visiting order; index `count` is zero. */
    private var ceilingSuffix: Array[Double] = EmptyDoubles
    private var floorSuffix: Array[Double]   = EmptyDoubles

    /** Classes the probe phase settled exactly, with their values. */
    private var settled: Array[Boolean]     = EmptyFlags
    private var settledValue: Array[Double] = EmptyDoubles

    private var rolledStates: IArray[GameState] = EmptyStates

    private var initialUpper = Double.PositiveInfinity
    private var initialLower = Double.NegativeInfinity

    private var accumulated     = 0.0
    private var resolved        = 0
    private var tainted         = false
    private var probeCut        = false
    private var probeIncomplete = false
    private var probed          = false

    private def entry(position: Int): WeightedRoll =
      if order.isEmpty then classes(position) else classes(order(position))

    private def rolledAt(position: Int): GameState =
      if rolledStates.nonEmpty then rolledStates(position) else entry(position).roll.applyToState(afterTurn)

    private def isSettled(position: Int): Boolean = settled.nonEmpty && settled(position)

    // Reproduce the historical reverse-fold arithmetic without scratch when an initial cut needs only the total.
    private def uniformSuffix(position: Int, value: Double): Double =
      var sum   = 0.0
      var index = count - 1
      while index >= position do
        sum += entry(index).weight.toDouble * value
        index -= 1
      sum

    private def ceilingAt(position: Int): Double =
      if ceilingSuffix.nonEmpty then ceilingSuffix(position) else uniformSuffix(position, policy.highestValue)

    private def floorAt(position: Int): Double =
      if floorSuffix.nonEmpty then floorSuffix(position) else uniformSuffix(position, policy.lowestValue)

    def incomplete(): Result = Result(SearchOutcome.Incomplete(control.workUsed), tainted, count, resolved, probeCut)

    private def upperBound(value: Double): Result =
      Result(SearchOutcome.UpperBound(value, control.workUsed), tainted, count, resolved, probeCut)

    private def lowerBound(value: Double): Result =
      Result(SearchOutcome.LowerBound(value, control.workUsed), tainted, count, resolved, probeCut)

    /** Whether this node's own side of the window is finite — the only case in which probing can pay for itself. */
    private def boundedForRole: Boolean =
      if maximizing then beta < Double.PositiveInfinity else alpha > Double.NegativeInfinity

    /** Asks the policy for per-class bounds when the window allows a cut, folds them into the suffix sums, and settles
      * the classes whose probe is already exact. Without probes the suffixes are the declared range times the mass.
      */
    def probePhase(): Unit =
      val probes =
        if boundedForRole && policy.probingEnabled then
          val states = IArray.tabulate(count)(position => entry(position).roll.applyToState(afterTurn))
          rolledStates = states
          policy.probe(states)
        else None
      probes match
        case Some(values) =>
          require(values.length == count, s"probe must answer every class ($count), got ${values.length}")
          probed = true
          fillSuffixes(probes)
        case None =>
          if alpha != Double.NegativeInfinity || beta != Double.PositiveInfinity then
            prepareUniformBounds()
            if !(initialUpper < alpha || initialLower > beta) then fillSuffixes(None)

    private def prepareUniformBounds(): Unit =
      var upper    = 0.0
      var lower    = 0.0
      var position = count - 1
      while position >= 0 do
        val weight = entry(position).weight.toDouble
        upper += weight * policy.highestValue
        lower += weight * policy.lowestValue
        position -= 1
      initialUpper = upper / mass
      initialLower = lower / mass

    private def fillSuffixes(values: Option[Array[Value]]): Unit =
      val ceilings = new Array[Double](count + 1)
      val floors   = new Array[Double](count + 1)
      ceilingSuffix = ceilings
      floorSuffix = floors
      var position = count - 1
      while position >= 0 do
        var ceiling = policy.highestValue
        var floor   = policy.lowestValue
        if values.nonEmpty then
          val value = values.get(position)
          tainted ||= value.lossTainted
          value.outcome match
            case SearchOutcome.Exact(exact, _) =>
              if settled.isEmpty then
                settled = new Array[Boolean](count)
                settledValue = new Array[Double](count)
              settled(position) = true
              settledValue(position) = exact
              ceiling = exact
              floor = exact
            case SearchOutcome.UpperBound(upper, _) if !maximizing => ceiling = clampToRange(upper)
            case SearchOutcome.LowerBound(lower, _) if maximizing  => floor = clampToRange(lower)
            case SearchOutcome.Incomplete(_)                       => probeIncomplete = true
            case _ => () // a bound pointing the wrong way proves nothing here
        val weight = entry(position).weight.toDouble
        ceilings(position) = ceilings(position + 1) + weight * ceiling
        floors(position) = floors(position + 1) + weight * floor
        position -= 1

    private def clampToRange(value: Double): Double =
      math.max(policy.lowestValue, math.min(policy.highestValue, value))

    /** The Star2 cut when the probe sums already settle the window, otherwise the roll loop. Without probes the same
      * check happens inside the loop before the first class, as a Star1 cut on the declared range.
      */
    def run(): Result =
      if probeIncomplete || (rolledStates.nonEmpty && control.isCancelled) then incomplete()
      else
        val probeUpper = if probed then ceilingAt(0) / mass else initialUpper
        val probeLower = if probed then floorAt(0) / mass else initialLower
        if probeUpper < alpha then
          probeCut = probed
          upperBound(probeUpper)
        else if probeLower > beta then
          probeCut = probed
          lowerBound(probeLower)
        else loop()

    private def loop(): Result =
      var result: Option[Result] = None
      var position               = 0
      while position < count && result.isEmpty do
        result = resolveClass(position)
        position += 1
      result.getOrElse(
        Result(SearchOutcome.Exact(accumulated / mass, control.workUsed), tainted, count, resolved, probeCut)
      )

    private inline def resolveClass(position: Int): Option[Result] =
      if isSettled(position) then
        accumulated += entry(position).weight.toDouble * settledValue(position)
        resolved += 1
        None
      else
        val upperNow = if alpha == Double.NegativeInfinity then Double.PositiveInfinity
        else (accumulated + ceilingAt(position)) / mass
        val lowerNow = if beta == Double.PositiveInfinity then Double.NegativeInfinity
        else (accumulated + floorAt(position)) / mass
        if upperNow < alpha then Some(upperBound(upperNow))
        else if lowerNow > beta then Some(lowerBound(lowerNow))
        else if control.isCancelled then Some(incomplete())
        else expandClass(position)

    /** Searches one class inside the window its weight implies and folds the answer; `Some` ends the node. */
    private def expandClass(position: Int): Option[Result] =
      val weight     = entry(position).weight.toDouble
      val rolled     = rolledAt(position)
      val childAlpha =
        if alpha == Double.NegativeInfinity then alpha
        else bounds.clamp((alpha * mass - accumulated - ceilingAt(position + 1)) / weight)
      val childBeta =
        if beta == Double.PositiveInfinity then beta
        else bounds.clamp((beta * mass - accumulated - floorAt(position + 1)) / weight)
      val child = policy.decide(rolled, position, childAlpha, childBeta)
      tainted ||= child.lossTainted
      child.outcome match
        case SearchOutcome.Exact(value, _) =>
          accept(weight, value)
          None
        case SearchOutcome.UpperBound(upper, _) =>
          val combined = (accumulated + weight * upper + ceilingAt(position + 1)) / mass
          if combined < alpha then Some(upperBound(combined)) else research(position, rolled, weight)
        case SearchOutcome.LowerBound(lower, _) =>
          val combined = (accumulated + weight * lower + floorAt(position + 1)) / mass
          if combined > beta then Some(lowerBound(combined)) else research(position, rolled, weight)
        case SearchOutcome.Incomplete(_) => Some(incomplete())

    private def accept(weight: Double, value: Double): Unit =
      accumulated += weight * value
      resolved += 1

    /** A bounded child that did not settle the parent is searched again with an open window: only an exact value can
      * join an expectation.
      */
    private def research(position: Int, rolled: GameState, weight: Double): Option[Result] =
      val exact = policy.decide(rolled, position, Double.NegativeInfinity, Double.PositiveInfinity)
      tainted ||= exact.lossTainted
      exact.outcome match
        case SearchOutcome.Exact(value, _) =>
          accept(weight, value)
          None
        case SearchOutcome.Incomplete(_) => Some(incomplete())
        case other                       =>
          require(false, s"a decision node searched with an open window must be exact, got $other")
          Some(incomplete())
