# Dice Chess Engine 🎲♟️

[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/fortemate/dicechess-engine/badge)](https://securityscorecards.dev/viewer/?uri=github.com/fortemate/dicechess-engine)
[![CI Pipeline](https://github.com/fortemate/dicechess-engine/actions/workflows/ci.yaml/badge.svg)](https://github.com/fortemate/dicechess-engine/actions/workflows/ci.yaml)
[![Architecture Docs](https://img.shields.io/badge/Docs-Architecture-orange)](https://fortemate.github.io/dicechess-engine/)
[![Scaladoc API](https://img.shields.io/badge/Scaladoc-API-blue)](https://fortemate.github.io/dicechess-engine/api/)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)

An open-source, high-performance, cross-platform game engine and AI search for **Dice Chess**, built with **Scala 3** targeting **JVM**, **JavaScript (Scala.js)**, and **WebAssembly (WasmGC)**.

---

## 📖 Dice Chess Rules & Turn Structure

Dice Chess is a stochastic chess variant where players roll **three six-sided dice** before making their moves.

### Core Concepts:
1. **The Turn Structure**:
   * A player's turn consists of **one roll of 3 dice** and **up to 3 micro-moves**.
   * The active color in the FEN **does not change** within the turn (during micro-moves). It only changes when the turn ends — after the playable dice have been consumed, or immediately when no legal moves exist (a forced pass; there is no voluntary pass).

2. **The Dice Roll**:
   * Each die determines which piece type may move:
     * `1` = Pawn (♙)
     * `2` = Knight (♘)
     * `3` = Bishop (♗)
     * `4` = Rook (♖)
     * `5` = Queen (♕)
     * `6` = King (♔)

3. **Micro-moves**:
   * Each micro-move consumes one die matching the moved piece's type. Castling consumes **two dice** — King (`6`) and Rook (`4`) — in a single move.
   * You can move **different pieces** or the **same piece** multiple times during your turn, as long as each move matches one of the remaining dice.
   * **Victory Condition**: The game is won by **capturing the opponent's king** directly (no check/checkmate).
   * **Maximum Micro-moves Rule**: Players must choose move sequences that maximize the number of dice consumed over the whole turn; any sequence that captures the opponent's king is always legal regardless of length.

---

## 🛠️ Architecture & Multi-Platform Delivery

This project compiles from a single Scala 3 codebase into three production artifacts:

* **Maven Package** (`com.fortemate:dicechess-engine_3`): Full-featured JVM JAR with JMH benchmarks, bot arena, ONNX runtime, and high-speed simulation.
* **NPM JavaScript Package** (`@fortemate/dicechess-engine`): ES Module for browsers and Node.js.
* **NPM WebAssembly Package** (`@fortemate/dicechess-engine-wasm`): WasmGC build running on modern WebAssembly runtimes.

---

## 🚀 Getting Started

### Prerequisites
- [mise](https://mise.jdx.dev/) (manages Java Temurin 25, Node.js 26, sbt, scalafmt, and tooling)

```bash
# Clone the repository
git clone https://github.com/fortemate/dicechess-engine.git
cd dicechess-engine

# Install toolchain and register git hooks
mise install
mise run setup

# Run full test suite and quality gates
mise run check

# Performance benchmarks
mise run bench          # JVM JMH benchmarks
mise run bench:js       # JavaScript (Scala.js / ES2022) on Node.js
mise run bench:wasm     # WebAssembly (Scala.js / WasmGC) on Node.js
mise run bench:all      # Cross-platform JS vs Wasm vs JVM comparison
```


---

## 🔌 API Quickstart

### JavaScript / TypeScript

Install from npmjs.org without a token or custom registry configuration:

```bash
npm install @fortemate/dicechess-engine
```

```typescript
import { DiceChess, EngineFacade } from '@fortemate/dicechess-engine';

// Generate legal micro-moves for current position and dice roll
const fen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const legalMoves = DiceChess.getLegalMoves(fen, [1, 2, 4]); // Pawn, Knight, Rook

// Find best move sequence using AI search
const bestMoves = DiceChess.getBestMove(fen, [1, 2, 4], 'greedy');
console.log('Suggested moves:', bestMoves);
```

### Java / Kotlin (JVM Facade)

```java
import dicechess.engine.jvmapi.JvmApi;
import java.util.List;

public class BotExample {
    public static void main(String[] args) {
        String fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        List<Integer> dice = List.of(1, 2, 4);

        List<String> bestSequence = JvmApi.chooseMoves(fen, dice, "greedy");
        System.out.println("Best sequence: " + bestSequence);
    }
}
```

---

## 📄 License & Contributing

- Licensed under **[AGPL-3.0](LICENSE)**.
- Contributions require signing the [Contributor License Agreement](CLA.md) (see [CONTRIBUTING.md](CONTRIBUTING.md)).
- Security vulnerability reports should follow [SECURITY.md](SECURITY.md).
