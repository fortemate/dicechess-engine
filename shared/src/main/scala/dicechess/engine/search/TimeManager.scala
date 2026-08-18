package dicechess.engine.search

/** A snapshot of the moving side's game clock, in **milliseconds**.
  *
  * This is the input to time management — everything [[TimeManager]] needs to turn a game clock into a per-turn
  * thinking budget. The unit of time is a full Dice Chess *turn* (1–3 micro-moves searched together), and the Fischer
  * increment is credited per turn.
  *
  * @param remainingMs
  *   time left on the moving side's clock before this turn, in milliseconds
  * @param incrementMs
  *   per-turn Fischer increment in milliseconds (`0` for sudden death)
  * @param moveNumber
  *   the full-move number (the DFEN 6th field); used by the selected moves-to-go policy
  * @param movesToGo
  *   explicit moves-to-go for a tournament-style control, when known; otherwise [[TimeManager]] estimates it
  */
final case class ClockState(
    remainingMs: Long,
    incrementMs: Long,
    moveNumber: Int,
    movesToGo: Option[Int] = None
)

/** The result of [[TimeManager.budget]]: the ideal per-turn target and the hard ceiling it was clamped to.
  *
  * @param targetMs
  *   the time the bot should aim to spend on this turn
  * @param hardCapMs
  *   the absolute per-turn ceiling (a fraction of the remaining clock) — the bot must never aim above this, so a single
  *   turn can never flag the clock
  */
final case class TimeBudget(targetMs: Long, hardCapMs: Long)

/** Pure, platform-agnostic time manager for the bots' game clock.
  *
  * This is **layer (a)** of time control: it maps a [[ClockState]] to a per-turn budget in milliseconds. **Layer (b)**
  * — the actual search under a deadline — stays untouched in [[TimeBudgetedSearch]]; the caller turns the budget
  * returned here into a `System.nanoTime` deadline. Keeping the manager here (rather than in each consumer) means the
  * JS API, the offline arena, and any future server share one tested implementation instead of re-inventing it.
  *
  * The policy is pure `Long`/`Double` math — no clocks, no randomness — so it is identical on the JVM and Scala.js and
  * is exhaustively unit-testable without wall-clock flakiness (see [[TimeBudgetedSearch]] for why the *search* path
  * cannot be).
  *
  * ## How the budget is derived
  *
  * From the spendable time (remaining minus a safety reserve) and an estimated moves-to-go:
  *
  * ```scala sc:nocompile
  * reserve = max(ReserveFloorMs, ReserveFraction * remaining)
  * spendable = max(0, remaining - reserve)
  * target = increment + spendable / movesToGo // increment == 0 ⇒ sudden death
  * ```
  *
  * then clamped to `[MinThinkMs, hardCap]` where `hardCap = MaxFraction * remaining`, with a tighter panic clamp once
  * `spendable` falls below [[TimeManager.PanicThresholdMs]].
  *
  * ## Behaviour across controls
  *
  *   - **Sudden death (1+0):** target ≈ `spendable / movesToGo`, decaying as the clock drains; near zero the bot
  *     blitzes legal moves (panic floor) instead of flagging.
  *   - **Fischer (10+10):** the `increment` term keeps per-turn time high while the clock is healthy. As `remaining`
  *     shrinks the `hardCap` (a fraction of the remaining clock) takes over and throttles the target below the
  *     increment — deliberately conservative, since the increment is only credited *after* the turn completes, so a bot
  *     that overspends now can still flag before it is refunded. This trades a little time-utilisation for a guarantee
  *     of never losing on time; the constants are tunable.
  *
  * @param policy
  *   the configurable moves-to-go policy; safety limits remain common to every policy
  */
