---
title: Staged Downstream Adoption Guide
description: Wave-by-wave downstream adoption contract, minimum versions, external compatibility recipes, and fixed-work parity checklist for Epic #390.
sidebar:
  order: 19
---

Publishing reusable primitives in `dicechess-rules_3` and `dicechess-engine_3` does not remove duplicate traversal, pruning, or feature code in downstream consumers until those consumers migrate to the shared APIs. This page specifies the staged downstream adoption contract ([Issue #398](https://github.com/fortemate/dicechess-engine/issues/398), part of [Epic #390](https://github.com/fortemate/dicechess-engine/issues/390)) established in [Shared Traversal & Search Contracts §5.2](/dicechess-engine/architecture/search/14-traversal-and-search-contracts/#52-staged-adoption-sequence-392399).

:::note[Release & Publication Boundary]
Releases are **human-only** (`gh workflow run release.yaml` or `mise run release:prepare`) and are never executed automatically by an issue or pull request. Downstream repositories keep their own evaluator formulas, parameter values, candidate limits, selective reply filters, roll urgency heuristics, root blending weights, opening books, trained models, and arena verdicts in their owning repositories.
:::

---

## 1. API Group Matrix and Minimum Versions

All Maven (`com.fortemate:dicechess-rules_3`, `com.fortemate:dicechess-engine_3`) and npm (`@fortemate/dicechess-engine`, `@fortemate/dicechess-engine-wasm`) artifacts in a release share one lockstep `X.Y.Z` version. The table below records the minimum version and owning artifact for each reusable API group:

| Wave | API Group | Symbols (`dicechess.engine.search`) | Owning Artifact | Minimum Version | Implementation Guide |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Foundation** | Promotion-aware roll equivalence | `ActiveDicePartitioner`, `CompactRoll` | `dicechess-rules_3` | `0.15.0` ([#387](https://github.com/fortemate/dicechess-engine/issues/387)) | [Equivalent Dice Rolls](/dicechess-engine/architecture/search/13-equivalent-dice-rolls/) |
| **Foundation** | Work-bounded rules queries | `BudgetedRules.Budget`, `Outcome`, `turnPaths`, `kingCaptureRolls`, `kingCapturePath` | `dicechess-rules_3` | `0.14.9` / strict EP in `0.16.1` ([#316](https://github.com/fortemate/dicechess-engine/issues/316), [#323](https://github.com/fortemate/dicechess-engine/issues/323), [#430](https://github.com/fortemate/dicechess-engine/pull/430)) | [Budgeted Rules Queries](/dicechess-engine/architecture/rules-budgets/) |
| **Wave 1** | Canonical passed-pawn detector | `PassedPawns.bitboard`, `count`, `maxRank`, `countAndMaxRank` | `dicechess-engine_3` | Shipped since `0.11.0`; qualified in `0.16.1` ([#396](https://github.com/fortemate/dicechess-engine/issues/396)) | [Mobility & Passed Pawn Contracts](/dicechess-engine/architecture/search/09-mobility-and-passed-pawns/) |
| **Wave 1** | Attacker-type masks, roll availability & minimum piece price | `AttackerTypes`, `DiceRolls.rollsShowingAnyOf`, `probabilityOfAnyOf` (`rules`); `PieceValues` (`engine`) | `dicechess-rules_3` & `dicechess-engine_3` | Next release after `0.16.0` (`>= 0.16.1`, [#395](https://github.com/fortemate/dicechess-engine/issues/395)) | [Attacker Types & Piece Values](/dicechess-engine/architecture/search/17-attacker-types-and-piece-values/) |
| **Wave 1** | Invocation-local evaluation cache & TT boundary | `EvaluationCache` (`#295`) vs `TranspositionTable` (`#59`, `#397`) | `dicechess-engine_3` | Next release after `0.16.0` (`>= 0.16.1`, [#295](https://github.com/fortemate/dicechess-engine/issues/295), [#397](https://github.com/fortemate/dicechess-engine/issues/397)) | [Invocation-local Evaluation Cache](/dicechess-engine/architecture/search/evaluation-cache/) |
| **Wave 2** | Shared-prefix resulting-state visitors & folds | `TurnGenerator.forEachLegalTurnState`, `BudgetedRules.visitTurnStates`, `foldTurnStates`, `TurnVisitSummary`, `TurnFoldResult` | `dicechess-rules_3` | `0.16.0` (`forEachLegalTurnState`), `>= 0.16.1` with strict-EP budgeted visitors ([#392](https://github.com/fortemate/dicechess-engine/issues/392), [#430](https://github.com/fortemate/dicechess-engine/pull/430)) | [Shared Traversal & Search Contracts §2.2](/dicechess-engine/architecture/search/14-traversal-and-search-contracts/#22-canonical-turn-state-traversal-and-endturn-ownership-392) |
| **Wave 2** | Bounded search outcomes & cooperative cancellation | `SearchOutcome`, `SearchControl`, `UtilityBounds`, `ControlledSearch` | `dicechess-engine_3` | Next release after `0.16.0` (`>= 0.16.1`, [#393](https://github.com/fortemate/dicechess-engine/issues/393)) | [Bounded Search Outcomes & Cancellation](/dicechess-engine/architecture/search/15-search-outcomes-and-cancellation/) |
| **Wave 3** | Weighted chance-node accumulator & Star1/Star2 pruning | `ChanceNode`, `ChanceNode.Policy`, `ChanceNode.Replies`, `ChanceNode.Value`, `ChanceNode.Result` | `dicechess-engine_3` | Next release after `0.16.0` (`>= 0.16.1`, [#394](https://github.com/fortemate/dicechess-engine/issues/394)) | [Weighted Chance-Node Search](/dicechess-engine/architecture/search/16-weighted-chance-node-search/) |

During local pre-release verification inside a consumer checkout, run `mise run publish:local` in `dicechess-engine` to publish the current snapshot to local Ivy before switching to the next human-published Maven Central release.

---

## 2. Wave-by-Wave Downstream Migration Protocol

Adopt one wave per pull request in the downstream repository so that any score, tie-break, or performance shift is isolated to a single mechanism change.

### Wave 1 — Leaf & Feature Helpers (`#396`, `#395`, `#295`, `#397`)

1. **`PassedPawns.bitboard(state, color)` (`#396`)**:
   - Replace duplicate local front-span masks with `PassedPawns.bitboard(state, color)`.
   - Keep the consumer's rank-bonus table and weights in the consumer; iterate the bitboard with `java.lang.Long.numberOfTrailingZeros` so doubled pawns are scored individually without heap allocation.
2. **`AttackerTypes`, `DiceRolls.rollsShowingAnyOf`, and `PieceValues` (`#395`)**:
   - Replace local square-to-piece-type loops with `AttackerTypes.mask(state, targetSq, attackerColor)`. Because `AttackerTypes.mask` intersects `MoveGenerator.allAttackers` with exact piece bitboards, diagonal/orthogonal queens set only bit 4 (`Queen`) and never set bishop or rook bits.
   - Replace inline `1.0 - math.pow((6 - k) / 6.0, 3)` formulas with `AttackerTypes.availableRolls(mask)` (exact integer out of `216`) or `AttackerTypes.availabilityProbability(mask)`.
   - Replace custom cheapest-attacker loops with `prices.minimumOrElse(mask, emptySentinel)` over a consumer-owned `PieceValues` instance.
3. **`EvaluationCache` vs `TranspositionTable` (`#295`, `#397`)**:
   - Wrap deterministic `(GameState, Color) => Int` callbacks in a fresh per-invocation `new EvaluationCache(evalFn)` when scoring transposing root or reply paths without collapsing path multiplicity.
   - Never share `EvaluationCache` across threads or moves, and never merge it with `TranspositionTable` (which stores Zobrist-keyed subtree bounds `(value, depth, TTBound, lossTainted)`).

### Wave 2 — Canonical Turn-State Traversal & Cooperative Cancellation (`#392`, `#393`)

1. **Shared-prefix state visitors (`#392`)**:
   - Replace `TurnGenerator.generateAllLegalTurnPaths(state)` + manual `path.foldLeft(state)(_.makeMove(_))` replay loops with `TurnGenerator.forEachLegalTurnState` (unbudgeted) or `BudgetedRules.visitTurnStates` / `foldTurnStates` (budgeted).
   - **Verify `endTurn()` ownership**:
     - When `isKingCapture == true`, `afterState` is the state immediately after the capturing micro-move (`endTurn()` is **not** called, `afterState.activeColor == state.activeColor`).
     - When `isKingCapture == false`, `afterState` already has `endTurn()` applied **once** (`afterState.activeColor == state.activeColor.opponent`, `dicePool == Nil`). **Never call `.endTurn()` a second time** on `afterState`.
2. **`SearchOutcome` and `SearchControl` (`#393`)**:
   - Thread a per-invocation `SearchControl(budget, deadlineNanos, clockNanos)` through root candidate loops, chance nodes, and nested `BudgetedRules` calls.
   - Check `control.isCancelled` **before** every `EvaluationCache` or `TranspositionTable` lookup so an already-expired invocation returns `SearchOutcome.Incomplete` deterministically.
   - Preserve the 4-tier root fallback order in the consumer's root adapter:
     1. Immediate root king capture (`SearchScoring.TerminalWinScore`).
     2. Best completed (`SearchOutcome.Exact`) root candidate (with seeded `Random` tie-breaking).
     3. Pre-ranker top choice or first canonical legal turn when zero candidates completed.
     4. `None` only on a forced pass (`0` legal paths).

### Wave 3 — Weighted Chance-Node Search (`#394`)

1. **`ChanceNode.expand` adoption**:
   - Replace custom 56-roll `DiceRolls` loops and custom Star1/Star2 arithmetic with `ChanceNode.expand(afterTurn, bounds, control, alpha, beta, policy, order)`.
   - Supply consumer-specific behaviour through `ChanceNode.Policy`:
     - `lowestValue` and `highestValue` bounded by `UtilityBounds` (`terminalLoss` at MIN nodes unless opponent king capture is ruled out).
     - `probe(rolled)` returning sound bounds (`UpperBound` at MIN, `LowerBound` at MAX, `Exact` only for terminal king captures or forced passes). **Never use the static pass score `eval(rolled.endTurn())` as a bound when `rolled` has legal turns** (mandatory-move invariant, fixture `F9`).
     - `decide(rolled, classIndex, alpha, beta)` evaluating the class's replies (for example via `ChanceNode.Replies.generate(rolled, control)` or a consumer's selective reply policy).
   - If the consumer reorders dice classes by urgency, pass a caller-owned `order: Array[Int]` permutation allocated in invocation-local scratch; never mutate `ActiveDicePartitioner.partitionsFor(afterTurn)`.

### Wave 4 — Deduplication After Fixed-Work Parity (`#398`)

Delete duplicate downstream traversal, partition, and Star1/Star2 helpers **only after** fixed-work verification passes:

- [ ] **Fixed-work parity (`deadlineNanos = Long.MaxValue`)**: Under `SearchControl.Unbounded` and deterministic `SearchControl.withBudget(Budget(limit))`, compare the consumer's legacy implementation against the adopted shared implementation across synthetic and consumer-owned fixtures:
  - Identical selected legal path and score (or documented ULP-only difference when roll summation order intentionally changes).
  - Identical candidate multiplicity and shortlist size before random tie-breaking.
  - Identical post-call `scala.util.Random` state (`rng.nextLong()`) for every seed.
- [ ] **Cancellation & fallback parity (`F4`, `F7`, `F10`)**: Verify `Budget(0)`, `Budget(1)`, `Budget(exact - 1)`, `Budget(exact)`, and step-clock deadlines (including pre-cache-hit cancellation on warm `EvaluationCache` / `TranspositionTable` instances).
- [ ] **Concurrency isolation**: Run concurrent independent invocations on a multi-threaded executor and assert bit-exact parity with sequential runs.
- [ ] **Artifact & facade boundaries**: Confirm `rulesJVM/assertRulesBinaryCompatible`, `rulesSmoke/test` (`RulesOnlyConsumerSpec`), `.mise/lib/check-npm-package-entries.mjs`, and `JvmApi` Java callability pass without regression.

---

## 3. Externally Compiled Compatibility Example

The following end-to-end example compiles outside `package dicechess` (exercised on JVM, Scala.js, and WebAssembly in `shared/src/test/scala/consumer/StagedAdoptionParitySuite.scala`) and combines Waves 1–3 while keeping all evaluation weights and policies in the consumer:

```scala
import dicechess.engine.domain.*
import dicechess.engine.search.*
import scala.util.Random

// Consumer-owned prices and rank bonuses (Wave 1):
val prices      = PieceValues(pawn = 100, knight = 310, bishop = 330, rook = 500, queen = 900, king = 10_000)
val passedBonus = IArray(0, 5, 12, 25, 45, 80, 140)

def consumerEval(state: GameState, perspective: Color): Int =
  var pawns = PassedPawns.bitboard(state, perspective).value
  var bonus = 0
  while pawns != 0L do
    val sq   = java.lang.Long.numberOfTrailingZeros(pawns)
    val rank = sq / 8 + 1
    val adv  = if perspective.isWhite then rank - 1 else 8 - rank
    bonus += passedBonus(adv)
    pawns &= pawns - 1L
  val hanging = PieceSafety.hangingSquares(state, perspective)
  val risk    =
    if hanging.isEmpty then 0
    else
      val victimSq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(hanging.value))
      val mask     = AttackerTypes.mask(state, victimSq, perspective.opponent)
      (AttackerTypes.availableRolls(mask) * prices.minimumOrElse(mask, 0)) / DiceRolls.totalOrderedRolls
  Evaluator.evaluateMaterial(state, perspective) + bonus - risk

// Wave 2 + Wave 3: root traversal with EvaluationCache, SearchControl, and ChanceNode expansion:
val state   = FenParser.parse("4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP").toOption.get
val mover   = state.activeColor
val control = SearchControl.withBudget(new BudgetedRules.Budget(10_000L))
val cache   = new EvaluationCache(consumerEval)
val bounds  = ExpectimaxSearch.utilityBounds(mover)

val folded = BudgetedRules.foldTurnStates(
  state,
  Vector.empty[(List[Move], GameState, Boolean)],
  control.budget.get
) { (acc, path, afterState, isKingCapture) =>
  (acc :+ (path, afterState, isKingCapture), true)
}

val firstCandidateOpt = folded match
  case BudgetedRules.Outcome.Complete(res, _) =>
    if res.value.isEmpty then None else Some(res.value.head)
  case BudgetedRules.Outcome.Incomplete(_) => None
val (_, firstAfterTurn, isWin) = firstCandidateOpt.get
assert(!isWin && firstAfterTurn.activeColor == mover.opponent && firstAfterTurn.flags.isDicePoolEmpty)

// Check control.isCancelled before EvaluationCache lookup, then expand the opponent's chance node:
val rootStaticOutcome =
  if control.isCancelled then SearchOutcome.Incomplete(control.workUsed)
  else SearchOutcome.Exact(cache(firstAfterTurn, mover), control.workUsed)
val policy = new ChanceNode.Policy:
  def lowestValue: Double  = bounds.terminalLoss
  def highestValue: Double = bounds.maxNonTerminal
  def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
    ChanceNode.Replies.generate(rolled, control) match
      case None                                  => ChanceNode.Value.incomplete(control)
      case Some(replies) if replies.capturesKing => ChanceNode.Value.exact(bounds.terminalLoss, lossTainted = true)
      case Some(_) if control.isCancelled        => ChanceNode.Value.incomplete(control)
      case Some(replies) if replies.isForcedPass =>
        ChanceNode.Value.exact(cache(rolled.endTurn(), mover).toDouble, workUsed = control.workUsed)
      case Some(replies) =>
        val minReply = replies.states.map(cache(_, mover)).min.toDouble
        ChanceNode.Value.exact(minReply, workUsed = control.workUsed)

val chanceResult = ChanceNode.expand(
  firstAfterTurn,
  bounds,
  control,
  Double.NegativeInfinity,
  Double.PositiveInfinity,
  policy
)
assert(chanceResult.outcome.isExact && rootStaticOutcome.valueOption.exists(_ >= 0) && Random(0).nextInt(1) == 0)
```

---

## See Also

- [Shared Traversal & Search Contracts](/dicechess-engine/architecture/search/14-traversal-and-search-contracts/) — the reviewed architecture and fixture matrix (`F1`–`F10`)
- [Bounded Search Outcomes & Cooperative Cancellation](/dicechess-engine/architecture/search/15-search-outcomes-and-cancellation/) — `SearchOutcome`, `SearchControl`, `UtilityBounds`, and `ControlledSearch`
- [Weighted Chance-Node Search](/dicechess-engine/architecture/search/16-weighted-chance-node-search/) — `ChanceNode` and `ChanceNode.Policy`
- [Attacker Types & Piece Values](/dicechess-engine/architecture/search/17-attacker-types-and-piece-values/) — `AttackerTypes`, `DiceRolls`, and `PieceValues`
- [Invocation-local Evaluation Cache](/dicechess-engine/architecture/search/evaluation-cache/) — `EvaluationCache` and its boundary with `TranspositionTable`
- [Mobility and Passed Pawn Contracts](/dicechess-engine/architecture/search/09-mobility-and-passed-pawns/) — `PassedPawns.bitboard` qualification and rank-bonus adapter
- [Published Artifacts & Rules-Only Migration](/dicechess-engine/architecture/artifacts/) — Maven and npm artifact boundaries
