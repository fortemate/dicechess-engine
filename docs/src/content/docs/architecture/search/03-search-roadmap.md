---
title: Search Roadmap & Evaluation
description: The staged plan for improving Dice Chess search and the benchmark protocol used to validate each upgrade.
sidebar:
  order: 3
---

The engine includes five single-turn heuristic bots and the rollout-based `MonteCarloSearch`.
The Scala search API also provides configurable **two- or three-ply expectimax** with
**Star1/Star2 pruning, Zobrist hashing and transposition caching**. These are implemented;
they are no longer future stages of the roadmap.

See [Expectimax Search](/dicechess-engine/architecture/search/06-expectimax-search/) for the
algorithm and [Project Status & Roadmap](/dicechess-engine/architecture/milestones/) for the
broader delivery overview. The default npm bot roster does not include expectimax.

## Search capabilities and remaining work

| Capability | Status | Reference |
| --- | --- | --- |
| Full-turn heuristic bots | Implemented | [Primitive bots](/dicechess-engine/architecture/search/01-primitive-search/) |
| Monte-Carlo pre-roll equity and rollout bot | Implemented | [Monte-Carlo equity](/dicechess-engine/architecture/search/04-monte-carlo-equity/) |
| Clock-aware search budgets | Implemented | [Time management](/dicechess-engine/architecture/search/05-time-management/) |
| Expectimax, Star1/Star2 and transposition tables | Implemented, configurable through the Scala API | [Expectimax](/dicechess-engine/architecture/search/06-expectimax-search/) |
| ONNX evaluation and model serving contract | Implemented on JVM; models supplied by the host | [ONNX integration](/dicechess-engine/architecture/search/07-onnx-integration/), [model contract](/dicechess-engine/architecture/search/10-model-contract/) |
| Parallel chance-node evaluation with Ox | Proposed, not implemented | [Issue #61](https://github.com/fortemate/dicechess-engine/issues/61) |

Implementation and evaluation answer different questions. A configurable deeper search
must still demonstrate useful move quality within a target application's time and memory
budget. Do not infer playing strength, deployment status or hardware requirements from a
completed implementation alone.

## Goals and baseline

Future changes should preserve full-turn legality, reproducible testing and bounded runtime.
Compare both move quality and the cost in time, memory and implementation complexity.

`GreedySearch` (Level 3) remains a simple, deterministic reference bot. Every experiment
should explicitly name its baseline, even when comparing against a stronger configured
search. The protocol below applies to changes in existing algorithms as well as new ones.

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

## Tracking further work

[Search evaluation reports and fixtures](https://github.com/fortemate/dicechess-engine/issues/24)
are implemented. Use the [open issues](https://github.com/fortemate/dicechess-engine/issues)
for current work rather than recreating the completed expectimax, pruning or hashing stages.
The [project status page](/dicechess-engine/architecture/milestones/) links the release history
and live milestones.
