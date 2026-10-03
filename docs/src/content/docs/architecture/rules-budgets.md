---
title: Budgeted rules queries
description: Complete, witnessed and interrupted canonical rules traversal.
---

`dicechess.engine.search.BudgetedRules` is an additive Scala API in the rules artifact. Its queries share a caller-owned synchronous `Budget`, with cumulative work and sticky exhaustion. Existing complete rules methods and Java/JavaScript facade exports keep their contracts.

A work unit admits a move-generator invocation, an examined micro-move or an aggregate capture-roll iteration. One generator invocation is atomic. These units do not represent time, instructions or heap bytes: retain an external wall-clock and memory guard.

## Complete paths and aggregate capture rolls

`turnPaths(state, budget)` preserves the full canonical legal-turn order, including king-capture paths and maximal-dice normal paths. An empty complete list means a pass. Exhaustion withholds the buffered prefix.

`kingCaptureRolls(state, defender, budget)` computes the successful ordered-roll count for the defender's opponent. It replaces the incoming side/dice for that aggregate query. Incomplete traversal withholds a partial exact count.

## Current-dice capture witness

`kingCapturePath(state, budget)` preserves the incoming active color and remaining ordered dice. Its result is `Outcome[Option[List[Move]]]`:

- `Complete(Some(path), used)` gives the first canonical legal king-capture path. Discovery proves existence, so traversal can stop without generating other paths. A king capture is legal regardless of normal-turn maximality.
- `Complete(None, used)` proves no capture exists for the current side/dice. A missing opposing king also gives complete absence without new work.
- `Incomplete(used)` proves neither existence nor absence and exposes no path prefix.

A capture found on the last admitted micro-move is complete. An exhausted shared budget stays incomplete, including a later query with a missing target; it cannot be resumed as a fresh complete proof. Budgets should be scoped to the caller's intended synchronous operation, not shared globally or concurrently.

This query returns a witness for one turn, not an optimal move, full-game evaluation or search result. It is a Scala-shaped rules-core API cross-compiled for JVM, JavaScript and WebAssembly; it adds no Java/Kotlin or exported JavaScript facade method. Consumers must compile against an artifact version providing this method.
