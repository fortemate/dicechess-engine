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
excluded by `GameState.equals`. A mutable hash map resolves hash collisions with
equality; neither normalized DFEN nor a bare Zobrist hash is a substitute.

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

Memory is proportional to unique evaluated state/perspective pairs. Hashing and
comparison walk full state, including the mailbox. Callers bound the work;
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
| **Collision & replacement policy** | Lossless value equality on `mutable.HashMap` buckets; no eviction during the invocation | Fixed power-of-two array (`key & mask`), depth-preferred replacement (`depth >= oldDepth` or replacing a non-exact bound on the same key), 64-bit Hyatt XOR checksum (~$2^{-64}$ residual collision acceptance) |
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

`EvaluationCacheBenchmark` compares fixed batches with repeated equal states and
zero hits against the public deterministic evaluator. Each measured batch creates
a fresh cache; construction, hashing and storage are included. Use JMH `-prof gc`
for allocation overhead and divide auxiliary work totals by `batches` to recover
calls, hits and entries per invocation. This measures callback cost, not playing
strength or a universal speedup. Retain measured results according to the
repository publication boundary.
