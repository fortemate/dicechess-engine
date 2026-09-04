---
title: Approved Milestones
description: The structured roadmap and definition of done for successive versions of the Dice Chess Engine. See AGENTS.md for the canonical project state.
---

Assign tasks to these milestones logically. Each milestone must be fully tested (including performance benchmarks) before moving to the next.

[View current milestones on GitHub](https://github.com/fortemate/dicechess-engine/milestones?sort=title&direction=asc)

> **Note on history.** This page was rewritten after the migration to the Fortemate organization. An earlier revision marked the search milestones as completed while several of their key deliverables (Zobrist/TT, Star1/Star2 pruning, depth > 2, parallel search) were in fact never implemented. The completed section below lists only what actually shipped; everything else moved back into the open milestones, which match the GitHub milestone list one-to-one.

---

## Completed (shipped in the v0.1–v0.4 line)

### ✅ v0.1 - Foundation & Core Types

- Project setup (SBT 2.x / Scala 3), configuration, `mise` setup.
- Opaque core types (`Bitboard`, `Square`, `Piece`, `Color`).
- FEN parsing/serialization and the DFEN extension (7th field for the dice pool, multiple en-passant targets).

### ✅ v0.2 - Move Generation (Classic)

- Bitwise operations and precomputed attack tables (Magic Bitboards).
- Pawn, knight, king, and sliding piece move generation.
- Perft framework integration to verify move correctness.

### ✅ v0.3 - Dice Chess Mechanics

- Dice roll representation (216 ordered rolls / 56 weighted multisets).
- Filtering pseudo-legal moves by dice outcomes; Maximum Micro-moves Rule enforcement.
- Turn lifecycle: roll → generate moves → apply micro-moves → endTurn.

### ✅ v0.4 - Bots, Evaluation & Integrations (current line)

The 0.4.x line also absorbed deliverables originally slated for later milestones:

- Bot roster: Random, Checkmate-Aware, Greedy, Cautious Greedy, Aggressive, Monte-Carlo, plus the ONNX-backed searches.
- Static evaluation (`Evaluator`, material and aggressive variants) and exact King Capture Probability (per-roll enumeration over all 216 outcomes).
- **2-ply** expectimax with chance nodes (`ExpectimaxSearch`): material pre-ranking, top-K candidate expansion, per-roll deadline honouring, leaf deduplication (`LeafKey`, ~78% duplicates).
- Time management subsystem (`TimeManager` policies incl. empirical-v1 + `TimeBudgetedSearch`).
- Rao-Blackwellized Monte-Carlo pre-roll equity estimator.
- ONNX model integration (`OnnxEvalSearch`, `OnnxExpectimaxSearch`), opening book (`OpeningBook`, `OpeningBookBot`, `OpeningBookParser`), doubling cube and draw-offer logic.
- Scala.js / WasmGC artifacts, JVM Battle Arena, seeded evaluation fixtures, JMH benchmarks.

---

## Open milestones

### 🚧 v0.5 - Search Foundations: Zobrist & TT

- **Scope**: Caching groundwork required before any deeper search.
- **Key Deliverables**:
  - Zobrist hashing over position, active color, castling, en passant, and the remaining dice pool.
  - Transposition table with bound-typed entries (exact / lower / upper) and thread-safe reads.
  - Search/evaluation hot-path groundwork (KCP optimization track).

### 🚧 v0.6 - Star Pruning & Search Depth 3

- **Scope**: Cutoffs at chance nodes and the first depth increase. Tracked by [Epic #56](https://github.com/fortemate/dicechess-engine/issues/56); design in the internal wiki (`model/star-pruning-depth-3`).
- **Key Deliverables**:
  - Star1 pruning in `ExpectimaxSearch` chance nodes (weight-ordered rolls, fail-soft bounds, cutoff telemetry).
  - Star2 probing with batched top-1 opponent replies.
  - Configurable `searchDepth`; depth-3 implementation gated by paired SPRT duels.

### 🚧 v0.7 - Fast Learned Evaluation (NNUE track)

- **Scope**: Engine-side support for a fast learned evaluator — the designated remaining strength lever once search is squeezed (value-model roadmap step 3c).
- **Key Deliverables**:
  - Quantized integer inference with an incremental (NNUE-style) accumulator inside make/undo.
  - Replacing ONNX Runtime on the search hot path; ONNX stays for training-side export and analysis serving.

### 🚧 v0.8 - Self-Play & Book Distillation

- **Scope**: The engine as a data generator for training flywheels.
- **Key Deliverables**:
  - In-engine self-play generation (`TurnGenerator`-based) with seeded reproducibility.
  - Self-play-distilled opening book to replace the human-game book (measured weaker in arena: booked vs bookless 327–316).

### 🚀 v1.0 - Production & Optimization

- **Scope**: Deployment optimization and infrastructure operations.
- **Key Deliverables**:
  - GraalVM Native Image compilation for fast startup.
  - Dockerfile optimization for containerized deployment.
  - CI/CD pipeline improvements (release automation, publishing).
  - Deployment configurations for Oracle Cloud (Ampere ARM64).
  - Structured concurrency with Virtual Threads (`Ox`) for parallel chance-node evaluation ([#61](https://github.com/fortemate/dicechess-engine/issues/61)) — gated on a production width measurement, moved here from v0.6.
