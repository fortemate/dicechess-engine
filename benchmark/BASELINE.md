# Dice Chess Engine - JMH Benchmarks Baseline

This document records the baseline performance metrics of the Dice Chess engine core functions, captured on **2026-05-20**. These metrics serve as a reference point to evaluate future optimizations and prevent performance regressions.

## Environment Details
- **JMH Version:** 1.37
- **VM Version:** JDK 17.0.15, OpenJDK 64-Bit Server VM (17.0.15+6-LTS)
- **Parameters:** Warmup: 1 iteration (1s), Measurement: 2 iterations (1s), Fork: 1, 1 Thread.

---

## 1. FEN Parser Performance (`FenParserBenchmark`)

### Throughput (Higher is better)
| Benchmark | Position | Mode | Score | Units |
|-----------|----------|------|-------|-------|
| `parseFen` | `initial` | `thrpt` | `0.210` | `ops/us` |
| `parseFen` | `kiwipete` | `thrpt` | `0.204` | `ops/us` |
| `parseFen` | `endgame` | `thrpt` | `0.615` | `ops/us` |

### Average Time (Lower is better)
| Benchmark | Position | Mode | Score | Units |
|-----------|----------|------|-------|-------|
| `parseFen` | `initial` | `avgt` | `4.637` | `us/op` |
| `parseFen` | `kiwipete` | `avgt` | `5.085` | `us/op` |
| `parseFen` | `endgame` | `avgt` | `1.670` | `us/op` |

---

## 2. Magic Bitboards Performance (`MagicBitboardsBenchmark`)

These lookups are $O(1)$ precomputed array access, showing extreme raw throughput.

### Throughput (Higher is better)
| Benchmark | Mode | Score | Units |
|-----------|------|-------|-------|
| `bishopAttacks` | `thrpt` | `0.134` | `ops/ns` |
| `rookAttacks` | `thrpt` | `0.133` | `ops/ns` |
| `queenAttacks` | `thrpt` | `0.094` | `ops/ns` |

---

## 3. Move Generator Performance (`MoveGeneratorBenchmark`)

### 3.1 `generateAllMoves` (All piece types, independent of dice)
Throughput remains consistent across dummy dice rolls (average range below):

#### Throughput (Higher is better)
| Position | Throughput (`ops/us`) | Average Time (`us/op`) |
|----------|-----------------------|------------------------|
| `initial` | `~2.55 - 2.72` | `~0.38 - 0.40` |
| `kiwipete` | `~1.39 - 1.43` | `~0.71 - 0.77` |
| `endgame` | `~3.95 - 4.05` | `~0.25 - 0.26` |
| `castling` | `~1.99 - 2.04` | `~0.51 - 0.52` |
| `promotion` | `~6.15 - 6.44` | `~0.15 - 0.17` |

### 3.2 `generateMoves` (Filtered by single Dice Roll)

#### Throughput by Dice and Position (Higher is better, `ops/us`)
| Dice Roll | Initial | Kiwipete | Endgame | Castling | Promotion |
|-----------|---------|----------|---------|----------|-----------|
| **1 (Pawn)** | `7.571` | `10.137` | `20.945` | `7.353` | `33.969` |
| **2 (Knight)** | `39.559` | `16.252` | `280.068` | `267.608` | `280.538` |
| **3 (Bishop)** | `127.385` | `14.024` | `271.818` | `267.133` | `262.691` |
| **4 (Rook)** | `123.559` | `25.623` | `19.202` | `24.438` | `255.169` |
| **5 (Queen)** | `120.882` | `15.996` | `241.622` | `241.455` | `247.172` |
| **6 (King)** | `18.101` | `6.589` | `34.434` | `6.993` | `25.452` |

