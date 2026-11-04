---
title: Traversal Prefixes Across Dice Classes
description: Invocation-local reuse of canonical turn prefixes, per-class maximality proofs, safe early exits, and explicit work accounting.
sidebar:
  order: 19
---

`TurnGenerator.SharedTraversal` reuses turn prefixes across the weighted classes of one fresh roll boundary ([Issue #442](https://github.com/fortemate/dicechess-engine/issues/442)). It lives in `dicechess-rules_3` and uses the existing piece generators, dice-consumption rules and packed turn-path representation. The partition weights, class order, per-class move order and path multiplicity keep their existing meaning. Search evaluators, utility bounds, selective policies and class visiting order belong to their callers.

## Ownership and lifetime

Create one traversal for a board and active color, reuse it for that board's classes, and discard it with the chance node. Its mutable cache belongs to one synchronous invocation and one thread. Nested or concurrent searches create independent traversals; a callback must use another instance if it starts a nested traversal.

Each cached prefix stores a dice-free intermediate board and its per-edge en-passant eligibility. Piece-type move lists are generated lazily at that prefix. Prefix identity includes the ordered moves: commuting paths remain distinct even when they reach equal boards. A board is retained for each requested ordered prefix at depths one and two; a third-move leaf is applied when its callback is emitted. This is not a two-board cache: its retained boards and move lists grow with the requested prefixes and live until the chance node is discarded.

`TurnGenerator.forEachLegalTurnState` keeps its existing single-roll discovery and replay implementation, with packed path buffers and at most two intermediate replay boards. Callers opt into `SharedTraversal` for cross-class reuse or streaming terminal discovery. `SingleRollTraversalBenchmark` compares these mechanisms on three-die rolls, including a branching queen position, with full and stop-first visits; use `-prof gc` and retain the raw evidence privately.

Each `visit` owns its remaining dice, packed normal paths, consumed-dice maximum and borrowed three-slot move array. Dice are consumed separately for every class, so a shared prefix never carries another class's residual dice. Promotion-retaining classes can generate moves for a newly created piece type. Castling requires and spends both King and Rook dice. Initial en-passant eligibility is removed when an eligible pawn moves, so another pawn cannot inherit it by entering that pawn's original square.

An empty roll or a completely explored non-empty roll with no legal paths completes with zero callbacks. The caller handles that forced pass with one `endTurn()` and preserves the class's full weight. Budget exhaustion or cancellation produces `Incomplete`, which cannot establish a pass.

## Legal emission and early stops

King captures are legal independently of maximal dice consumption. They stream in canonical depth-first discovery order. A callback returning `false` at a capture proves that the requested first result is legal and stops the remaining generation immediately. Search reply materialization can use that proof to settle a terminal class without exploring its other branches.

Normal paths wait until the entire class has been explored and the maximum achievable **dice consumed** has been established, including dice spent by king-capture paths. Only normal paths matching that maximum are then emitted, in their canonical order. A callback stop during normal emission skips subsequent result construction; it does not skip the earlier maximality proof. A partially explored branch is never treated as a completed maximal turn.

For every emitted result:

- A king capture applies the path without `endTurn()`, preserving the mover's active color.
- A normal maximal turn applies exactly one `endTurn()`, clearing dice, flipping the side and advancing Black's full-move counter.
- The resulting board and Zobrist hash equal plain sequential replay. Duplicate paths or states remain duplicate callbacks.
- The move array is borrowed and reused. Copy `moves.take(length).toList` inside the callback to retain a path.

`Complete(SharedVisitSummary(emittedPaths, stoppedEarly), used)` distinguishes a caller stop from an interrupted computation. It deliberately carries no total path count: an early capture stop may leave that total unknown. A callback returning `false` stops only its current class; later classes may still be visited on the same traversal.

## Work and cancellation

The new shared traversal has an explicit accounting contract. Its cumulative `TraversalWork` snapshot reports:

| Field | Count |
| :--- | :--- |
| `generated` | Actual per-piece move-generator calls on cache misses. A cache hit requires no generation admission. |
| `examined` | Micro-move examinations for each visited class, including cached moves whose class-specific dice consumption must be checked. |
| `transitions` | Actual `makeMove` calls during discovery and result construction. |
| `emitted` | Visitor callbacks, preserving path multiplicity. |
| `replayed` | The subset of `transitions` needed to apply an emitted leaf that has no cached intermediate board. |

One budget unit admits a per-piece generator call or an examined move; therefore newly admitted work is `generated + examined`. Cached geometry and dice-empty continuations require no generator call. `used` is cumulative on the supplied `BudgetedRules.Budget`, including previous queries that used that budget. Without a supplied budget it is the traversal's cumulative admitted work.

This differs deliberately from `BudgetedRules.visitTurnStates`, whose existing unit remains one full `generateMoves` invocation or one examined move. That API retains its full-discovery-before-callback contract, `totalLegalPaths`, and zero callbacks on incomplete discovery. A numerical limit therefore does not describe identical work in these two APIs.

The shared traversal checks its cancellation callback before generation, edge examination and emission. An individual piece-generation call, `makeMove` or visitor callback is indivisible. Interruption is sticky for that traversal. Proved results may already have been delivered before a later interruption, including normal results if cancellation occurs during replay; callers must discard accumulated results when `visit` returns `Incomplete`. Normal paths are never emitted from an incomplete discovery. A terminal or caller stop returns `Complete(..., stoppedEarly = true)` without another cancellation poll; the next visit checks cancellation before doing work.

Expectimax owns one shared traversal in each node's reply policy, including the probe and decision phases. Its deterministic work limits therefore follow the new admissions above. The standalone historical `Replies.generate` helper retains its budgeted API behavior. No evaluator callback, candidate policy, utility bound or class order moves into the traversal.

The Expectimax reply adapter checks a timed control at each class entry and amortizes real-clock reads across at most 64 internal traversal cancellation polls. Work-budget exhaustion is still checked at every admission, and expired controls cannot admit work in a warmed class. The counter belongs to that reply traversal; nested and concurrent nodes do not share it. An individual generator or callback remains non-preemptive, so this interval is not a wall-clock bound. Direct compositions passing `() => control.isCancelled` check the timed clock on every cancellation callback instead.

## Scala composition

```scala
import dicechess.engine.domain.FenParser
import dicechess.engine.search.{ActiveDicePartitioner, BudgetedRules, TurnGenerator}

val boundary = FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get
val budget = new BudgetedRules.Budget(10_000L)
val traversal = new TurnGenerator.SharedTraversal(boundary, Some(budget))
var weightedResults = 0L
var complete = true
for entry <- ActiveDicePartitioner.partitionsFor(boundary) if complete do
  var count = 0L
  traversal.visit(entry.roll) { (_, _, _, _) =>
    count += 1
    true
  } match
    case BudgetedRules.Outcome.Complete(summary, _) =>
      if summary.emittedPaths == 0 then count = 1 // the forced-pass successor
      weightedResults += entry.weight.toLong * count
    case BudgetedRules.Outcome.Incomplete(_) => complete = false
assert(complete && weightedResults > 0L)
```

In a controlled search, supply `control.budget` and `() => control.isCancelled` to the constructor. A policy owns the traversal alongside its per-class reply slots; it can request classes in its existing visiting order without changing `ChanceNode`'s probability or bound arithmetic. The rules-only consumer test compiles this additive API outside the rules project. The Java/Kotlin and JavaScript facades keep their existing signatures.

## Reproducible JMH qualification

`SharedClassTraversalBenchmark` compares independent calls to the unchanged `BudgetedRules.visitTurnStates` with a fresh `SharedTraversal` reused across all partition classes. Both variants run under an ample budget. Each has full-consumption and stop-at-first-result methods. Fixtures cover a sparse king-only board, all six friendly piece types, castling, two-step promotion, inherited en passant, and king captures.

Trial setup checks ordered paths, result states and capture flags for each class in both full and early-stop modes. An independent discovery probe supplies the legacy variant's generator, edge and `makeMove` counts; its replay probe models the existing two-slot cache. These probes run outside measurement. The shared variant reports its actual work snapshot. Auxiliary counters include `batches`, `generated`, `examined`, `transitions`, `replayed`, `results` and `forcedPasses`; divide each event total by `batches` for per-boundary work. Legacy `generated` counts full move-generator invocations, whereas shared `generated` counts per-piece invocations, as required by their different admission contracts.

```bash
sbt 'benchmark/Jmh/run -prof gc .*SharedClassTraversalBenchmark.*'
```

Cache construction and growth are inside each measured operation; partition lookup initialization and qualification probes are outside. Keep timing, allocation and comparative verdicts in the private knowledge base under **Shared algorithm migration performance qualification**. Public documentation records the mechanism and reproduction method. Primitive work savings do not establish timed-search throughput or playing strength.
