---
title: JavaScript API (DiceChess)
description: Reference documentation for the Dice Chess Engine JavaScript API.
---

The Dice Chess Engine exposes the `DiceChess` object to JavaScript consumers (like the `dicechess-lab` PWA frontend). This API provides functions for move generation, validation, and game state transitions.

## Two entry points

The npm package has two entries, and every function on this page that needs only the rules is on both:

```javascript
import { DiceChess } from '@fortemate/dicechess-engine';       // everything
import { DiceChess } from '@fortemate/dicechess-engine/rules'; // rules only, ~420 KB smaller
```

The `./rules` subpath carries `getLegalUciMoves`, `generateMoves`, `applyMove`, `endTurn`, `perft`,
`getPieceFromDice` and `canonicalKey` — identical in name and behaviour — and nothing that reaches
the search package. Everything else below (`getLegalTurnTree`, `getBestMove`, bot discovery, time
policies, the doubling and draw decisions, `estimateEquity`) lives on the full entry only;
`getLegalTurnTree` is there because it is built from `TurnGenerator`, which `./rules` does not
carry. See [Published Artifacts](/dicechess-engine/architecture/artifacts/#the-rules-only-entry)
for the sizes and the reason the WebAssembly package has no such subpath.

## `DiceChess`

The primary interface for interacting with the engine from JavaScript/TypeScript.

### `getLegalUciMoves`

Returns all legal moves for a given position and a set of available dice rolls as a flat array of UCI strings (e.g., `["e2e4", "e7e8q"]`).

```typescript
function getLegalUciMoves(dfen: string): string[]
```

**Returns:** An array of full UCI move strings. If a pawn promotion is legal, the 5th character contains the target piece notation (e.g., `"e7e8q"`).

These are the legal *first* actions of a turn from this position, and the call judges the position
in isolation. Asked again after each micro-move, it no longer knows how many dice the whole turn
could have used, so it can admit a continuation the turn does not allow. To follow a turn one action
at a time, walk [`getLegalTurnTree`](#getlegalturntree) instead.

---

### `getLegalTurnTree`

Returns every legal turn of the rolled position as a prefix tree of UCI micro-moves. Full entry
only.

```typescript
interface MoveTree {
    [uci: string]: MoveTree
}

function getLegalTurnTree(dfen: string): MoveTree
```

The tree is made of plain nested objects keyed by micro-move, with children in UCI order — the
shape of dicechess-play-api's `MoveTree`, so `JSON.stringify` gives the same string the server
sends for the same roll:

```json
{ "e2e3": { "e3e4": {} }, "e2e4": { "e4e5": {} } }
```

- **A node with no children is a complete legal turn**, and every complete legal turn is such a
  leaf. A turn that captures the king always ends at a leaf, even when dice remain; every other
  turn spends the most dice the roll allows (the
  [Maximum Micro-moves Rule](/dicechess-engine/architecture/move-generation/05-maximum-micromoves/),
  castling counting as two).
- **The first level equals `getLegalUciMoves(dfen)`.** Deeper levels can be narrower than
  `getLegalUciMoves` asked again after each micro-move.
- **An empty object** means the roll has no legal move (the player passes). An invalid DFEN or a
  DFEN without dice also returns `{}`.

The difference matters in positions where a first action is legal only because a later one takes
the king. With `8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP`, `c2c4` is legal because `Nb3xc5` follows;
after it, `getLegalUciMoves` admits every knight move, but a quiet one would end a two-dice turn
while `c2c3, c3c4, Nb3xc5` uses all three. The tree offers `b3c5` alone:

```typescript
const tree = DiceChess.getLegalTurnTree('8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP')
Object.keys(tree.c2c4)  // ["b3c5"]
```

A client walks the tree alongside [`applyMove`](#applymove): play an action that is a key of the
current node and descend into its child. An empty child completes the turn: call `endTurn`, unless
the last action captured the king, which ends the game.

---

### `applyMove`

Applies a **micro-move** to the given DFEN and returns the resulting board state. 
This function acts as the **Single Source of Truth** for chess rules, ensuring correct handling of castling rights, en passant, and pawn promotions.

> [!NOTE]  
> Because a Dice Chess turn consists of multiple micro-moves, `applyMove` **does not** transition the turn to the opponent. The active color and full-move number remain unchanged. To formally end a turn, you must call `endTurn`.

```typescript
function applyMove(dfen: string, from: string, to: string, promotion?: string): string | undefined
```

**Returns:** The updated DFEN after the move is applied, or `undefined` if the move is pseudo-illegal, no die in the pool allows it, or an argument is invalid.

The returned DFEN keeps the dice the move did not spend, so the next call sees exactly the dice the
rest of the turn may use: playing `e2e4` from a position with `PPN` leaves `PN`, and castling spends
both the king and the rook die. Pass it on unchanged: while dice remain, appending dice to it gives an
eight-field DFEN, which every function rejects.

When the DFEN carries dice, the move must spend one of them. A move that no die in the pool allows —
a pawn move with dice `NNN`, or castling without a rook die — returns `undefined`, as a pseudo-illegal
move does. A position without dice, such as a board editor's, accepts any pseudo-legal move and stays
without dice.

`applyMove` checks the dice, not the whole turn. It does not know which dice the turn started with,
so it cannot apply the Maximum Micro-moves Rule, and once the last die is spent the DFEN it returns has
no dice: DFEN cannot tell spent dice from dice not yet rolled, so a further move on it is accepted.
Walk [`getLegalTurnTree`](#getlegalturntree), and call `endTurn` at an empty child.

> [!CAUTION]
> **Behaviour change ([#279](https://github.com/fortemate/dicechess-engine/issues/279)).** Up to
> 0.12.3, `applyMove` emptied the dice pool after every move and did not check the dice, and clients
> removed the played die themselves. A client that appends or re-attaches the remaining dice after
> `applyMove` must stop doing so, and a move the dice do not allow now returns `undefined`.

---

### `endTurn`

Explicitly ends the current player's turn. This function is critical for the micro-move architecture. It performs three vital operations:
1. Toggles the active color to the opponent.
2. Increments the full-move number (if the current player was Black).
3. Clears any stale *en-passant* targets from the previous turn, preventing illegal captures.

```typescript
function endTurn(dfen: string): string | undefined
```

**Returns:** The updated FEN string for the next player's turn, or `undefined` if the FEN is invalid.

---

### `getAvailableBots`

Returns all available bots (search algorithms) supported by the engine.

```typescript
function getAvailableBots(): {
    id: string,
    name: string,
    description: string,
    difficulty: number,
    isExperimental: boolean
}[]
```

**Returns:** An array of bot metadata objects, which can be used to dynamically populate UI selection menus.

---

### `getAvailableTimePolicies`

Returns the stable IDs of the built-in time-management policies.

```typescript
type TimePolicyId = "empirical-v1" | "legacy-linear-v1"

function getAvailableTimePolicies(): TimePolicyId[]
```

`"empirical-v1"` is calibrated from production Dice Chess games and is the default.
`"legacy-linear-v1"` preserves the original chess-inspired allocation for rollback and A/B tests.

---

### `getBestMove`

Computes the best sequence of micro-moves for the given position and available dice using the engine's search algorithms.

```typescript
interface ClockStateOptions {
    remainingMs: number
    incrementMs?: number
    moveNumber?: number
    movesToGo?: number
}

interface BestMoveOptions {
    algorithm?: string
    clock?: ClockStateOptions
    timePolicy?: TimePolicyId
    timeBudgetMs?: number
}

function getBestMove(dfen: string, options?: BestMoveOptions): {
    moves: { from: string, to: string, promotion?: string }[],
    score: number,
    timeTakenMs: number,
    budgetMs: number
}
```

- `options.algorithm`: The bot ID to use. Built-in algorithms are `"random"`, `"checkmate-aware"`, `"greedy"`, `"greedy-v2"`, `"aggressive"`, and `"monte-carlo"`. Defaults to `"greedy"`.
- `options.clock`: The live game clock. The engine converts it into a per-turn budget for algorithms that support deadlines.
- `options.timePolicy`: The allocation policy to use with `clock`. Defaults to `"empirical-v1"`; an unknown ID also falls back to the default.
- `options.timeBudgetMs`: An advanced precomputed per-turn budget. It bypasses time management and is ignored when a valid `clock` is present; malformed or non-finite clocks fall back to this value.
- `budgetMs`: The effective search budget. It is `0` when no time budget was applied.

```typescript
const result = DiceChess.getBestMove(dfen, {
    algorithm: "monte-carlo",
    clock: {
        remainingMs: 180_000,
        incrementMs: 2_000,
        moveNumber: 8
    },
    timePolicy: "empirical-v1"
})
```

See [Time Management](/dicechess-engine/architecture/search/05-time-management/) for the
allocation formula, safeguards, and guidance on selecting a policy.


---


### `getPieceFromDice`

Returns the piece type notation associated with a dice roll.

```typescript
function getPieceFromDice(dice: number): string | null
```

* `1` → `"p"` (Pawn)
* `2` → `"n"` (Knight)
* `3` → `"b"` (Bishop)
* `4` → `"r"` (Rook)
* `5` → `"q"` (Queen)
* `6` → `"k"` (King)

---

### Doubling Cube Functions

#### `shouldBotOfferDouble`

Evaluates whether the bot should offer a double before its turn.

```typescript
function shouldBotOfferDouble(dfen: string, currentStake: number, options?: { algorithm?: string }): boolean
```

- `dfen`: The current game state in DFEN format.
- `currentStake`: The current stake value.
- `options.algorithm`: The bot ID to use for evaluation. Defaults to `"greedy"`.

#### `shouldBotAcceptDouble`

Evaluates whether the bot should accept a double offered by the opponent.

```typescript
function shouldBotAcceptDouble(dfen: string, newStake: number, options?: { algorithm?: string }): boolean
```

- `dfen`: The current game state in DFEN format.
- `newStake`: The new stake value after accepting the double.
- `options.algorithm`: The bot ID to use for evaluation. Defaults to `"greedy"`.

---

### Draw Offer Functions

#### `shouldBotOfferDraw`

Evaluates whether the bot should offer a draw.

```typescript
function shouldBotOfferDraw(dfen: string, options?: { algorithm?: string }): boolean
```

- `dfen`: The current game state in DFEN format.
- `options.algorithm`: The bot ID to use for evaluation. Defaults to `"greedy"`.

#### `shouldBotAcceptDraw`

Evaluates whether the bot should accept a draw offered by the opponent.

```typescript
function shouldBotAcceptDraw(dfen: string, options?: { algorithm?: string }): boolean
```

- `dfen`: The current game state in DFEN format.
- `options.algorithm`: The bot ID to use for evaluation. Defaults to `"greedy"`.
