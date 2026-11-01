// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.GameState

import scala.util.Random

/** Cooperative cancellation for one synchronous search invocation: an optional deterministic work budget, an optional
  * wall-clock deadline, and a latch that keeps the invocation cancelled once either has fired.
  *
  * Both limits are **cooperative**. A search consults [[isCancelled]] at its yield points — before the first expensive
  * operation at entry, between root candidates, between the dice rolls of every chance node, immediately after an
  * indivisible batch evaluator call, and before every cache lookup — and nothing preempts the work between two yield
  * points. The budget additionally reaches into the rules layer: the same [[BudgetedRules.Budget]] is handed to every
  * nested `BudgetedRules` query, so exhaustion inside a reply generation cancels the whole invocation at the next
  * check.
  *
  * ## Check order and latching
  *
  * [[isCancelled]] reports `true` once the latch is set, else once the budget is exhausted, else once the clock has
  * reached the deadline — and the first `true` latches permanently, so a search never observes a cancelled control
  * becoming live again (a clock that is adjusted backwards, for instance). An already-exhausted budget or an
  * already-passed deadline cancels at the very first check.
  *
  * ## Zero-syscall untimed path
  *
  * When `deadlineNanos` is [[SearchControl.NoDeadline]] (`Long.MaxValue`) the clock is **never read**: the untimed,
  * reproducible path — the seeded arena, fixed-work parity tests, JMH — pays nothing for the timed path's existence.
  * The clock is injectable (`clockNanos`), which is what makes deadline behaviour testable without elapsed-time
  * assertions: a test supplies a clock that advances by a fixed step per read and asserts exactly which check cancels.
  *
  * ## Atomic operations and what cancellation cannot do
  *
  * A single `MoveGenerator.generateMoves` call, a single scalar `eval(state, color)` and a single batched
  * `evalBatch(states, color)` call are atomic: neither a work unit nor a deadline preempts inside them, and nothing
  * here bounds their heap use. A host that runs untrusted or very expensive evaluators keeps its own wall-clock and
  * memory supervision around the whole call.
  *
  * ## Ownership
  *
  * A `SearchControl` is a single-invocation, single-thread object, like the budget it wraps: create one per
  * `findBestMove` call and let it go. The one exception is [[SearchControl.Unbounded]], which can never cancel and is
  * therefore safe to share.
  *
  * ## Example
  *
  * ```scala
  * import dicechess.engine.search.{BudgetedRules, SearchControl}
  *
  * // 1. Deterministic: cancels once 5,000 rules work units have been admitted, whatever the machine speed.
  * val budget   = new BudgetedRules.Budget(5_000)
  * val budgeted = SearchControl.withBudget(budget)
  * assert(!budgeted.isCancelled && budgeted.workUsed == 0L)
  *
  * // 2. Timed: a real deadline on System.nanoTime(); the clock is read only at yield points.
  * val timed = SearchControl.untilDeadline(System.nanoTime() + 200_000_000L)
  *
  * // 3. Testable: an injected clock that is already past the deadline cancels at the first check and stays cancelled.
  * var now     = 100L
  * val expired = SearchControl.untilDeadline(deadlineNanos = 50L, clockNanos = () => now)
  * assert(expired.isCancelled)
  * now = 0L
  * assert(expired.isCancelled) // latched: a clock running backwards cannot revive the invocation
  *
  * // 4. Untimed: the clock closure is never invoked when there is no deadline.
  * val untimed = SearchControl(budget = None, clockNanos = () => sys.error("clock read on the untimed path"))
  * assert(!untimed.isCancelled)
  * ```
  *
  * @param budget
  *   the invocation's rules work budget, shared with every nested `BudgetedRules` query; `None` means unbounded work
  * @param deadlineNanos
  *   a `clockNanos` value at which the invocation is cancelled; [[SearchControl.NoDeadline]] disables the clock
  * @param clockNanos
  *   the clock the deadline is compared against; `System.nanoTime` by default
  */
