---
title: Monte-Carlo Pre-Roll Equity
description: A Rao-Blackwellized Monte-Carlo estimator of pre-roll win probability, why integrating the exact per-ply king-capture probability cuts variance, and how to budget it.
sidebar:
  order: 4
---

For positions that are absent or too sparse in the games database to read an empirical win-rate,
[`MonteCarloEquity`](https://github.com/fortemate/dicechess-engine/blob/main/shared/src/main/scala/dicechess/engine/search/MonteCarloEquity.scala)
estimates the **pre-roll** outcome distribution on demand:

```scala
val est = MonteCarloEquity.estimate(state, MonteCarloConfig(rollouts = 2000), new Random(seed))
est.whiteWin   // P(White captures a king first)
est.blackWin   // P(Black captures a king first)
est.undecided  // residual survival mass at the ply horizon
```

The three masses partition every rollout and sum to `1.0`. "Pre-roll" means the estimate is for a
position *before* the dice are rolled — the same quantity analytics reads empirically — so it slots
directly into the doubling-cube equity guidance.

## Why Rao-Blackwell

A vanilla Monte-Carlo rollout plays a full random game and scores a **0/1** win at the end. Its
per-rollout variance is that of a Bernoulli variable: `p·(1 - p)`, which needs many samples to
tighten.

The engine already computes, for any position, the **exact** probability that the side to move
captures a king on its upcoming roll — [`KingCaptureProbability`](https://github.com/fortemate/dicechess-engine/blob/main/shared/src/main/scala/dicechess/engine/search/KingCaptureProbability.scala)
enumerates all 216 dice outcomes (56 weighted multisets). Instead of sampling a win/loss at each
node, we **integrate that exact term** along the rollout, weighted by the probability the game is
still alive:

```text
survive = 1
for each ply (side S to move):
  p = P(S captures the opponent king on this roll)   // exact, over all 216 rolls
  winsOf(S) += survive * p
  survive   *= (1 - p)
  advance to a random surviving continuation          // a sampled non-capturing turn
```

This is **Rao-Blackwellization**: replacing a sampled indicator with its conditional expectation
can only reduce variance. The continuation is still sampled (via [`TurnGenerator`](https://github.com/fortemate/dicechess-engine/blob/main/shared/src/main/scala/dicechess/engine/search/TurnGenerator.scala)
+ the `RandomSearch` policy), so the estimator stays unbiased to first order while the dominant
win/loss mass at every node is added analytically.

`EquityEstimate.varianceReductionVsVanilla` reports the measured ratio `mean·(1 - mean) / sampleVariance`
— how many times smaller the per-rollout variance is than a vanilla 0/1 estimator with the same mean.
This is the self-check ported from the C++ reference; a value `> 1` quantifies the win, and
`+Infinity` means the position is resolved exactly (zero sample variance — e.g. decided on the first
roll).

## Conditioning the rollout on survival

The C++ reference advances the rollout to *any* sampled legal turn, including king-captures. A king
capture is terminal, so following it produces a king-less board that is then played on — a small
second-order bias. As the rules source of truth, this implementation instead conditions the
continuation on **survival**: it advances only through turns that do *not* capture a king (the event
with probability `1 - p` that `survive` already tracks). If a sampled roll has no surviving
continuation it is re-rolled; the per-ply analytic terms — and therefore the variance self-check —
are unchanged.

## Budgeting

`MonteCarloConfig` controls cost:

- `rollouts` — fixed sample count (and the hard cap when adaptive stopping is on).
- `maxPlies` — rollout horizon; survival mass still alive at the horizon is reported as `undecided`.
- `targetError` — when `> 0`, stop early once the White-win standard error reaches the target (after
  `minRollouts`). Standard error scales as `1/√rollouts`, so halving it costs 4× the rollouts.

The dominant cost per ply is the exact king-capture term (56 weighted DFS probes), not the random
advance. `MonteCarloEquityBenchmark` tracks fixed-budget time (→ rollouts/sec) and time-to-target-CI
across positions, which decides where the estimator runs: server-side, cached, or compiled to WASM
for the browser.

## Verification

- Convergence: on a position where every one of the 216 rolls captures the king, the estimate is
  exactly `1.0` for the side to move (and its `colorFlip` gives `1.0` for the other side).
- Invariant: `whiteWin + blackWin + undecided == 1` for every position.
- Variance: the reference self-check (`varianceReductionVsVanilla > 1`) on a sharp position.
- Determinism: a fixed seed reproduces the estimate bit-for-bit; adaptive stopping halts at the
  configured `minRollouts` once the target error is met.
