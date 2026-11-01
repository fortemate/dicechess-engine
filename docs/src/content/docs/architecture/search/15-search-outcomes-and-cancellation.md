---
title: Bounded Search Outcomes & Cooperative Cancellation
description: SearchOutcome, SearchControl and UtilityBounds — the shared result, cancellation and utility-scale contracts of stochastic search, and how ExpectimaxSearch adopts them.
sidebar:
  order: 15
---

A reusable stochastic search must keep three results apart: an expectation it finished computing, a bound it proved before a cutoff, and a partial accumulation that a deadline or work budget interrupted. The first two are comparable within their stated direction; the third is not comparable with anything. This page documents the three public contracts that make that distinction explicit — `SearchOutcome`, `SearchControl` and `UtilityBounds` in `dicechess.engine.search` (artifact `dicechess-engine_3`) — and the way `ExpectimaxSearch` adopts them ([Issue #393](https://github.com/fortemate/dicechess-engine/issues/393), part of [Epic #390](https://github.com/fortemate/dicechess-engine/issues/390)). The design was reviewed on the [Shared Traversal & Search Contracts](/dicechess-engine/architecture/search/14-traversal-and-search-contracts/) page; this page is the implementation guide.

Existing serving defaults are unchanged: `SearchAlgorithm.findBestMove`, `TimeBudgetedSearch.findBestMove(state, deadlineNanos, random)`, the Java/Kotlin (`JvmApi`) and JavaScript (`DiceChess`, `EngineFacade`) facades and every built-in bot behave exactly as before.

## 1. `SearchOutcome[+A]`

```scala
enum SearchOutcome[+A] derives CanEqual:
  case Exact(value: A, workUsed: Long)
  case LowerBound(value: A, workUsed: Long) // proven true value >= value (fail-high, driven strictly > beta)
  case UpperBound(value: A, workUsed: Long) // proven true value <= value (fail-low, driven strictly < alpha)
  case Incomplete(workUsed: Long)           // cancelled: carries no value at all
```

| Case | Meaning | May be ranked? | May be cached? |
| :--- | :--- | :--- | :--- |
| `Exact` | The true value of the node is `value`. | Yes, against other `Exact` values. | Yes (`TTBound.Exact`). |
| `LowerBound` | The true value is **at least** `value`; the node was driven strictly above `beta`. | No — but it settles a MAX parent's window. | Yes, as a lower bound. |
| `UpperBound` | The true value is **at most** `value`; the node was driven strictly below `alpha`. | No — but it settles a MIN parent's window. | Yes, as an upper bound. |
| `Incomplete` | The invocation was cancelled before a value or bound was proven. | Never. | Never. |

Three decisions carry the contract:

- **No score on `Incomplete`.** An interrupted expectation covers an arbitrary prefix of the roll order. Omitting the value field means a cancelled candidate cannot be ranked, blended with a root rescore or written to a transposition table by accident — the compiler refuses to read a score from it. `valueOption` and `exactOption` return `None` for it.
- **Strict cutoffs.** Fail-low needs `< alpha` and fail-high needs `> beta`, never `<=`/`>=`. A root candidate whose bound exactly ties the best completed score may still *equal* the best, so it must reach `Exact` and the seeded random tie-break instead of vanishing into a bound.
- **One perspective.** Every value is expressed for the single `perspective` colour the search was asked for (section 3); nothing is negated on its way up the tree.

`map(f)` keeps the outcome kind and is sound only for an order-preserving `f`; `mapReversing(f)` swaps `LowerBound` and `UpperBound` for an order-reversing `f` such as a perspective flip. Neither can turn a bound into a false proof as long as the caller picks the one matching its transform.

`workUsed` is the invocation's cumulative [`BudgetedRules.Budget`](/dicechess-engine/architecture/rules-budgets/) count at the moment the outcome was produced — rules work units (generator calls, examined micro-moves, capture-roll iterations), not time or bytes — and `0` when the search ran without a budget. `SearchOutcome.fromRules` lifts a `BudgetedRules.Outcome` into this vocabulary (`Complete → Exact`, `Incomplete → Incomplete`); rules queries never produce one-sided bounds.

## 2. `SearchControl` and the cancellation check order

```scala
final class SearchControl(
    val budget: Option[BudgetedRules.Budget],
    val deadlineNanos: Long = SearchControl.NoDeadline,
    clockNanos: () => Long = () => System.nanoTime()
):
  def isCancelled: Boolean
  def workUsed: Long
  val isTimed: Boolean

object SearchControl:
  val NoDeadline: Long = Long.MaxValue
  val Unbounded: SearchControl
  def untilDeadline(deadlineNanos: Long, clockNanos: () => Long = () => System.nanoTime()): SearchControl
  def withBudget(budget: BudgetedRules.Budget): SearchControl
```

One `SearchControl` describes one synchronous invocation: an optional deterministic work budget, an optional cooperative deadline, and a latch.

1. **Check order and latching.** `isCancelled` reports the latch, then `budget.exhausted`, then `clockNanos() >= deadlineNanos` — a direct comparison, so an extreme deadline such as `Long.MinValue` cannot overflow the way the subtractive `now - deadline >= 0` idiom does. The first `true` latches for the rest of the invocation: a search never observes a cancelled control becoming live again, and a clock adjusted backwards cannot revive it. An already-exhausted budget or an already-passed deadline cancels at the very first check.
2. **Zero-syscall untimed path.** When `deadlineNanos == Long.MaxValue` the clock is never read. The reproducible path — the seeded arena, fixed-work parity tests, JMH — pays nothing for the timed path's existence. `SearchControl.Unbounded` is the shared instance the untimed `findBestMove` entry points use.
3. **Shared budget.** The same `Budget` is handed to every nested `BudgetedRules` query. Exhaustion inside one roll's reply generation cancels the whole invocation at the next check, deterministically and without a clock. Budget counts are therefore the tool for fixed-work parity tests, and the injectable `clockNanos` is the tool for testing deadline behaviour without elapsed-time assertions (a clock that advances by one per read turns "the n-th check cancels" into an exact statement).
4. **Check points.** A search consults `isCancelled`:
   - before the first piece of *search* work at entry (the root rescore batch or the first chance node);
   - between root candidates;
   - between the dice rolls of every chance node, at every depth;
   - inside nested `BudgetedRules` queries, through the shared `Budget`;
   - immediately after an indivisible reply generation or batch evaluator call, before its result is used;
   - **before every cache-hit path** — the `TranspositionTable` probe of a root or recursive chance node — so an already-cancelled invocation returns `Incomplete` deterministically instead of being rescued by a hit.
5. **Atomic, uninterruptible operations.** A single `MoveGenerator.generateMoves` call, a single scalar `eval(state, color)` and a single batched `evalBatch(states, color)` call are atomic. Neither a work unit nor a deadline preempts inside them, and nothing here bounds their heap use. Hosts that run untrusted or very expensive evaluators keep external wall-clock and memory supervision around the whole `findBestMove` call.
6. **Thread ownership.** `Budget`, `SearchControl`, visitor buffers and the per-invocation scratch of a search are single-invocation, single-thread objects: create one per call and let it go. `SearchAlgorithm` instances remain safe for concurrent *independent* calls; `TranspositionTable` keeps its racy-reader contract.

## 3. `UtilityBounds`: perspective and terminal dominance

```scala
final case class UtilityBounds(
    perspective: Color,
    terminalLoss: Double,
    minNonTerminal: Double,
    maxNonTerminal: Double,
    terminalWin: Double
)
```

The constructor enforces `terminalLoss < minNonTerminal <= maxNonTerminal < terminalWin`. A decision node is **MAX** when its mover is `perspective` and **MIN** when its mover is `perspective.opponent` (`isMaximizing`); search windows and chance expectations are stated for `perspective` only. The two sentinels dominate every non-terminal evaluation, so a lost line always ranks last, a won line always first, and Star1/Star2 pruning can bound the unexpanded probability mass of a chance node by the scale's edges. At a MIN chance node the pessimistic per-roll bound is `terminalLoss`, not `minNonTerminal`, until opponent king capture on the unexpanded rolls has been ruled out.

`ExpectimaxSearch.utilityBounds(perspective)` declares the scale the expectimax tree reasons on: `terminalLoss = -1e9`, non-terminal range `[-10000, 10000]`, `terminalWin = 10001`. Leaf evaluators plugged into the search must stay inside the non-terminal range; a value the search has to clamp is an evaluator bug. The root is the one place the scale is not used: an immediate root king capture short-circuits with `SearchScoring.TerminalWinScore` (`Int.MaxValue`) before pre-ranking or any chance expansion, as every other `SearchAlgorithm` reports a winning turn.

## 4. Adapters and fallback ownership

`ControlledSearch` extends `TimeBudgetedSearch` with `findBestMove(state, control: SearchControl, random)` and supplies the historical deadline entry point as an adapter: `findBestMove(state, deadlineNanos, random)` is exactly `findBestMove(state, SearchControl.untilDeadline(deadlineNanos), random)`. `ExpectimaxSearch` and `OnnxExpectimaxSearch` implement it; `MonteCarloSearch` keeps its own deadline slicing for now.

Lower-level evaluators — chance nodes, decision nodes, nested rules queries — return `Incomplete` when cancelled and never fabricate a move. The root is the only layer that owes the caller a legal turn, and it chooses one in this order:

1. an immediate root king capture, scored `SearchScoring.TerminalWinScore`;
2. the best **completed** (`Exact`) root candidate — interrupted candidates are never ranked;
3. when no candidate completed, the pre-ranker's top choice (an implementation without a pre-ranker plays the first canonical legal turn);
4. `None` only on a forced pass, when the position has no legal turn at all.

Root legal-turn generation, the immediate-capture test and the pre-rank batch produce fallbacks 1 and 3, so they run even when the control is already cancelled at entry and are not charged to the budget; the first operation cancellation skips is the first piece of search work. `RootSearchStats` reports the result: `candidatesAbandoned` counts the (at most one) candidate the control cut mid-expansion, `deadlineTruncated` says the selected set was not exhausted — by the deadline or the budget — and `fellBackToPreRank` says the pre-ranker decided alone.

### Compiled example

```scala
import dicechess.engine.domain.FenParser
import dicechess.engine.search.{BudgetedRules, Evaluator, ExpectimaxSearch, SearchControl}
import scala.util.Random

val search = ExpectimaxSearch((states, color) => states.map(Evaluator.evaluateMaterial(_, color)))
val state  = FenParser.parse("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP").toOption.get

// Deterministic: the same budget bounds every nested reply generation; reproducible on any machine.
val budget = new BudgetedRules.Budget(50_000)
val fixed  = search.findBestMove(state, SearchControl.withBudget(budget), Random(7))

// Timed: identical to the historical deadline entry point.
val timed = search.findBestMove(state, SearchControl.untilDeadline(System.nanoTime() + 100_000_000L), Random(7))

// Both: whichever limit fires first cancels; the result is always a legal turn unless the position is a forced pass.
val both = search.findBestMove(
  state,
  SearchControl(Some(new BudgetedRules.Budget(50_000)), System.nanoTime() + 100_000_000L),
  Random(7)
)
```

## 5. Fixture validation

The implementation is validated against the public synthetic fixtures of the contracts page; every assertion uses a deterministic work limit or an injected clock, never elapsed time.

| Fixture | Position | What #393 verifies |
| :--- | :--- | :--- |
| **F4** | `4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1 PPP` | A forced pass is `None` under every control (unbounded, exhausted budget, expired deadline) with an all-zero `RootSearchStats`; as a chance node, the blocked roll keeps its full forced-pass weight under a budget. |
| **F7** | `8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP` | `Nb3xc5` is played and scored `TerminalWinScore` even when the control is already cancelled at entry; the root's own legal-turn generation charges nothing to the budget. |
| **F10** | `r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP` | `Budget(exact)` reproduces the unbounded move and telemetry with the budget spent but not exhausted; `Budget(exact - 1)` abandons exactly one candidate; `Budget(0)` and `Budget(1)` fall back to the pre-ranker; a warm transposition table does not rescue an already-cancelled invocation (zero `ttHits`). Depth 3 runs the same boundaries on the sparse `k7/8/8/8/8/8/8/R3K3 w - - 0 1 NBK` ending, whose full F10 tree would take minutes. |

The strict en-passant eligibility of [#419](https://github.com/fortemate/dicechess-engine/issues/419) was extended to `BudgetedRules` in the same change set, so a budgeted and an unbudgeted search expand identical trees until the budget runs out.

## See Also

- [Shared Traversal & Search Contracts](/dicechess-engine/architecture/search/14-traversal-and-search-contracts/) — the reviewed design this page implements
- [Budgeted rules queries](/dicechess-engine/architecture/rules-budgets/) — the `Budget` and `Outcome` the control shares with the rules layer
- [Expectimax Search Engine](/dicechess-engine/architecture/search/06-expectimax-search/) — the search that adopts these contracts
- [Time Management](/dicechess-engine/architecture/search/05-time-management/) — turning a clock into a deadline