final class TimeManager(val policy: TimePolicy):

  import TimeManager.*

  /** Estimated number of further turns the moving side must play, used to spread the spendable time.
    *
    * Honours an explicit [[ClockState.movesToGo]] when present (clamped to at least 1 to stay division-safe); otherwise
    * delegates to [[policy]].
    */
  def movesToGo(clock: ClockState): Double =
    val estimate = clock.movesToGo.map(_.toDouble).getOrElse(policy.estimateMovesToGo(clock))
    if estimate.isFinite then math.max(1.0, estimate) else 1.0

  /** Computes the ideal per-turn [[TimeBudget]] for the given clock. Pure; see the class docs for the formula. */
  def budget(clock: ClockState): TimeBudget =
    val reserve   = math.max(ReserveFloorMs, (ReserveFraction * clock.remainingMs).toLong)
    val spendable = math.max(0L, clock.remainingMs - reserve)
    val rawTarget = (clock.incrementMs + spendable / movesToGo(clock)).toLong
    val hardCap   = math.max(MinThinkMs, (MaxFraction * clock.remainingMs).toLong)
    val capped    = math.min(math.max(rawTarget, MinThinkMs), hardCap)
    val target    =
      if spendable <= PanicThresholdMs then math.max(MinThinkMs, math.min(capped, PanicBudgetMs))
      else capped
    TimeBudget(target, hardCap)

  /** The conservative budget (in ms) the caller should turn into a search deadline.
    *
    * Subtracts the caller's transport/granularity `overheadBufferMs` from the target — the bot cannot interrupt an
    * in-flight rollout, and a worker round-trip adds latency, so the deadline is set short of the real allocation. The
    * buffer is caller knowledge (≈50 ms in-process, ≈150 ms across a Web Worker), hence a parameter rather than a
    * constant. Never returns less than [[TimeManager.MinThinkMs]].
    *
    * @param clock
    *   the moving side's clock snapshot
    * @param overheadBufferMs
    *   slack subtracted from the target to absorb rollout-overrun and transport latency
    */
  def budgetMs(clock: ClockState, overheadBufferMs: Long): Long =
    val b = budget(clock)
    math.max(MinThinkMs, math.min(b.targetMs, b.hardCapMs) - overheadBufferMs)

/** Shared safety constants plus source-compatible access to the default time manager.
  *
  * Existing `TimeManager.budget(...)` callers continue to use [[default]], while consumers that need an explicit policy
  * construct an instance with `TimeManager(policy)`.
  */
object TimeManager:

  /** Fixed reserve protecting short clocks from transport and scheduling latency that does not scale with clock size.
    */
  val ReserveFloorMs: Long = 300L

  /** Scaled reserve protecting long clocks and long-game tails; whichever is larger wins over [[ReserveFloorMs]]. */
  val ReserveFraction: Double = 0.05

  /** Original opening horizon retained so `legacy-linear-v1` reproduces the pre-policy allocation exactly. */
  val BaseMovesToGo: Int = 30

  /** Original horizon floor retained for compatibility and to stop the legacy taper spending the clock in one turn. */
  val MinMovesToGo: Int = 12

  /** Policy-independent ceiling that preserves enough clock for unexpectedly long games. */
  val MaxFraction: Double = 0.20

  /** Absolute floor preventing rounding, caller overhead, or an empty spendable clock from producing a zero deadline.
    */
  val MinThinkMs: Long = 20L

  /** Spendable-time boundary where survival becomes more important than the policy's normal quality allocation. */
  val PanicThresholdMs: Long = 2000L

  /** Absolute panic cap that stretches the last seconds over several turns even when increment or policy says spend. */
  val PanicBudgetMs: Long = 200L

  /** The default manager. Its policy changes only through an intentional compatibility decision. */
  val default: TimeManager = TimeManager(TimePolicies.default)

  /** Constructs an explicitly configured manager, avoiding mutable global policy state and allowing two participants
    * with different policies to share one process safely.
    */
  def apply(policy: TimePolicy): TimeManager = new TimeManager(policy)

  /** Compatibility facade for [[default]]. */
  def movesToGo(clock: ClockState): Int = default.movesToGo(clock).toInt

  /** Compatibility facade for [[default]]. */
  def budget(clock: ClockState): TimeBudget = default.budget(clock)

  /** Compatibility facade for [[default]]. */
  def budgetMs(clock: ClockState, overheadBufferMs: Long): Long = default.budgetMs(clock, overheadBufferMs)