final class SearchControl(
    val budget: Option[BudgetedRules.Budget],
    val deadlineNanos: Long = SearchControl.NoDeadline,
    clockNanos: () => Long = () => System.nanoTime()
):
  private var latched = false

  /** Whether a real deadline was supplied. The clock is read only when this is `true`. */
  val isTimed: Boolean = deadlineNanos != SearchControl.NoDeadline

  /** Whether the invocation is cancelled: the latch, then the budget, then the clock — and the first `true` latches.
    *
    * Cheap enough for the hottest yield point (a field read, an optional field read, and a clock read only on the timed
    * path), which is what lets a search check it between dice rolls rather than between root candidates.
    */
  def isCancelled: Boolean =
    latched || {
      val exhausted = budget match
        case Some(b) => b.exhausted
        case None    => false
      val fired = exhausted || (isTimed && clockNanos() - deadlineNanos >= 0L)
      if fired then latched = true
      fired
    }

  /** Cumulative rules work units admitted by the shared budget so far; `0` without a budget. */
  def workUsed: Long = budget match
    case Some(b) => b.used
    case None    => 0L

object SearchControl:
  /** Sentinel deadline for the untimed path: a clock value `System.nanoTime()` never reaches in practice. */
  val NoDeadline: Long = Long.MaxValue

  /** A control that never cancels: no budget, no deadline, no clock reads. Shareable because it has no state to latch.
    * This is what the untimed `findBestMove` entry points use.
    */
  val Unbounded: SearchControl = new SearchControl(None, NoDeadline, () => NoDeadline)

  /** A deadline-only control, the adapter the historical `findBestMove(state, deadlineNanos, random)` entry point maps
    * onto. The clock is injectable so deadline behaviour can be tested deterministically.
    */
  def untilDeadline(deadlineNanos: Long, clockNanos: () => Long = () => System.nanoTime()): SearchControl =
    new SearchControl(None, deadlineNanos, clockNanos)

  /** A deterministic, work-only control: cancels when `budget` is exhausted and never reads a clock. */
  def withBudget(budget: BudgetedRules.Budget): SearchControl =
    new SearchControl(Some(budget), NoDeadline, () => NoDeadline)

  /** A control with both limits: whichever of the budget and the deadline fires first cancels the invocation. */
  def apply(
      budget: Option[BudgetedRules.Budget],
      deadlineNanos: Long = NoDeadline,
      clockNanos: () => Long = () => System.nanoTime()
  ): SearchControl =
    new SearchControl(budget, deadlineNanos, clockNanos)

/** A [[TimeBudgetedSearch]] that can also run under a shared [[SearchControl]] — a deterministic work budget, a
  * deadline, or both — and that owns the root move fallback whenever the control cancels the search.
  *
  * The historical deadline entry point is kept as an adapter: `findBestMove(state, deadlineNanos, random)` is exactly
  * `findBestMove(state, SearchControl.untilDeadline(deadlineNanos), random)`, so existing callers (`JvmApi`, the
  * JavaScript facade, the arena) see no change.
  *
  * ## Fallback ownership
  *
  * Lower-level evaluators — chance nodes, decision nodes, nested rules queries — return [[SearchOutcome.Incomplete]]
  * when cancelled and never fabricate a move. The root is the only layer that owes the caller a legal turn, and it
  * chooses one in this order:
  *
  *   1. an immediate root king capture, scored [[SearchScoring.TerminalWinScore]] (taken before any search work);
  *   2. the best **completed** ([[SearchOutcome.Exact]]) root candidate — interrupted candidates are never ranked;
  *   3. when no candidate completed, the pre-ranker's top choice (or the first canonical legal turn when the
  *      implementation does not pre-rank);
  *   4. `None` only on a forced pass, when the position has no legal turn at all.
  *
  * Root legal-turn generation, the immediate-capture test and the pre-ranking that produce fallbacks 1 and 3 are
  * therefore part of the anytime contract and run even when the control is already cancelled at entry; the first
  * operation that cancellation skips is the first piece of *search* work (a root rescore batch, a chance-node expansion
  * or a cache lookup).
  */
trait ControlledSearch extends TimeBudgetedSearch:

  /** Finds the best full-turn path under `control`, honouring its budget and deadline at every yield point and applying
    * the fallback order documented on [[ControlledSearch]] when the search is cancelled.
    *
    * @param state
    *   the current [[dicechess.engine.domain.GameState]]; `state.activeColor` identifies the side to move
    * @param control
    *   the invocation's cancellation control; create a fresh one per call unless it is [[SearchControl.Unbounded]]
    * @param random
    *   the random source for tie-breaking among equal completed candidates
    * @return
    *   `Some(ScoredSequence)` whenever at least one legal turn exists, `None` only on a forced pass
    */
  def findBestMove(state: GameState, control: SearchControl, random: Random): Option[ScoredSequence]

  /** The historical deadline entry point, mapped onto a deadline-only [[SearchControl]]. */
  override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] =
    findBestMove(state, SearchControl.untilDeadline(deadlineNanos), random)
