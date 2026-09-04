---
title: Search Roadmap & Evaluation
description: The staged plan for improving Dice Chess search and the benchmark protocol used to validate each upgrade.
sidebar:
  order: 3
---

The engine currently has a complete 5-level primitive bot roster: `RandomSearch`, `CheckmateAwareSearch`, `GreedySearch` (baseline), `GreedySearchV2` (Cautious Greedy), and `AggressiveSearch`. Level 6 is the non-primitive `MonteCarloSearch` bot.

This forms a solid baseline for move evaluation, but single-turn bots are limited to a 1-turn horizon. To develop a stronger, grandmaster-level engine, we need to transition from single-turn heuristics to a deep, probabilistic search tree.

This document defines:
1. the revised implementation roadmap for deep search algorithms and optimizations
2. the evaluation protocol that every new search algorithm must pass before it replaces the baseline

## Goals

The search roadmap should improve move quality without losing the properties that already matter:

* legal move selection under the maximum micro-moves rule
* deterministic and reproducible testing
* predictable runtime for browser and JVM usage
* a clear migration path from simple heuristics to probabilistic tree search

Future changes should therefore be judged on two axes:

* **playing strength** against the current baseline (`GreedySearch` or the latest optimized bot)
* **cost** in runtime, implementation complexity, and memory footprint (minimizing garbage collection on the hot path)

## Baseline

The current baseline is `GreedySearch` (Level 3).

While primitive, it has several advantages:
* it reasons over the full Dice Chess turn, not a single micro-move
* it is deterministic
* it is easy to explain and debug
* it creates a stable reference point for head-to-head testing

The baseline remains available even after stronger algorithms are added. It serves as the control group for all future experiments.

## Revised Roadmap

The search roadmap is divided into progressive optimization and tree-search stages.

### Stage 1: Deep Expectimax Search

This stage introduces depth-first traversal of multiple plies (turns), reasoning about future turns.

Expected characteristics:
* **Chance Nodes:** Representing the stochastic dice roll outcomes (216 ordered rolls / 56 unique multisets) of both sides.
* **Decision Nodes:** Selecting the optimal full-turn path (1-3 micro-moves) for the active player.
* **Bounded Depth:** Dynamically adjusted search depth based on remaining time controls.

**Milestone fit**:
* primarily **v0.6 - Expectimax Search Engine**

### Stage 2: Search Optimizations (Star1 & Star2 Pruning)

Traversing a deep expectimax tree scales exponentially. We must implement advanced alpha-beta pruning extensions for games with chance nodes:
* **Star1/Star2 Pruning:** Prunes chance nodes by calculating bounds on the mathematical expectation and skipping subtrees that cannot affect the optimal choice.
* **Zobrist Hashing & Transposition Tables (TT):** Caches previously computed node values, bounds, and search depths. Zobrist keys must include the board position, active color, and remaining dice pool.

**Milestone fit**:
* primarily **v0.6 - Expectimax Search Engine** and **v0.5** (Zobrist/TT groundwork)

### Stage 3: Parallel Search with Ox Concurrency

