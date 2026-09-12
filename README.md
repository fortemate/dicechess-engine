# Dice Chess Engine 🎲♟️

[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/fortemate/dicechess-engine/badge)](https://securityscorecards.dev/viewer/?uri=github.com/fortemate/dicechess-engine)
[![CI Pipeline](https://github.com/fortemate/dicechess-engine/actions/workflows/ci.yaml/badge.svg)](https://github.com/fortemate/dicechess-engine/actions/workflows/ci.yaml)
[![Architecture Docs](https://img.shields.io/badge/Docs-Architecture-orange)](https://fortemate.github.io/dicechess-engine/)
[![Scaladoc API](https://img.shields.io/badge/Scaladoc-API-blue)](https://fortemate.github.io/dicechess-engine/api/)
[![License: AGPL-3.0-only](https://img.shields.io/badge/License-AGPL--3.0--only-blue.svg)](LICENSE)

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

This project compiles from a single Scala 3 codebase into four published artifacts, all at one version per release:

* **Maven rules artifact** (`com.fortemate:dicechess-rules_3`, from 0.11.0): the rules of the game only — board and DFEN model, move generation, legal turn enumeration, dice probabilities. Depends on the Scala standard library and nothing else.
* **Maven engine artifact** (`com.fortemate:dicechess-engine_3`): bots, evaluators, feature extractors and the Java/Kotlin facade `JvmApi`. Depends on `dicechess-rules_3` of the same version; `onnxruntime` is an optional dependency that ONNX consumers declare themselves. The JMH benchmarks, the bot arena and the CLI live in this repository but are not part of the jar.
* **NPM JavaScript Package** (`@fortemate/dicechess-engine`): ES Module for browsers and Node.js.
* **NPM WebAssembly Package** (`@fortemate/dicechess-engine-wasm`): WasmGC build running on modern WebAssembly runtimes.

What each artifact contains, what is deliberately left out and how to pick or switch a coordinate: [Published Artifacts & Rules-Only Migration](https://fortemate.github.io/dicechess-engine/architecture/artifacts/).

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
import { DiceChess } from '@fortemate/dicechess-engine';

// A Dice Chess FEN (DFEN) carries the rolled dice as a 7th field: "PN" = a Pawn and a Knight die.
const dfen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PN';

// Every legal micro-move for this position and roll, as UCI strings
const legalMoves = DiceChess.getLegalUciMoves(dfen); // e.g. ["e2e3", "e2e4", "b1c3", ...]

// Play one micro-move, then close the turn once the dice are spent
const afterMove = DiceChess.applyMove(dfen, 'e2', 'e4');
const nextTurn = DiceChess.endTurn(afterMove);

// Ask a built-in bot for its turn (ids from DiceChess.getAvailableBots())
const bot = DiceChess.getBestMove(nextTurn, { algorithm: 'greedy' });
console.log('Bot plays:', bot.moves, 'score', bot.score);
```

The full surface (`getAvailableBots`, clock-aware `getBestMove`, doubling and draw decisions, `estimateEquity`) is documented in the [JavaScript API Reference](https://fortemate.github.io/dicechess-engine/architecture/javascript-api/).

### Java / Kotlin (JVM Facade)

`JvmApi` ships in `com.fortemate:dicechess-engine_3` (Maven Central); rules-only JVM projects use `dicechess-rules_3` and call the Scala API directly.

```java
import dicechess.engine.domain.GameState;
import dicechess.engine.jvmapi.JvmApi;
import java.util.List;

public class BotExample {
    public static void main(String[] args) {
        GameState state = JvmApi.parseDfen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        GameState rolled = JvmApi.withDice(state, List.of(1, 2, 4)); // Pawn, Knight, Rook

        JvmApi.bestTurn(rolled, "greedy").ifPresentOrElse(
            turn -> System.out.println("Best turn: " + turn.uci() + " score " + turn.score()),
            () -> System.out.println("No legal turn for this roll: forced pass"));
    }
}
```

The facade's contract — `legalTurns`, `evaluate`, thinking-time budgets, game-over queries — is documented in the [JVM API Reference](https://fortemate.github.io/dicechess-engine/architecture/jvm-api/).

---

## 📄 License & Contributing

- Licensed under **[AGPL-3.0-only](LICENSE)** (SPDX `AGPL-3.0-only`; the GNU Affero General Public License version 3, without the "or any later version" option).
- Contributions require signing the [Contributor License Agreement](CLA.md) (see [CONTRIBUTING.md](CONTRIBUTING.md)).
- Security vulnerability reports should follow [SECURITY.md](SECURITY.md).
