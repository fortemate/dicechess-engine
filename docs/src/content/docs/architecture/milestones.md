---
title: Approved Milestones
description: The structured roadmap and definition of done for successive versions of the Dice Chess Engine. See AGENTS.md for the canonical project state.
---

Assign tasks to these milestones logically. Each milestone must be fully tested (including performance benchmarks) before moving to the next.

[View current milestones on GitHub](https://github.com/fortemate/dicechess-engine/milestones?sort=title&direction=asc)

---

## ✅ v0.1 - Foundation & Core Types

* **Status**: Completed 🏆
* **Scope**: Project setup (SBT 2.x / Scala 3), configuration, `mise` setup.
* **Key Deliverables**:
  * Implementation of basic Opaque Types (`Bitboard`, `Square`, `Piece`, `Color`).
  * Basic FEN parsing and serialization.
  * DFEN extension (7th field for dice pool, multiple en-passant targets).

## ✅ v0.2 - Move Generation (Classic)

* **Status**: Completed 🏆
* **Scope**: Fast legal and pseudo-legal move computation.
* **Key Deliverables**:
  * Bitwise operations and precomputed attack tables (Magic Bitboards).
  * Pawn, knight, king, and sliding piece move generation.
  * Perft (Performance Test) framework integration to verify move correctness.

## ✅ v0.3 - Dice Chess Mechanics

* **Status**: Completed 🏆
* **Scope**: Integrating physical probability layers of Dice Chess.
* **Key Deliverables**:
  * Dice roll representations.
  * Filtering pseudo-legal moves based on dice outcomes.
  * Game state management with random events.
  * Maximum Micro-moves Rule enforcement.
  * Turn lifecycle: roll → generate moves → apply micro-moves → endTurn.

## ✅ v0.4 - Basic Bot & Gameplay

* **Status**: Completed 🏆
* **Scope**: Validating game mechanics and state transitions with primitive bots.
* **Key Deliverables**:
  * Implementation of 7 primitive bots: Random, Checkmate-Aware, Greedy, Cautious Greedy, Aggressive, Monte-Carlo, Expectimax.
  * Integration with Scala.js for browser-based execution.
  * Dedicated Svelte/Vite PWA test harness for human vs. engine testing.
  * JVM Battle Arena for bot-vs-bot win-rate validation.

## ✅ v0.5 - Evaluation & Heuristics

* **Status**: Completed 🏆
* **Scope**: Understanding board values statically and efficiently caching calculations.
* **Key Deliverables**:
  * Static evaluation function (Material balance, Piece-Square Tables).
  * Zobrist Hashing and Transposition Tables (TT) for caching board states.
  * King Capture Probability analysis (exact enumeration of 216 dice outcomes).

## ✅ v0.6 - Expectimax Search Engine

* **Status**: Completed 🏆
* **Scope**: Deep probabilistic search with chance nodes.
* **Key Deliverables**:
  * Deep Expectimax search implementation with chance nodes (216 ordered rolls / 56 unique multisets).
  * Star1/Star2 pruning for chance nodes to reduce search tree.
  * Time management subsystem (`TimeManager` policy + `TimeBudgetedSearch` honouring).
  * Rao-Blackwellized Monte-Carlo pre-roll equity estimator.
  * Structured concurrency with Virtual Threads (`Ox`) for parallel chance-node evaluation.

## ✅ v0.7 - Advanced Features

* **Status**: Completed 🏆
* **Scope**: Production-ready features and integrations.
* **Key Deliverables**:
  * ONNX model integration for learned evaluation (`OnnxEvalSearch`, `OnnxExpectimaxSearch`).
  * Opening book support for common positions (`OpeningBook`, `OpeningBookBot`, `OpeningBookParser`).
  * Doubling cube logic and draw offer handling (`DrawOfferLogic`).
  * JMH benchmarks for performance validation.

## 🚀 v1.0 - Production & Optimization

* **Scope**: Deployment optimization and infrastructure operations.
* **Key Deliverables**:
  * GraalVM Native Image compilation for fast startup.
  * Dockerfile optimization for containerized deployment.
  * CI/CD pipeline improvements (release automation, publishing).
  * Deployment configurations for Oracle Cloud Free Tier (Ampere ARM64).

