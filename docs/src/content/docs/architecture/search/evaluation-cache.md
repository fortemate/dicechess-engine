---
title: Invocation-local Evaluation Cache
description: Reuse deterministic Scala evaluations while preserving every candidate path, and keep exact evaluation memoization separate from TranspositionTable search bounds.
sidebar:
  order: 18
---

`dicechess.engine.search.EvaluationCache` is an opt-in Scala helper in the engine
artifact, cross-compiled for JVM, Scala.js and Wasm. It adds no rules-artifact or
Java/Kotlin/JavaScript facade surface and changes no built-in bot defaults.

Create one instance per search or rescoring invocation, bound to one pure,
deterministic `(GameState, Color) => Int` callback with stable configuration.
Keep inputs, including mailbox contents, immutable. The instance has a single
owner and is not thread-safe; do not share it across concurrent invocations,
models, profiles, moves or games. Stochastic rollouts and callbacks depending on
time, external history or changing configuration are unsuitable.

## Consumer example

This example is also compiled in Scaladoc and exercised from outside the engine
package by the shared integration suite:

```scala
import dicechess.engine.domain.{Color, GameState, Move}
import dicechess.engine.search.{EvaluationCache, ScoredSequence, SearchScoring}

def scoreCandidates(
    state: GameState,
    candidates: List[List[Move]],
    fullEval: (GameState, Color) => Int,
    mayContinue: () => Boolean
): List[ScoredSequence] =
  val cachedEval = new EvaluationCache(fullEval)
  candidates.iterator.takeWhile(_ => mayContinue()).map { moves =>
    SearchScoring.scorePath(state, moves, cachedEval.apply)
  }.toList
```

The consumer owns evaluator configuration, candidate bounds, deadline policy and
the fallback when no candidate is scored. Check cancellation or the deadline
before each cache access, including warm hits. The cache cannot interrupt a slow
callback or guarantee a hard deadline. Timed search may score more candidates and
choose a different legal path.

`SearchScoring.scorePath` continues to own move replay, `endTurn()` and evaluation
from the original mover's perspective. Immediate king capture returns the
terminal score without touching the cache. Its empty-path behavior is unchanged:
evaluate the current state from the current mover's perspective.

## Identity, telemetry and cost

Keys use full `(GameState, Color)` value equality: all piece bitboards, mailbox
contents, packed flags (including dice, active color, castling and half-move
clock), full en-passant targets and full-move number. The derived `zobristKey` is
excluded by `GameState.equals`. A primitive scalar fingerprint selects an
invocation-owned linear-probing table; full equality resolves every collision.
The fingerprint includes the bitboards, packed flags, complete en-passant targets,
full-move number and perspective. It deliberately omits the mailbox hash and
derived Zobrist field. Mailbox contents remain part of equality even for synthetic
states whose boards and mailbox disagree; neither normalized DFEN nor a bare
Zobrist hash is a substitute for exact identity.

FEN parsing and engine transitions keep mailbox contents consistent with the
piece bitboards. Arbitrary synthetic copies need not preserve that invariant;
mailbox-only variants intentionally share a scalar fingerprint and remain
distinct through equality. Consistent states can also have ordinary index
collisions. Lookups scan occupied slots, so inserting many colliding
values can require quadratic equality work. Resizing does not separate identical
fingerprints. The fingerprint has constant cost; the whole lookup has no
worst-case constant-time guarantee. Callers retain ownership of candidate and
input bounds.

| Property | Meaning |
| --- | --- |
| `evaluatorCalls: Long` | Actual callback attempts, including exceptions |
| `cacheHits: Long` | Successful lookups reusing an earlier result |
| `entryCount: Int` | Successfully evaluated state/perspective pairs retained |

All returned integers are cacheable. Exceptions propagate unchanged, leave no
entry and are retried on the next access. Existing successful entries survive.

Preserve candidate multiplicity, order, shortlist membership, tie-breaking and
RNG consumption. Memoization reuses scores; it does not deduplicate paths. With
an untimed deterministic evaluator, this preserves the chosen path, score and
next RNG value.

Memory is proportional to unique evaluated state/perspective pairs. Each lookup
computes one constant-work scalar fingerprint and allocates no tuple key. Only
comparison with equal scalar signatures needs full state equality, including the
mailbox; repeated references can use identity first. References, signatures and
values occupy parallel arrays without linked entry objects. Both halves of each
64-bit field are mixed before folding; final mixing cannot recover information
already lost by folding high and low halves together. One signature bit retains
the perspective, and slot selection uses the remaining bits.

