# @fortemate/dicechess-engine 🎲♟️

High-performance, cross-platform game engine and move generator for **Dice Chess**, compiled to pure Javascript (ES Modules) with full TypeScript type declarations.

Designed for maximum performance in web browsers, hybrid apps, and Node.js servers.

---

## Installation

Install the package via npm or your preferred package manager:

```bash
npm install @fortemate/dicechess-engine
```

---

## Quick Start (JavaScript / TypeScript)

The package is distributed as a standard ES Module (`type: "module"`).

```javascript
import { DiceChess } from '@fortemate/dicechess-engine';

// Dice Chess FEN (DFEN) encodes the board state and the current dice pool as the 7th field
// (e.g. "PN" represents a rolled Pawn (P) and Knight (N))
const dfen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PN";

// 1. Get all legal moves as a flat array of UCI strings for the DFEN position
const legalMoves = DiceChess.getLegalUciMoves(dfen);

console.log("Legal moves in this turn:", legalMoves);
// e.g. ["e2e3", "e2e4", "b1c3", "b1a3", ...]

// 2. Apply a micro-move. Note that applyMove preserves the active color
// (White) since a Dice Chess turn may consist of multiple micro-moves.
// Arguments: (dfen, fromSquare, toSquare, optionalPromotionPiece)
const nextDfen = DiceChess.applyMove(dfen, "e2", "e4");
console.log("DFEN after micro-move:", nextDfen);

// 3. Explicitly end the turn when the player has exhausted their dice.
// This toggles the active color to Black, increments the move counter,
// and clears any stale en-passant targets from the previous turn.
const finalDfen = DiceChess.endTurn(nextDfen);
console.log("DFEN after ending turn:", finalDfen);

// 4. Discover available bots (search algorithms)
const bots = DiceChess.getAvailableBots();
console.log("Available bots:", bots);
// e.g. [ { id: 'random', name: 'Random', description: '...', difficulty: 1, isExperimental: false }, ... ]

// 5. Discover the built-in time-management policies.
const timePolicies = DiceChess.getAvailableTimePolicies();
console.log("Available time policies:", timePolicies);
// [ "empirical-v1", "legacy-linear-v1" ]

// 6. Compute the best sequence of micro-moves using the greedy bot search
// Arguments: (dfen, optionalOptions)
const botResult = DiceChess.getBestMove(finalDfen, { algorithm: "greedy" });
console.log("Bot moves:", botResult.moves);
// e.g. [ { from: "g8", to: "f6" } ]

// Clock-aware searches use empirical-v1 by default. Pass legacy-linear-v1 to
// reproduce the original allocation for rollback or an A/B comparison.
const timedResult = DiceChess.getBestMove(finalDfen, {
  algorithm: "monte-carlo",
  clock: { remainingMs: 180_000, incrementMs: 2_000, moveNumber: 8 },
  timePolicy: "empirical-v1",
});
console.log("Effective budget (ms):", timedResult.budgetMs);

// 7. Doubling Cube & Draw Offers (New)
// Check if the bot wants to offer a double before its turn:
const shouldDouble = DiceChess.shouldBotOfferDouble(finalDfen, 1);

// Check if the bot accepts a double proposed by the opponent (Take/Drop):
const acceptDouble = DiceChess.shouldBotAcceptDouble(finalDfen, 2);

// Check if the bot wants to offer a draw:
const offerDraw = DiceChess.shouldBotOfferDraw(finalDfen);

// Check if the bot accepts a draw proposed by the opponent:
const acceptDraw = DiceChess.shouldBotAcceptDraw(finalDfen);

```

---

## Features

* **Complete Dice Chess Rules**: Implements turn sequencing, unblocking, promotion path calculations, and strict **Maximal Micro-moves Filtering**.
* **Type-Safe**: Shipped with comprehensive TypeScript definition files (`.d.ts`) generated directly from the Scala compiler.
* **Side-Effect Free**: Configured with `sideEffects: false` to allow advanced tree-shaking in modern bundlers like Vite, Webpack, and Rollup.
* **Blazing Fast**: Optimized bitwise operations compiled via the advanced Scala.js optimizing linker.

---

## API Reference & Documentation

* **[Interactive User & Developer Guide](https://jc.id.lv/dicechess-engine-scala/)**: Complete rules, architectural documentation, and live interactive visual catalogs.
* **[GitHub Repository](https://github.com/rabestro/dicechess-engine-scala)**: Source code, issue tracker, and contribution guidelines.

---

## License

This package is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
