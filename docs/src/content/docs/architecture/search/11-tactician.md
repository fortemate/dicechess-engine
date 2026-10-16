---
title: Tactician Search
description: Configurable single-turn tactical rescoring using exact dice-roll king-threat coverage.
sidebar:
  order: 11
---

`TacticianSearch` ranks complete legal turns with aggressive evaluation, then
rescores a bounded shortlist with exact roll-weighted king danger and hanging
material. It is opt-in: the engine supplies the algorithm and profile schema,
while the host supplies every parameter. No default profile is registered.

## Selection

1. Enumerate canonical maximal-dice paths with `TurnGenerator`.
2. If a path captures the king, choose among the shortest winning paths before
   evaluating any heuristic.
3. Apply each other path and end the turn. Deduplicate the complete resulting
   `GameState`, including flags, clocks and en-passant state. Retain the first
   path reaching each state.
4. Rank distinct states with `Evaluator.evaluateAggressive`; prefer shorter paths
   when cheap scores tie. Keep at most `candidateLimit` states.
5. Replace the evaluator's boolean king-safety contribution with roll-weighted
   danger, add attacking potential and the hanging-material balance, then select
   a highest-scoring candidate. Equal scores use the supplied random generator.

Both danger and attacking potential use `KingThreat.threatWeight` divided by the
total ordered dice-roll weight. Danger measures the opponent's chance to capture
our king on the next roll. Attacking potential measures our chance to capture
their king if the resulting board remains unchanged until our next roll; it
does not simulate the opponent's reply.

The hanging-material balance is enemy hanging material minus friendly hanging
material, using `PieceSafety.hangingMaterial`. A heuristic score is clamped below
the terminal-win sentinel, including under extreme finite profile settings.

## Profile and registration

| Field | Meaning | Validation |
| --- | --- | --- |
| `candidateLimit` | Maximum distinct states rescored | Positive integer |
| `kingThreatCp` | Reward for hypothetical attacking coverage | Nonnegative integer |
| `kingDangerCp` | Penalty for opponent capture coverage | Nonnegative integer |
| `hangingWeight` | Multiplier for hanging-material balance | Finite, nonnegative number |

Scala callers construct `TacticianConfig` and `TacticianSearch` directly. To
expose a configured search through registry-based APIs, call
`BotRegistry.registerTacticianBot(config, id, name)`. It returns a removable
`Registration` on success and `None` for a missing profile, blank identity or
occupied case-insensitive id. Closing that registration removes the entry.

Java and Kotlin callers use
[`JvmApi.registerTacticianBot`](/dicechess-engine/architecture/jvm-api/#registertacticianbot).
JavaScript callers use
[`DiceChess.registerTacticianBot`](/dicechess-engine/architecture/javascript-api/#registertacticianbot).
These facade methods return a boolean and retain successful registrations for
the process lifetime. Existing bots and the default algorithm are preserved.

## Work and timing

The candidate limit bounds expensive tactical queries, not total search time.
Full legal-turn generation, state application, deduplication and cheap ordering
still depend on the position. The implementation is synchronous and has no
wall-clock deadline or UI responsiveness guarantee.

An optional `TacticianStats` sink reports legal paths, distinct states, rescored
states and threat-query counts for each call. Per-call working collections stay
local to the search invocation; hosts are responsible for the thread safety of
their statistics sink.

`TacticianExperimentMain` compares configurable searches in the arena and writes
reports outside the repository. `TacticianSearchBenchmark` measures whole-move
latency alongside aggressive search. Supply every profile value explicitly for
experiments; measured profiles and experiment verdicts stay private.

Game summaries use a conservative two-sided 95% interval from the
[Hoeffding bound](https://www.stat.cmu.edu/~cshalizi/sml/21/lectures/06/lecture-06.html).
Each independent mirrored pair is one observation in `[0, 1]`. Bounds are clipped
to that range and retain uncertainty even when every pair has the same outcome.

Run the configured JMH comparison with the dedicated task:

```text
mise run bench:tactician <candidate-limit> <king-threat-cp> <king-danger-cp> <hanging-weight>
```

The standard `bench` and `bench:quick` tasks exclude this explicit-profile
benchmark. For custom JMH iteration or filter options, invoke `benchmark/Jmh/run`
directly and supply all four profile fields with `-p` arguments.