A successful miss retains one entry. Growth moves existing references and values
without calling the evaluator or recomputing fingerprints. A second lookup after
the callback is required only if successful nested insertions changed the table.
Successful re-entry on the same key is updated with the outer callback's result;
an outer failure preserves nested successes and does not insert its failed result.
The state graph remains retained until the invocation's cache is discarded. Callers bound the work;
there is no TTL, LRU, process-global storage or cross-invocation reuse. This exact
evaluation memoization remains separate from search transposition-table bounds.

## Ownership boundary: `EvaluationCache` vs `TranspositionTable` (`#397`)

`EvaluationCache` ([#295](https://github.com/fortemate/dicechess-engine/issues/295)) and `TranspositionTable` ([#59](https://github.com/fortemate/dicechess-engine/issues/59)) cache different functions under incompatible correctness keys and remain strictly separate abstractions ([Shared Traversal & Search Contracts §4.2](/dicechess-engine/architecture/search/14-traversal-and-search-contracts/#42-cache-ownership-boundary-evaluationcache-295-vs-transpositiontable-59-397)). No third shared cache abstraction is warranted:

| Dimension | `EvaluationCache` ([#295](https://github.com/fortemate/dicechess-engine/issues/295)) | `TranspositionTable` ([#59](https://github.com/fortemate/dicechess-engine/issues/59)) |
| :--- | :--- | :--- |
| **Function cached** | Deterministic static evaluation `(GameState, Color) => Int` | Stochastic subtree search `(GameState, depth, alpha, beta, role) => SearchOutcome[Double]` |
| **Perspective & utility scale** | Explicit `perspective: Color` in the lookup key `(state, perspective)`; returns raw `Int` callback score | Entries stated for the search's single root `perspective` on its [`UtilityBounds`](/dicechess-engine/architecture/search/15-search-outcomes-and-cancellation/) scale; depth-3 inner `MAX` chance nodes XOR `MaxChanceKeySalt` into the key so `MAX` and `MIN` continuations on the same board never collide |
| **Identity key & counters** | Exact `(GameState, Color)` via `GameState.equals` (piece bitboards, 64-square mailbox, `flags` with `dicePool`, `castlingRights`, `halfMoveClock`, full `enPassant` bitboard, and `fullMoveNumber`; excludes derived `zobristKey`) | 64-bit [`Zobrist`](https://github.com/fortemate/dicechess-engine/blob/main/shared-rules/src/main/scala/dicechess/engine/domain/Zobrist.scala) hash (piece placement, `activeColor`, `castlingRights`, full `enPassant` bitboard, canonical `dicePool`) XORed with role salt; deliberately excludes `halfMoveClock` and `fullMoveNumber` |
| **Stored payload & bounds** | Exact `Int` score only | `TTEntry(key, value: Double, bound: TTBound, depth: Int, lossTainted: Boolean)` (`Exact`, `LowerBound`, `UpperBound`) |
| **Terminal & loss-taint handling** | `SearchScoring.scorePath` short-circuits immediate king capture with `TerminalWinScore` before touching the cache | Immediate root king capture short-circuits before probing; recursive chance nodes record `lossTainted = true` (at `UtilityBounds.terminalLoss`) when any opponent roll captures the root perspective's king, preserving the flag in `TTEntry` so [`RootRescore`](/dicechess-engine/architecture/search/06-expectimax-search/#7-root-rescoring-rootrescore) never blends a loss-tainted candidate on a table hit |
| **Collision & replacement policy** | Lossless full value equality on a scalar-indexed linear-probing table; no eviction during the invocation | Fixed power-of-two array (`key & mask`), depth-preferred replacement (`depth >= oldDepth` or replacing a non-exact bound on the same key), 64-bit Hyatt XOR checksum (~$2^{-64}$ residual collision acceptance) |
| **Lifetime, memory & concurrency** | Per-invocation, single-thread, heap grows with unique evaluated `(GameState, Color)` pairs, discarded when the call returns | Caller-owned fixed primitive arrays (zero GC pressure on hot paths), racy-reader safe (lockless XOR checksum, single writer recommended), reusable across moves/plies for one fixed evaluator, model, `UtilityBounds` scale, and `RootRescore` configuration; call `clear()` or replace when configuration changes |
| **Cancellation & incomplete values** | Caller checks deadline / `SearchControl.isCancelled` **before** calling `apply` (including warm hits); exceptions propagate without writing an entry | Caller checks `SearchControl.isCancelled` **before** `probe`; `SearchOutcome.Incomplete` carries no score and is never passed to `store` |

### Why incomplete values cannot be stored and cancellation precedes lookup

1. **No partial expectations in `TranspositionTable`:** When a `SearchControl` budget or deadline interrupts a chance node mid-roll, the partial weighted sum covers only an arbitrary prefix of the 216 dice outcomes. `SearchOutcome.Incomplete` omits a score field so an interrupted calculation cannot be stored as `TTBound.Exact`, `LowerBound`, or `UpperBound` and corrupt subsequent lookups.
2. **Pre-lookup cancellation check (`F10`):** Both `EvaluationCache` and `TranspositionTable` require checking `SearchControl.isCancelled` (or the caller's deadline predicate) *before* probing the cache. Otherwise, an already-expired invocation could continue making progress on warm cache hits and violate deterministic work-budget or deadline boundaries.

### Compiled boundary example (`F6`, `F8`, `F10`)

The following example (also compiled in `TranspositionTable` Scaladoc and verified in `TranspositionTableSpec`) contrasts counter-sensitive `EvaluationCache` keys with counter-blind `TranspositionTable` Zobrist keys and pre-probe cancellation:

```scala
import dicechess.engine.domain.{Color, FenParser}
import dicechess.engine.search.{
  EvaluationCache,
  Evaluator,
  SearchControl,
  SearchOutcome,
  SearchScoring,
  TTBound,
  TranspositionTable,
  TurnGenerator
}

// F8: commuting pawn pushes (a2a3 -> h2h3 vs h2h3 -> a2a3) reach the exact same post-endTurn() GameState.
val f8    = FenParser.parse("4k3/8/8/8/8/8/P6P/4K3 w - - 0 1 PP").toOption.get
val paths = TurnGenerator.generateAllLegalTurnPaths(f8)
val eval  = new EvaluationCache(Evaluator.evaluateMaterial)

// EvaluationCache reuses the static score across transposing paths while keeping both candidate paths:
val scored = paths.map(path => SearchScoring.scorePath(f8, path, eval.apply))
assert(scored.size == paths.size && eval.evaluatorCalls < paths.size)

// Counter boundary: Zobrist ignores fullMoveNumber/halfMoveClock, whereas EvaluationCache distinguishes them.
val afterTurn = paths.head.foldLeft(f8)(_.makeMove(_)).endTurn()
val laterTurn = afterTurn.copy(fullMoveNumber = afterTurn.fullMoveNumber + 2)
assert(afterTurn != laterTurn && afterTurn.zobristHash == laterTurn.zobristHash)

// F10: check SearchControl.isCancelled BEFORE probing TranspositionTable, and never store Incomplete.
val f10 = FenParser.parse("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3 NBP").toOption.get
val tt  = new TranspositionTable(1 << 10)
tt.store(f10.zobristHash, value = 125.0, bound = TTBound.Exact, depth = 2, lossTainted = false)

def probeDepth2(control: SearchControl): SearchOutcome[Double] =
  if control.isCancelled then SearchOutcome.Incomplete(control.workUsed)
  else
    tt.probe(f10.zobristHash) match
      case Some(entry) if entry.depth == 2 && entry.bound == TTBound.Exact =>
        SearchOutcome.Exact(entry.value, control.workUsed)
      case _ =>
        SearchOutcome.Incomplete(control.workUsed)

val expired = SearchControl.untilDeadline(deadlineNanos = 0L, clockNanos = () => 1L)
assert(probeDepth2(SearchControl.Unbounded) == SearchOutcome.Exact(125.0, 0L))
assert(probeDepth2(expired) == SearchOutcome.Incomplete(0L))
```

## Adoption and measurement

A downstream Scala bot can replace an equivalent local wrapper after adopting
an engine version containing this helper, while keeping its own evaluator and
configuration. Downstream migration and release publication are separate work.

`EvaluationCacheBenchmark` compares uncached callbacks, the original tuple-key
`HashMap`, and the optimized cache on fixed batches with repeated equal states
and zero hits. Both the public aggressive evaluator and an inexpensive
deterministic counter callback are included; trial setup checks value and
telemetry parity before timing. Each measured batch creates a fresh cache;
construction, hashing and storage are included. Use JMH `-prof gc`
for allocation overhead and divide auxiliary work totals by `batches` to recover
calls, hits and entries per invocation. This measures callback cost, not playing
strength or a universal speedup. Retain measured results according to the
repository publication boundary.

`NestedChanceCacheBenchmark` complements the isolated cache batch. Trial setup
freezes canonical outer successors of synthetic sparse, middlegame,
transposition-rich and promotion/en-passant positions. Each measured operation
fully expands the nested chance boundaries with fresh traversal and evaluation
caches, using either material valuation or a constant callback. Setup checks the
score against independent exhaustive path generation. Compare the identical
compiled harness against the published and candidate engine/rules jars on
separate classpaths, with the same JVM, heap, CPU allocation and fork plan.
Auxiliary counters report actual evaluator calls, hits, entries, geometry calls,
class edges, transitions, replay and emissions. Lookup comparisons, collision
distributions and growth require separate instrumentation; do not infer them
from callback misses. Keep unprofiled timings separate from allocation/GC
profiling and retain both privately. This batch models nested expansion; complete
downstream search and deadline qualification remain separate acceptance gates.
