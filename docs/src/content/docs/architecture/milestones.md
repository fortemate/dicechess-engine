---
title: Project Status & Roadmap
description: Implemented engine capabilities, recent integration changes and the remaining roadmap, with links to release notes and live GitHub tracking.
---

The engine is a reusable rules and search library for JVM, JavaScript and WebAssembly.
For application setup, start with [Build with the Engine](/dicechess-engine/guides/integrations/).

This overview was checked against **v0.13.0** and the repository on **28 September 2026**.
[Release notes](https://github.com/fortemate/dicechess-engine/releases) are the source for
versioned changes; [GitHub milestones](https://github.com/fortemate/dicechess-engine/milestones)
and [open issues](https://github.com/fortemate/dicechess-engine/issues) track ongoing work.
Historical milestone names are planning labels, not the current package version.

## Implemented capabilities

| Area | Available now | Details |
| --- | --- | --- |
| Rules | Bitboard move generation, dice pools, complete legal turns, king capture, castling, promotion and en passant | [Turn lifecycle](/dicechess-engine/architecture/turn-lifecycle/) |
| State | DFEN, board symmetry, canonicalization and Zobrist hashing | [DFEN](/dicechess-engine/architecture/dice-chess-fen/), [symmetry](/dicechess-engine/architecture/board-symmetry/) |
| Portable APIs | JVM artifacts, Java/Kotlin facade, JavaScript and WasmGC packages | [Integration guide](/dicechess-engine/guides/integrations/) |
| Bots and evaluation | Six built-in bots, exact king-capture probability, Monte-Carlo equity, opening-book support and time management | [Search and bots](/dicechess-engine/architecture/search/01-primitive-search/) |
| Configurable search | Two- or three-ply expectimax, Star1/Star2 pruning, transposition caching and batched leaf evaluation | [Expectimax](/dicechess-engine/architecture/search/06-expectimax-search/) |
| JVM model integration | ONNX-backed search and manifest/graph validation through the model serving contract | [Model contract](/dicechess-engine/architecture/search/10-model-contract/) |
| Validation | Shared JVM/JS/Wasm rule tests, generated visual fixtures, scenario evaluation, arenas and benchmarks | [Testing](/dicechess-engine/architecture/testing/) |

Expectimax is configured through the Scala search API; it is not a default npm bot.
Available search depth is an implementation capability, not a promise of strength or speed
on every device. Trained models and opening-book data are not shipped with the library.

## Recent changes for application developers

- **0.11.0 — separate JVM rules artifact.** `dicechess-rules_3` lets Scala consumers depend on
  rules without pulling in the engine's bots and evaluators.
- **0.12.0 — lightweight JavaScript rules entry.** `@fortemate/dicechess-engine/rules`
  exposes basic rules operations. Complete legal turn trees and bots use the full entry.
- **0.13.0 — complete turn navigation in JavaScript.** `getLegalTurnTree` exposes legal
  action sequences. `applyMove` retains the unspent dice and refuses moves the dice cannot
  support; clients should follow the original tree throughout the turn.
- **Applications using the engine.** The browser play client and Dice Chess TV demonstrate
  different interfaces using the same npm rules and bot implementation. See
  [integration examples](/dicechess-engine/guides/integrations/).

The [artifact guide](/dicechess-engine/architecture/artifacts/) covers dependency choices
and migrations, including the distinction between JVM rules and the npm rules subset.

## Future work

Parallel chance-node search with Ox remains a proposal tracked by
[issue #61](https://github.com/fortemate/dicechess-engine/issues/61). It is not an implemented
engine feature. Runtime and deployment work should be evaluated against actual application
needs and measured workloads; see [runtime choices](/dicechess-engine/infrastructure/oracle-cloud/).

For current priorities, use the live GitHub trackers above. A closed milestone or completed
implementation does not by itself establish production adoption or a measured playing-strength gain.
