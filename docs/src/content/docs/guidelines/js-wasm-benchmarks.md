---
title: JavaScript & WebAssembly Benchmarks
description: Dedicated Node.js benchmarking suite for measuring Scala.js and WebAssembly (WasmGC) performance and comparing throughput with the JVM.
---

The Dice Chess Engine is published in three form factors:
- **JVM (`rootJVM`)**: Maven Central `com.fortemate:dicechess-engine_3`
- **JavaScript (`rootJS`)**: npmjs.org `@fortemate/dicechess-engine` (ES2022 module)
- **WebAssembly (`rootWasm`)**: npmjs.org `@fortemate/dicechess-engine-wasm` (WasmGC module)

While JVM performance is measured via JMH microbenchmarks (`benchmark/` project), execution characteristics on V8 (Node.js and Chromium browsers) differ substantially:
- **64-bit Bitboards**: Pure JavaScript emulates 64-bit integers with pairs of 32-bit values (`lo`/`hi`), whereas WebAssembly uses native `i64` instructions and CPU registers.
- **Garbage Collection & Escape Analysis**: V8's TurboFan optimizing compiler has different escape analysis heuristics and GC behavior than OpenJDK HotSpot C2.
- **Web Worker Deployment**: Both `@fortemate/dicechess-engine` and `@fortemate/dicechess-engine-wasm` run inside browser Web Workers on `dicechess-play`.

To monitor regressions and evaluate optimizations across browser targets, the engine includes a dedicated **Node.js benchmark runner** (`benchmark/node/`).

---

## Quickstart

Run benchmarks via `mise`:

```bash
# Benchmark the optimized JavaScript bundle (Scala.js / ES2022)
mise run bench:js

# Benchmark the optimized WebAssembly bundle (Scala.js / WasmGC)
mise run bench:wasm

# Run both and display a side-by-side comparison with JVM baseline
mise run bench:all
```

For quick local smoke testing or CI checks:

```bash
mise run bench:js -- --quick
mise run bench:wasm -- --quick
mise run bench:all -- --quick
```

Filter by scenario name or regex pattern:

```bash
mise run bench:js -- --filter perft
mise run bench:wasm -- --filter movegen
mise run bench:all -- --filter "kiwipete"
```

---

## Benchmark Scenarios

The benchmark runner executes standard scenarios matching the canonical positions and dice configurations defined in the JVM JMH suite (`benchmark/BASELINE.md`):

### 1. Perft (Nodes Per Second - NPS)

Measures full-tree move generation and game-state transitions (`makeMove` + `endTurn`) across canonical positions:
- **`initial`**: Standard starting position (depths 1 to 3).
- **`kiwipete`**: Complex middlegame with heavy pins and en passant (depths 1 to 3).
- **`endgame`**: Sparse king-and-rook endgame (depths 1 to 4).

### 2. Move Generator (`MoveGenerator.generateMoves`)

Measures pseudo-legal move generation throughput in ops/μs:
- **`generateAllMoves`**: Classical pseudo-legal generation across all piece types (empty dice pool).
- **`generateMoves`**: Generation filtered by single dice roll (1=Pawn, 2=Knight, 4=Rook, 5=Queen, 6=King).

### 3. Legal Moves Filter (`LegalMovesFilter.filterMaximalMoves`)

Measures the recursive Maximum Micro-moves algorithm that searches all dice permutations:
- **`1,2,3` (pnb)**: Diverse pieces (Pawn + Knight + Bishop).
- **`4,5,6` (rqk)**: Heavy pieces (Rook + Queen + King).
- **`1,1,1` (ppp)**: Homogeneous pawns (deep pawn chain branches).
- **`6,4,2` (krn)**: Castling-eligible combination.
- **`5,5,5` (qqq)**: Three Queens.

---

## V8 JIT Warmup & Measurement Harness

V8 executes JavaScript and WebAssembly using tiered compilers:
- **JavaScript (TurboFan)**: Starts in Ignition bytecode interpreter, tiers through Maglev, and stabilizes in TurboFan after repeated invocations.
- **WebAssembly (Liftoff / TurboFan)**: Rapidly baseline-compiles via Liftoff and tiers up to TurboFan in background threads.

To ensure stable, repeatable throughput without measuring compilation overhead:
1. **Adaptive Batch Sizing**: The harness probes the target operation and chooses a batch size targeting 50–100 ms per iteration, ensuring high timing resolution.
2. **Warmup Phase**: Runs 3 warmup iterations (1 in `--quick` mode) allowing TurboFan to reach steady state before metrics are captured.
3. **Measurement Phase**: Runs 5 measurement iterations (2 in `--quick` mode), computing mean throughput, standard deviation, and relative error.
4. **Dead-Code Elimination Guard**: Tight loops accumulate generated move counts into an exported return value (acting as a JMH `Blackhole`), preventing the V8 JIT from eliminating the benchmark loop.

---

## CLI Options

The runner can also be invoked directly with Node:

```bash
node benchmark/node/runner.mjs [options]
```

| Flag | Values | Description |
|:-----|:-------|:------------|
| `--target` | `js`, `wasm`, `all` | Target bundle to benchmark (default: `all`) |
| `--filter` | `<regex>` | Filter scenarios by name or ID pattern |
| `--quick` | flag | Fast run with reduced warmup/measurement iterations |
| `--format` | `table`, `markdown`, `json` | Output format (default: `table`) |
| `--bundle` | `<path>` | Explicit path to compiled `main.js` bundle |

### Example Markdown Output

Running with `--format markdown` outputs a GitHub-flavored Markdown table suitable for pasting into pull request descriptions:

```bash
node benchmark/node/runner.mjs --target all --format markdown --quick
```

