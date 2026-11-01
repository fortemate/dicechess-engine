// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

/** The result of a bounded or cancellable search computation: an exact value, a proven one-sided bound, or an
  * interrupted computation that proved nothing.
  *
  * A stochastic search must keep three things apart that a bare number conflates: the expectation it finished
  * computing, a bound it proved before a cutoff ended the work early, and a partial accumulation the deadline or work
  * budget interrupted. The first two are comparable within their stated direction; the third is not comparable with
  * anything, and a search that ranks it against finished candidates lets the instant the clock landed choose the move.
  * [[SearchOutcome.Incomplete]] therefore carries **no value field at all**: a cancelled expectation cannot be ranked,
  * blended or written to a cache by accident, because the compiler refuses to read a score from it.
  *
  * ## Perspective and bound direction
  *
  * Every value is expressed from the single `perspective` colour the search was asked for (see [[UtilityBounds]]): a
  * larger value is always better for that colour, whichever side is to move at the node that produced it.
  *
  *   - [[SearchOutcome.Exact]] — the true value of the node is `value`.
  *   - [[SearchOutcome.LowerBound]] — the true value is **at least** `value`. Produced by a fail-high: the node's value
  *     was driven **strictly above** `beta`, so the caller stopped expanding it.
  *   - [[SearchOutcome.UpperBound]] — the true value is **at most** `value`. Produced by a fail-low: the node's value
  *     was driven **strictly below** `alpha`.
  *   - [[SearchOutcome.Incomplete]] — the computation was cancelled by its [[SearchControl]] before it proved a value
  *     or a bound. Only the work it consumed is reported.
  *
  * Cutoffs are strict (`< alpha`, `> beta`), never `<=`/`>=`: a root candidate whose bound exactly ties the best score
  * so far may still *equal* the best and must reach the seeded random tie-break as an `Exact` value rather than vanish
  * into a bound.
  *
  * ## Work accounting
  *
  * `workUsed` is the cumulative count of the invocation's [[BudgetedRules.Budget]] at the moment the outcome was
  * produced (`0` when the search ran without a budget). It is a count of rules work units — generator calls, examined
  * micro-moves, capture-roll iterations — not time, instructions or heap bytes.
  *
  * ## Example
  *
  * The rules artifact reports its own budgeted queries as [[BudgetedRules.Outcome]]; [[SearchOutcome.fromRules]] lifts
  * such a result into the search vocabulary, and the `Incomplete` case stays score-free across the boundary:
  *
  * ```scala
  * import dicechess.engine.domain.FenParser
  * import dicechess.engine.search.{BudgetedRules, SearchOutcome}
  *
  * val state  = FenParser.parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1 KR").toOption.get
  * val budget = new BudgetedRules.Budget(limit = 1_000)
  * val legal  = SearchOutcome.fromRules(BudgetedRules.turnPaths(state, budget))
  *
  * val candidates: Int = legal match
  *   case SearchOutcome.Exact(paths, _) => paths.size
  *   case SearchOutcome.Incomplete(_)   => 0 // nothing to rank: the traversal was interrupted
  *   case _                             => 0 // a rules query never yields a one-sided bound
  *
  * assert(legal.isExact)
  * assert(legal.workUsed == budget.used)
  * assert(legal.valueOption.exists(_.size == candidates))
  * ```
  *
  * @tparam A
  *   the value type; `Double` for chance-node expectations, a path list for rules queries
  */
enum SearchOutcome[+A] derives CanEqual:
  /** The computation finished: the true value is exactly `value`. */
  case Exact(value: A, workUsed: Long)

  /** Fail-high: the true value is at least `value` (it was driven strictly above `beta`). */
  case LowerBound(value: A, workUsed: Long)

  /** Fail-low: the true value is at most `value` (it was driven strictly below `alpha`). */
  case UpperBound(value: A, workUsed: Long)

  /** Cancelled before anything was proven. Deliberately carries no value. */
  case Incomplete(workUsed: Long)

  /** Cumulative work units consumed when this outcome was produced; `0` without a budget. */
  def workUsed: Long

  /** `true` only for [[SearchOutcome.Exact]]: the one outcome that may be ranked against other exact outcomes. */
  def isExact: Boolean = this match
    case Exact(_, _) => true
    case _           => false

  /** `true` for a proven one-sided bound ([[SearchOutcome.LowerBound]] or [[SearchOutcome.UpperBound]]). */
  def isBound: Boolean = this match
    case LowerBound(_, _) | UpperBound(_, _) => true
    case _                                   => false

  /** `true` when the computation was cancelled before it proved a value or a bound. */
  def isIncomplete: Boolean = this match
    case Incomplete(_) => true
    case _             => false

  /** The exact value, or `None` for a bound or an interrupted computation. */
  def exactOption: Option[A] = this match
    case Exact(value, _) => Some(value)
    case _               => None

  /** The carried value — exact or bound — or `None` for an interrupted computation. Callers that read a bound through
    * this accessor must check [[isBound]] and the bound's direction themselves.
    */
  def valueOption: Option[A] = this match
    case Exact(value, _)      => Some(value)
    case LowerBound(value, _) => Some(value)
    case UpperBound(value, _) => Some(value)
    case Incomplete(_)        => None

  /** Transforms the carried value, preserving the outcome kind, its bound direction and its work count. */
  def map[B](f: A => B): SearchOutcome[B] = this match
    case Exact(value, work)      => Exact(f(value), work)
    case LowerBound(value, work) => LowerBound(f(value), work)
    case UpperBound(value, work) => UpperBound(f(value), work)
    case Incomplete(work)        => Incomplete(work)

  /** The same outcome reported with a different cumulative work count, for callers that aggregate nested work. */
  def withWorkUsed(work: Long): SearchOutcome[A] = this match
    case Exact(value, _)      => Exact(value, work)
    case LowerBound(value, _) => LowerBound(value, work)
    case UpperBound(value, _) => UpperBound(value, work)
    case Incomplete(_)        => Incomplete(work)

object SearchOutcome:
  /** Lifts a budgeted rules query result into the search vocabulary: a complete rules answer is exact, an interrupted
    * one is [[SearchOutcome.Incomplete]]. Rules queries never produce one-sided bounds.
    */
  def fromRules[A](outcome: BudgetedRules.Outcome[A]): SearchOutcome[A] = outcome match
    case BudgetedRules.Outcome.Complete(value, used) => Exact(value, used)
    case BudgetedRules.Outcome.Incomplete(used)      => Incomplete(used)
