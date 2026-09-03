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