Nothing here is implemented, and the stage is gated rather than scheduled — see
[#61](https://github.com/fortemate/dicechess-engine/issues/61) for the current scope and its preconditions.

The idea is to parallelize the evaluation of chance-node subtrees on hosts that have spare cores:
* Use **Java Virtual Threads** (via the `Ox` structured concurrency library) to spawn lightweight concurrent branch evaluations.
* Ensure thread-safe read operations on transposition tables.
* Implement structured cancellation to stop running threads immediately when a beta-cutoff is triggered or when search time expires.

Two caveats that earlier revisions of this page got wrong. The stage was originally motivated by 4-core
ARM nodes on Oracle Cloud; that Always Free shape was withdrawn in June 2026, and no such host runs the
search today. And it was positioned as the step that makes depth 3 affordable, which Stage 2's depth-3
gate has since ruled out on magnitude. Whether spare cores convert into completed candidates at all is
an open measurement, not an assumption.

**Milestone fit**:
* **v1.0 - Production & Optimization** (moved out of v0.6, which shipped without it)

### Stage 4: Monte-Carlo Pre-Roll Equity

Parallel to the exact tree search, a Rao-Blackwellized Monte-Carlo estimator gives an *on-demand*
pre-roll win-probability estimate for positions too sparse in the games database to read empirically.
It integrates the exact per-ply king-capture probability (`KingCaptureProbability`) along a random
rollout weighted by survival, which cuts variance sharply versus vanilla 0/1 rollouts. It reuses
`TurnGenerator` + the `RandomSearch` policy and exposes a configurable budget (rollouts / target CI
width / ply horizon).

See [Monte-Carlo Pre-Roll Equity](/architecture/search/04-monte-carlo-equity/) for the algorithm,
the variance rationale, and budgeting. It complements position canonicalization (which pools
empirical statistics across symmetric positions) for genuinely off-book positions, and shares the
`KingCaptureProbability` machinery with the expectimax chance-node evaluation.

The estimator also drives a bot: **`MonteCarloSearch`** (Level 6, registered in `BotRegistry`) scores
every legal turn by the Monte-Carlo win probability of the resulting position and plays the best,
preferring an immediate king capture. It is the first non-primitive bot (rollout-based lookahead
rather than a one-ply heuristic). Per-move cost scales with the number of legal turns × the rollout
budget, so a multi-game win-rate match is validated offline in the JVM Battle Arena — not in CI.

**Time control.** Because per-move cost grows with the branching factor, an unbounded heavy bot loses
on the clock in complex positions. Clock handling is *not* specific to this bot: it is an
engine-wide, two-layer subsystem — `TimeManager` (a shared, pure policy turning a game clock into a
per-turn budget) and `TimeBudgetedSearch` (the capability mix-in each algorithm implements to honour
a deadline). See [Time Management](/architecture/search/05-time-management/) for the budget formula,
the constants, and which algorithms are budgeted.

`MonteCarloSearch` was the first implementor: it takes any immediate king capture for free, otherwise
ranks turns by a cheap material score, keeps the top *K*, and Monte-Carlo-evaluates them within an
equal slice of the remaining time, always falling back to the best material turn so a legal move is
returned even if the deadline elapses first. Its own budget paths (rollouts / target-error) stay
deterministic for tests; only the wall-clock path is non-deterministic.
The acceptance gate is whether time-limited Monte-Carlo beats `AggressiveSearch` (L5) within a
one-minute game budget — otherwise the heavy search is not worth it over the empirical-statistics path.

**Milestone fit**:
* feeds the analytics equity guidance now; aligns with **v0.6 - Expectimax Search Engine** machinery.

---

## Evaluation Pyramid

Every new algorithm or optimization must be validated at three levels.

### Level 1: Unit Correctness

These tests prove that the algorithm respects core game semantics and obvious tactical priorities.
* immediate king capture is always preferred over any non-terminal material sequence
* the maximum micro-moves rule is never violated
* castling still consumes the correct dice and is scored correctly
* promotion branches are ranked consistently

This level protects correctness and prevents regressions that would otherwise be hidden inside large simulations.

### Level 2: Deterministic Scenario Suite

The implemented scenario suite is a small, versioned catalog of fixed positions designed to expose evaluator and
shallow-search differences without the noise of a full match. The bundled catalog lives at
[`arena/src/main/resources/search-evaluation/core-v1.json`](https://github.com/fortemate/dicechess-engine/blob/main/arena/src/main/resources/search-evaluation/core-v1.json)
and covers:

* tactical decisions, including immediate king captures and material choices
* defensive decisions around king exposure and blocking attacks
* endgame decisions, including promotions and sparse captures
* forced passes when none of the remaining dice can move

Each scenario has a stable ID, category, rationale, DFEN (including the remaining dice pool), and either a set of
allowed turn paths or an expected pass. The catalog also names an explicit seed set. Every candidate and baseline
decision is checked for legality and expectation matching, so a report distinguishes improvements, regressions,
shared matches, and shared misses. The focused fixture and reproducibility tests run as part of `mise run check`.

Run the default comparison (`aggressive` against `greedy`) with:

```bash
mise run arena:evaluate
```

The task accepts positional `bot`, `baseline`, and `fixtures` arguments:

```bash
mise run arena:evaluate aggressive greedy arena/src/main/resources/search-evaluation/core-v1.json
```

The runner prints a human-readable row for every scenario and seed. For an additive-stable, machine-readable JSON
report, invoke the runner directly with `--json`:

```bash
sbt 'arena/runMain dicechess.engine.bench.SearchEvaluationRunner --bot aggressive --baseline greedy --json target/search-evaluation.json'
```

The JSON report records the fixture and seed-set identities, both bot identities, per-run decisions and scores,
legality and expectation results, comparison classifications, and aggregate totals. Preserve fixture IDs and seed-set
IDs when comparing archived reports; add a new version when their meaning changes.

### Level 3: Mass Simulation (Bot Arena)

This is the acceptance layer for comparing playing strength. Each challenger algorithm plays a large head-to-head match against the baseline:
* baseline as White, challenger as Black
* challenger as White, baseline as Black
* fixed seed lists for reproducibility
* repeated across multiple starting positions, tactical middlegames, and simplified endgames

---

## Match Protocol

The benchmark harness should follow a stable protocol so that results remain comparable over time.

### Required controls
* deterministic PRNG seeding
* explicit algorithm identity and Git commit hash in output reports
* symmetric color assignment (equal games as White and Black)
* turn/time budget settings recorded in the report
* fixed resignation, repetition, and 50-move limit rules

### Required metrics
At minimum, collect:
* win, loss, and draw rates
* average game length (turns)
* average decision time per turn
* average number of candidate paths or nodes evaluated
* runtime distribution (percentiles), not just mean runtime

### Acceptance criteria
A challenger should replace the baseline only when all of the following are true:
* it passes unit correctness and scenario-suite checks
* it shows a statistically meaningful improvement in head-to-head results (Win Rate > 50%)
* it does not introduce an unacceptable runtime regression for the target environment (e.g. browser/JS vs server/JVM)

---

## Reporting Format

Each experiment should produce a concise report:
```text
================================================================================
🎲♟️  Dice Chess Bot Arena - JVM Match Runner
Baseline Bot: [Name] (ID)
Games per Color: [N] (Total [2N] games per match)
================================================================================
Opponent Bot    | Total | Wins (W/B)   | Losses (W/B) | Draws (W/B)  | Win Rate | Time    
----------------------------------------------------------------------------------------
[Bot A]         | 1600  | ...          | ...          | ...          |    ...%  | ...s
```

This makes search changes reviewable inside pull requests instead of relying on anecdotal observations.

## Suggested Issue Decomposition

[Search evaluation reports and fixtures](https://github.com/fortemate/dicechess-engine/issues/24) are implemented.
The remaining future search engine tasks are decomposed as follows:

1. `Expectimax search skeleton` (Milestone v0.6)
2. `Star1 and Star2 pruning implementation`
3. `Zobrist Hashing & Transposition Table integration`
4. `Structured concurrency: parallelize chance-nodes using Ox`

## Milestone Mapping

The recommended milestone mapping is:

* **v0.5 - Evaluation & Heuristics**
  * Zobrist Hashing implementation
  * Transposition Table structure
* **v0.6 - Expectimax Search Engine**
  * Expectimax implementation and chance-node evaluations
  * Star1/Star2 pruning
  * Concurrency and parallel search improvements with `Ox`

This keeps the project aligned with the approved milestones published in the [Roadmap & Milestones](../milestones/) guide.
