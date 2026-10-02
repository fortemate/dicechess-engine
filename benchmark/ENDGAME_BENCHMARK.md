# Dice Chess Endgame Benchmark Suite (`endgames-v1.json`)

## Overview

The **Dice Chess Endgame Benchmark Suite** (`endgames-v1.json`) provides a standardized, reproducible suite of canonical endgame positions to evaluate:
1. **Attacker Conversion Efficiency:** How reliably and rapidly an attacker with material advantage checkmates/captures the defending king without blundering its own king.
2. **Defender Tenacity & Counterplay:** How long a defending side (especially a lone king) survives, whether it maintains maximum square mobility, and whether it actively creates king-capture counterplay lottery opportunities rather than passively collapsing into a corner.

The suite is bundled at `arena/src/main/resources/endgame-benchmark/endgames-v1.json` and mirrored at `benchmark/endgames-v1.json`.

---

## Canonical Position Categories

The suite consists of 17 positions across 5 canonical endgame categories:

### 1. Lone King vs K+Q (`lone-king-vs-kq`)
- `kq-vs-k-centralized`: Defender king on e5 (8 escape squares, Chebyshev distance 3). Tests mobility retention and avoidance of corner traps.
- `kq-vs-k-edge`: Defender king on e8 (5 escape squares, Chebyshev distance 7). Tests attacker cutoff technique and defender king counter-march.
- `kq-vs-k-corner`: Defender king on h8 (3 escape squares, Chebyshev distance 7). Tests escape from corner trap.
- `kq-vs-k-near-kings`: White K on e2, Q on a1; Black K on e4 (Chebyshev distance 2). Critical test for whether the lone king seizes proximity to threaten the enemy king or retreats.

### 2. Lone King vs K+R (`lone-king-vs-kr`)
- `kr-vs-k-centralized`: Defender king on e5, White K on e1, R on a1 (Chebyshev distance 4).
- `kr-vs-k-edge`: Defender king on d8, White K on d1, R on a1 (Chebyshev distance 7).
- `kr-vs-k-corner`: Defender king on a8, White K on e1, R on h1 (Chebyshev distance 7).
- `kr-vs-k-near-kings`: Defender king on f3, White K on f1, R on a8 (Chebyshev distance 2). Tests mutual-threat race between kings.

### 3. Lone King vs K+2 Pawns (`lone-king-vs-k2p`)
- `k2p-vs-k-connected-passed`: White pawns on d4/e4; Black king on d7 blockading (Chebyshev distance 6).
- `k2p-vs-k-isolated-passed`: White split pawns on b4/g4; Black king on e5 (Chebyshev distance 4).
- `k2p-vs-k-advanced-pawns`: White pawns on c6/d6; Black king on d8 (Chebyshev distance 7).

### 4. K+P vs K Promotion Races (`kp-vs-k`)
- `kp-vs-k-king-in-front`: White K on e5, P on e4; Black K on e7 (Chebyshev distance 2). Classical key squares battle.
- `kp-vs-k-outside-passer`: White K on b2, P on a4; Black K on g7 (Chebyshev distance 5). Square of the pawn rule.
- `kp-vs-k-rook-pawn`: White K on c2, P on a4; Black K on c7 (Chebyshev distance 5). Defensive cornering dynamics.

### 5. K+B+N vs K (`kbn-vs-k`)
- `kbn-vs-k-centralized`: White K on e1, B on c1 (dark), N on b1; Black K on e5 (Chebyshev distance 4).
- `kbn-vs-k-wrong-corner`: Black king on a8 (light square, opposite bishop color; Chebyshev distance 7).
- `kbn-vs-k-right-corner`: Black king on h8 (dark square, matching bishop color; Chebyshev distance 7).

---

## Running the Benchmark Suite

Use `EndgameSuiteRunner` to execute mirrored matches across the suite:

```bash
# Run all 17 positions (5 games per color = 170 total games)
sbt 'arena/runMain dicechess.engine.bench.EndgameSuiteRunner --bot aggressive --baseline greedy --games 5 --seed 42'

# Run a specific category
sbt 'arena/runMain dicechess.engine.bench.EndgameSuiteRunner --bot aggressive --baseline greedy --games 5 --category lone-king-vs-kq'

# Export structured JSON report
sbt 'arena/runMain dicechess.engine.bench.EndgameSuiteRunner --bot aggressive --baseline greedy --games 5 --json report.json'
```

### Options:
- `--bot <id>`: Candidate bot ID (default: `aggressive`)
- `--baseline <id>`: Baseline opponent ID (default: `greedy`)
- `--games <n>`: Games per color per position (default: `5`, total `2 * n`)
- `--seed <n>`: Dice seed (default: `42`)
- `--suite <path>`: Optional path to custom endgame JSON suite
- `--category <cat>`: Optional category filter
- `--json <path>`: Path to export JSON report

---

## Baseline Results: Aggressive vs Greedy (10 games/pos, seed 42)

```
Category             | Pos | Games | Cand Wins | Base Wins | Draws | Cand Atk% | Cand Def%
-----------------------------------------------------------------------------------------------
kbn-vs-k             |   3 |    30 |        15 |        15 |     0 |     93.3% |      6.7%
kp-vs-k              |   3 |    30 |        18 |        12 |     0 |     86.7% |     33.3%
lone-king-vs-k2p     |   3 |    30 |        15 |        15 |     0 |     93.3% |      6.7%
lone-king-vs-kq      |   4 |    40 |        22 |        18 |     0 |     95.0% |     15.0%
lone-king-vs-kr      |   4 |    40 |        20 |        20 |     0 |     90.0% |     10.0%
-----------------------------------------------------------------------------------------------
OVERALL BENCHMARK RESULTS:
Total Positions : 17
Total Games     : 170
Candidate Score : 90 wins, 0 draws, 80 losses (52.9%)
Candidate Atk % : 91.8% (78 / 85)
Candidate Def % : 14.1% (12 / 85)
Total Time      : 1.51s
```

### Key Baseline Takeaways:
- **Attacker Conversion:** Both `aggressive` and `greedy` successfully convert >90% of winning piece-advantage endings.
- **Defender Tenacity:** Without specialized endgame logic, lone king defense wins only 14.1% overall (and 0% in corner positions).
