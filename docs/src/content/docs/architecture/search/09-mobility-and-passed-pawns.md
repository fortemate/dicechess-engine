---
title: Mobility and Passed Pawn Contracts
description: Versioned feature contracts kcp-mobility-27-v1 and kcp-mobility-pawns-31-v1, shared-core primitives, and expected wasted-rolls interpretation.
---

The playground evaluation model ablation (fortemate/dicechess-training#12, #14) tests
position-evaluation models against the baseline `KcpFeatures` (`kcp-13`). Strategy
research highlights three position properties that no previous feature schema exposed:
**expected wasted rolls** (dice faces whose piece type has no move), **pawn blockage**,
and **tempo** as separate own and opponent mobility counts rather than a single signed
difference.

In addition, the endgame motivates a **passed-pawn** block: pawn promotion in Dice
Chess depends on rolling a pawn face, and a passed pawn's value increases sharply as
it nears the back rank.

Following the principle of **train == serve**, training enrichment and serving inference
share the exact engine implementation to prevent drift.

## Shared-Core Primitives

Two primitives in `dicechess.engine.search` compute the underlying positional features:

### `PieceMobility`

Exposes pseudo-legal move counts per moving piece type in dice order (`Pawn`, `Knight`,
`Bishop`, `Rook`, `Queen`, `King`):

```scala
package dicechess.engine.search

object PieceMobility:
  def counts(state: GameState, color: Color): Array[Int]
  def count(state: GameState, color: Color, pieceType: PieceType): Int
  def total(state: GameState, color: Color): Int
```

Moves are computed on `state.withActiveColor(color)` with castling dice present
(King and Rook dice in pool), exactly matching how `RichFeatures.mobility_diff`
computes its operands. For every position and color:

$$\sum \text{own\_moves\_*} - \sum \text{opp\_moves\_*} = \text{mobility\_diff}$$

### `PassedPawns`

Exposes passed-pawn detection and rank advancement:

```scala
package dicechess.engine.search

object PassedPawns:
  def count(state: GameState, color: Color): Int
  def maxRank(state: GameState, color: Color): Int
  def countAndMaxRank(state: GameState, color: Color): (Int, Int)
  def bitboard(state: GameState, color: Color): Bitboard
```

- **Definition:** Uses the standard chess definition: a pawn is passed if there are no
  opposing pawns on the same file or on adjacent files ahead of the pawn along its
  direction of advance.
- **Max rank advancement:** Counted from that side's own back rank as 0:
  - White pawn on rank $r \in [2, 7]$: advancement is $r - 1 \in [1, 6]$.
  - Black pawn on rank $r \in [2, 7]$: advancement is $8 - r \in [1, 6]$.
  - When a side has no passed pawn, the value is $0$.
- **Perspective:** Evaluated from the explicit `color` argument, independent of
  active color and dice pool.

### Reusing detection with consumer-owned rank bonuses

`PassedPawns` is the canonical detector in the engine artifact, not the rules-only
artifact. Reuse `bitboard(state, color)` instead of maintaining a second front-span
detector. Its four existing methods retain their behavior and signatures.

Only **opposing pawns strictly ahead** on the same or neighboring files matter.
Friendly pawns, opposing non-pawn pieces, and opposing pawns on the same rank or
behind do not disqualify a pawn. Doubled pawns are considered individually and can
both be passed. A passed pawn can still have its movement blocked; detection does
not test legal moves, king safety, dice availability, or a clear promotion path.

Keep the consumer's bonus table and evaluation weights. The following adapter is
also a compiled example in `PassedPawns` Scaladoc and is exercised against an
independent reference in `PassedPawnsContractSpec`:

```scala
import dicechess.engine.domain.*
import dicechess.engine.search.PassedPawns

def passedPawnBonus(state: GameState, color: Color, bonusByAdvancement: IArray[Int]): Int =
  var p     = PassedPawns.bitboard(state, color).value
  var score = 0
  while p != 0L do
    val square = java.lang.Long.numberOfTrailingZeros(p)
    val rank   = square / 8 + 1
    val adv    = if color.isWhite then rank - 1 else 8 - rank
    score += bonusByAdvancement(adv)
    p &= p - 1L
  score
```

For pawns on board ranks 2–7, `adv` is 1–6 from either color's perspective.
Provide at least seven entries: index 0 is unused, and entries 1–6 contain the
caller's existing bonuses. For a six-entry table indexed 0–5, use `adv - 1` instead.
If the existing table uses absolute board ranks or another convention, translate
the index explicitly. Synthetic positions with pawns on promotion/back ranks need
a separately defined caller policy; the 1–6 adapter assumes ordinary unpromoted
pawn ranks.

The loop uses primitive `Long` operations without allocating a collection or an
iterator per pawn. Allocate the immutable table once outside evaluation. A
caller-owned `Int => Int` may replace the `IArray[Int]` parameter with the same
lookup expression; reuse that function outside the loop as well.
Retain the original accumulation type, overflow handling, own/opponent signs,
weight multiplication, rounding and normalization order. The `Int` example
assumes the caller's scores fit in `Int`; it supplies no default bonuses or weights.
`maxRank` and `countAndMaxRank` summarize a set and cannot replace a sum of
individual rank bonuses when there are multiple passed pawns.

### Qualification and downstream adoption checklist

The existing fixtures in `PassedPawnsSpec` remain in place.
`PassedPawnsContractSpec` compares exact pawn sets and all four public methods
against an independent mailbox scan of every candidate and every possible
opposing blocker. It exhausts single opposing-pawn pairs across files a–h and
ranks 2–7 for both colors, and covers mixed boards, doubled pawns, friendly and
non-pawn blockers, explicit perspective, active-color/dice independence and exact
set mapping under `Symmetry.colorFlip` and `Symmetry.horizontalMirror`.
Adapter checks include arbitrary signed, non-monotone tables and weights,
every advancement index, doubled pawns and the empty set.

For the later downstream integration:

- [ ] Pin an engine version containing the qualified contract; retain the current
  evaluator configuration, table values, scale and weights in the consumer.
- [ ] Compare the old detector's exact square set with `PassedPawns.bitboard`
  on public/synthetic fixtures and the consumer's private fixtures in its own
  repository. Include both perspectives, edge files, doubled pawns and blockers.
- [ ] Compare per-pawn indices, unweighted sums and final weighted scores using
  the current consumer parameters and arithmetic. Do not replace a per-pawn sum
  with the maximum advancement.
- [ ] Report any detector or scoring discrepancy explicitly. Obtain a separate
  compatibility decision for semantic changes before replacing the old path.
- [ ] Replace only local detection, remove its obsolete masks, and keep bonus
  lookup and weighting in the consumer. Verify active-color/dice independence
  and color-flip score parity with the consumer's rank convention.
- [ ] Run the consumer's correctness gates. If its evaluation hot path changes,
  record reproducible fixed-work JMH measurements including work counts and
  allocations. Keep timed-search and playing-strength research separate.

This qualification adds tests and examples without changing detector code,
serving defaults, feature schemas, or downstream evaluators. It does not establish
parity with a particular consumer implementation; that comparison belongs to
the adoption task. Keep private parameters, artifacts and experiment verdicts in
the consumer's private records.

## Versioned Feature Contracts

### 1. `kcp-mobility-27-v1`

Extends `KcpFeatures` (13 columns) with per-piece move counts and normalized PDI:

| Index | Column Name | Source / Definition | Range |
|---|---|---|---|
| 0–12 | `p_diff` … `queen_capture_danger` | Identical to `KcpFeatures.extract` | float32 |
| 13 | `own_moves_p` | Own pseudo-legal pawn moves | $\ge 0$ |
| 14 | `own_moves_n` | Own pseudo-legal knight moves | $\ge 0$ |
| 15 | `own_moves_b` | Own pseudo-legal bishop moves | $\ge 0$ |
| 16 | `own_moves_r` | Own pseudo-legal rook moves | $\ge 0$ |
| 17 | `own_moves_q` | Own pseudo-legal queen moves | $\ge 0$ |
| 18 | `own_moves_k` | Own pseudo-legal king moves | $\ge 0$ |
| 19–24 | `opp_moves_p` … `opp_moves_k` | Opponent pseudo-legal move counts | $\ge 0$ |
| 25 | `own_pdi` | `PieceDiversity.count(state, color) / 5f` | 0.0 to 1.0 |
| 26 | `opp_pdi` | `PieceDiversity.count(state, opponent) / 5f` | 0.0 to 1.0 |

### 2. `kcp-mobility-pawns-31-v1`

Extends `kcp-mobility-27-v1` with the passed-pawn block:

| Index | Column Name | Source / Definition | Range |
|---|---|---|---|
| 0–26 | Prefix (27 columns) | Identical to `KcpMobilityFeatures.extract` | float32 |
| 27 | `own_passed_pawns` | Number of passed pawns for `color` | 0 to 8 |
| 28 | `opp_passed_pawns` | Number of passed pawns for opponent | 0 to 8 |
| 29 | `own_passed_max_rank` | Most advanced passed pawn rank from back rank | 0 to 6 |
| 30 | `opp_passed_max_rank` | Opponent most advanced passed pawn rank | 0 to 6 |

### Mover-Canonical Symmetry

Both contracts are mover-canonical: evaluating `state` from `color` perspective
produces the identical vector as evaluating `Symmetry.colorFlip(state)` from
`color.opponent` perspective. When evaluated for the side to move (`state.activeColor`),
the vector matches that of the color-flipped state evaluated for its own mover.

## Strategic Interpretation

### Expected Wasted Rolls

In Dice Chess, a turn rolls 3 dice from a uniform 6-sided die $\{P, N, B, R, Q, K\}$.
If a side has zero pseudo-legal moves for a piece type $T$ (and therefore zero legal moves),
any die face showing $T$ cannot be used for that type.

For a single die roll, the probability that the rolled piece type has no move is:

$$P(\text{unusable}) = \frac{\text{immobile\_types}}{6}$$

Across the 3 dice rolled in a turn, by linearity of expectation, the expected number
of wasted dice is:

$$\mathbb{E}[\text{wasted rolls}] = 3 \times \frac{\text{immobile\_types}}{6} = \frac{1}{2} \times \text{immobile\_types}$$

The per-piece move counts `own_moves_*` and `opp_moves_*` directly expose whether each
piece type is mobile ($>0$) or blocked ($0$).

### Pawn Blockage and Separate Tempo

- **Pawn Blockage:** When `own_moves_p == 0`, friendly pawns are completely locked or
  absent. In Dice Chess, rolling pawn dice when pawns are blocked forces the player to
  burn turns or forgo actions.
- **Separate Tempo:** Rather than collapsing mobility into a signed scalar
  (`mobility_diff = own - opp`), exposing `own_moves_*` and `opp_moves_*` separately
  allows non-linear models (neural nets) to evaluate asymmetries—for example, high
  mobility for both sides (tactical volatility) versus low mobility for both sides
  (closed deadlock).

## Schema Versioning Rule

:::caution
Any change in column order, normalization, perspective, or semantic definition
requires a **new schema identifier** (e.g. `kcp-mobility-27-v2`) and a corresponding
newly trained model. Existing schemas must remain immutable to preserve reproducibility
and avoid silent evaluation failures.
:::

## JMH Benchmarks

Microbenchmarks on OpenJDK 25 (Temurin 25.0.4) compare extraction overhead across the
5 standard benchmark positions (`initial`, `kiwipete`, `endgame`, `castling`, `promotion`):

| Benchmark | Position | Average Time ($\mu s$) |
|---|---|---|
| `passedPawnsSummary` | initial / kiwipete / endgame / castling / promotion | 0.004 – 0.012 $\mu s$ |
| `pieceMobilityCounts` | initial / kiwipete / endgame / castling / promotion | 0.101 – 0.436 $\mu s$ |
| `kcp` (13 cols) | promotion / endgame / castling / initial / kiwipete | 79 – 17,664 $\mu s$ |
| `kcpMobility27` | promotion / endgame / castling / initial / kiwipete | 82 – 17,466 $\mu s$ |
| `kcpMobilityPawns31` | promotion / endgame / castling / initial / kiwipete | 78 – 17,149 $\mu s$ |

The 216-outcome DFS search for king/queen capture probabilities in `kcp` dominates
total extraction time (hundreds of microseconds to tens of milliseconds). The additional
per-type move counting ($\sim 0.10\text{–}0.44\,\mu s$) and passed-pawn bitboard probe ($\sim 0.01\,\mu s$)
add under $0.01\%$ overhead on complex positions (e.g. $0.44\,\mu s$ on kiwipete's $17.6\,\text{ms}$)
and under $0.55\%$ on the fastest sparse positions ($0.44\,\mu s$ on promotion's $79\,\mu s$).