---

## Performance Architecture: Why JS vs Wasm Differs

Benchmark measurements on V8 (Node.js and Chromium) reveal clear behavioral patterns across JavaScript, WebAssembly (WasmGC), and JVM targets:

### 1. 64-bit Bitboard Arithmetic: Emulation vs Native Hardware `i64`

The chess engine models boards using 64-bit masks (`type Bitboard = Long`).

* **In JavaScript**: JavaScript Numbers are IEEE 754 double-precision floats, which only represent integers precisely up to 53 bits. Standard bitwise operators (`&`, `|`, `^`, `<<`, `>>`) implicitly coerce operands to 32-bit signed integers. Scala.js represents a 64-bit `Long` as an object with two 32-bit fields (`lo` and `hi`). Every bitboard intersection, union, shift, population count (`bitCount`), and trailing zero count (`numberOfTrailingZeros`) requires multiple 32-bit operations, branch logic, and temporary allocation.
* **In WebAssembly (WasmGC)**: The Wasm specification provides first-class `i64` value types. Operations like `i64.and`, `i64.or`, `i64.xor`, `i64.shl`, `i64.ctz` (count trailing zeros), and `i64.popcnt` compile directly into single CPU instructions.

### 2. Deep Tree Search & Recursion: Where WebAssembly Excels

On compute-intensive, recursive workloads involving thousands or millions of state evaluations, WebAssembly outperforms pure JavaScript by **1.5× to 2.8×**:

* **`LegalMovesFilter.filterMaximalMoves`**:
  * On complex dice rolls like `[1,2,3]` (Pawn, Knight, Bishop) or `[1,1,1]` (triple pawn chains), Wasm executes **1.7× to 2.2× faster** than JS.
  * On king/rook/knight combinations (`[6,4,2]`), Wasm achieves up to **2.8× faster throughput**.
* **Perft Node Generation**:
  * At deeper search horizons (`kiwipete d=3`, `endgame d=4`), WebAssembly achieves **15–17 Million Nodes Per Second (NPS)**, compared to **9–14 Million NPS** in JavaScript.
  * In these deep loops, the CPU instruction cache benefits from compact Wasm bytecode and native 64-bit register allocation.

### 3. Shallow Calls & Early Exits: Where JavaScript Holds Its Ground

Conversely, on very small, non-recursive micro-operations, pure JavaScript performs identically to or slightly faster than WebAssembly:

* **Shallow `generateMoves` with Single Dice (e.g. `[1-pawn]` or `[4-rook]` on initial position)**:
  * When pieces cannot move (e.g., Queens on initial position `[5,5,5]`, which are trapped behind pawns), the method returns empty move collections within ~100–200 nanoseconds.
  * **Call Boundary Overhead**: Calling an exported WebAssembly function from JavaScript incurs cross-boundary marshaling in V8. For a 100-nanosecond operation, this boundary overhead constitutes a measurable percentage of the total runtime.
  * **TurboFan Inlining**: V8's TurboFan compiler can aggressively inline small JavaScript functions and apply *Scalar Replacement of Aggregates* to eliminate object allocations completely on the JavaScript nursery heap.

### 4. Perft NPS Scaling: Amortization of Fixed Overhead

In Perft benchmarks, Nodes Per Second (NPS) increases significantly with search depth:
* `initial d=1` (20 nodes): **~130k – 400k NPS** (~0.2 ms elapsed)
* `initial d=2` (400 nodes): **~730k – 1.9M NPS** (~0.2–0.6 ms elapsed)
* `initial d=3` (8,902 nodes): **~3.4M NPS** (~2.5 ms elapsed)
* `kiwipete d=3` (98,903 nodes): **~14M – 17M NPS** (~6–7 ms elapsed)

At depth 1, the total measured time is dominated by call dispatch and state instantiation. At depth 3 and 4, these fixed costs are amortized over hundreds of thousands of state transitions, reflecting the true steady-state throughput of the move generator.

### 5. Comparison with OpenJDK HotSpot C2 (JVM)

* **Raw Move Generation**: The JVM HotSpot C2 compiler generates move lists 3× to 10× faster on simple lookups because direct memory access to precomputed Magic Bitboard tables (`MagicBitboards.rookTable`, `bishopTable`) executes without sandbox array bounds checks.
* **Complex Filtering**: Modern WasmGC builds achieve throughput comparable to or exceeding older JVM baselines on complex multi-die filters (`LegalMovesFilter`), benefiting from lean bytecode execution free of JVM safepoint polls and thread synchronization checks.

---

## Production Deployment Guidelines

Based on these performance profiles, use the following guidance when integrating the engine in downstream services:

| Target & Use Case | Recommended Package | Rationale |
|:------------------|:--------------------|:----------|
| **Browser Web Workers (AI Bots & Search)** | `@fortemate/dicechess-engine-wasm` | 1.5×–2.8× faster on MCTS/Expectimax search trees and deep legal move permutations. Wasm binary is asynchronously compiled once when the worker spawns. |
| **Browser UI Main Thread (Interactions)** | `@fortemate/dicechess-engine` | Synchronous immediate `import`, zero WebAssembly fetch latency, instant bundle evaluation, and optimal speed for instantaneous single-move validation (`getPieceTypeAt`, `getLegalUciMoves`). |
| **Edge Compute / Cloudflare Workers** | `@fortemate/dicechess-engine` | Starts instantly without asynchronous `.wasm` asset instantiation overhead in cold-start serverless isolates. |
| **Node.js Headless Bots & Game Servers** | `@fortemate/dicechess-engine-wasm` | Maximum sustained throughput for server-side match execution and tournament simulations. |