#### Average Time by Dice and Position (Lower is better, `us/op`)
| Dice Roll | Initial | Kiwipete | Endgame | Castling | Promotion |
|-----------|---------|----------|---------|----------|-----------|
| **1 (Pawn)** | `0.146` | `0.102` | `0.049` | `0.143` | `0.030` |
| **2 (Knight)** | `0.025` | `0.062` | `0.004` | `0.004` | `0.004` |
| **3 (Bishop)** | `0.008` | `0.071` | `0.004` | `0.004` | `0.004` |
| **4 (Rook)** | `0.008` | `0.047` | `0.050` | `0.041` | `0.004` |
| **5 (Queen)** | `0.009` | `0.063` | `0.004` | `0.004` | `0.004` |
| **6 (King)** | `0.053` | `0.146` | `0.029` | `0.148` | `0.040` |

### 3.3 `isSquareAttacked`
Checks whether a specific square is under attack. Consistently fast across all parameters.

- **Throughput:**
  - `initial`: `~121 - 123 ops/us`
  - `kiwipete`: `~249 - 257 ops/us`
  - `endgame`: `~120 - 123 ops/us`
  - `castling`: `~120 - 124 ops/us`
  - `promotion`: `~111 - 123 ops/us`
- **Average Time:** `~0.004 - 0.008 us/op` across all positions.

---

## 4. Legal Moves Filter Performance (`LegalMovesFilterBenchmark`)

Benchmarks for `LegalMovesFilter.filterMaximalMoves` — the recursive Maximum Micro-moves algorithm.
This is the **primary optimization target**: it performs two full tree-search passes over all possible
micro-move sequences for a given set of dice.

> **Note on `~0` entries:** JMH reports throughput as unmeasurable (< 0.001 ops/us) when average time
> is in the thousands of microseconds. See Average Time table for the actual figures.

### Throughput (Higher is better, `ops/us`)

| Dice | Initial | Kiwipete | Endgame | Castling | Promotion |
|------|---------|----------|---------|----------|-----------|
| **`1,2,3`** (Pawn+Knight+Bishop) | `0.003` | `~0` | `0.243` | `0.072` | `0.194` |
| **`4,5,6`** (Rook+Queen+King) | `6.789` | `0.001` | `0.020` | `0.040` | `0.543` |
| **`1,1,1`** (3x Pawn) | `~0` | `0.002` | `0.010` | `~0` | `0.822` |
| **`6,4,2`** (King+Rook+Knight) | `0.106` | `0.001` | `0.021` | `0.040` | `0.531` |
| **`5,5,5`** (3x Queen) | `20.568` | `0.001` | `22.518` | `22.765` | `21.421` |

### Average Time (Lower is better, `us/op`)

| Dice | Initial | Kiwipete | Endgame | Castling | Promotion |
|------|---------|----------|---------|----------|-----------|
| **`1,2,3`** (Pawn+Knight+Bishop) | `436.595` | `5469.823` | `4.219` | `13.955` | `5.099` |
| **`4,5,6`** (Rook+Queen+King) | `0.154` | `757.540` | `49.935` | `24.942` | `1.901` |
| **`1,1,1`** (3x Pawn) | `3517.842` | `420.962` | `106.214` | `4537.278` | `1.233` |
| **`6,4,2`** (King+Rook+Knight) | `9.895` | `789.916` | `46.627` | `24.584` | `1.948` |
| **`5,5,5`** (3x Queen) | `0.050` | `804.434` | `0.045` | `0.045` | `0.044` |

### Key Observations

- **`kiwipete`** is the hardest position for all dice (437-5470 us/op) due to its high branching factor (~48 legal moves vs ~20 average).
- **`1,2,3` on `initial`** (436 us) and **`1,1,1` on `initial`/`castling`** (3.5-4.5 ms) are worst-case scenarios — many pawns generate deep 3-move sequences.
- **`5,5,5` (three Queens)** is fast on most positions (< 0.1 us/op) because Queen moves quickly dominate — the optimal depth is found immediately on most branches.
- **`4,5,6` on `initial`** (0.154 us/op) is the fastest realistic dice combination in the opening since King/Rook/Queen movement is heavily restricted.
