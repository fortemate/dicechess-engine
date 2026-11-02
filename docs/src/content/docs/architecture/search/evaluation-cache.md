---
title: Invocation-local Evaluation Cache
description: Reuse deterministic Scala evaluations while preserving every candidate path.
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
