---
title: JavaScript API (DiceChess)
description: Reference documentation for the Dice Chess Engine JavaScript API.
---

The Dice Chess Engine exposes the `DiceChess` object to JavaScript consumers (like the `dicechess-lab` PWA frontend). This API provides functions for move generation, validation, and game state transitions.

## `DiceChess`

The primary interface for interacting with the engine from JavaScript/TypeScript.

### `getLegalUciMoves`

Returns all legal moves for a given position and a set of available dice rolls as a flat array of UCI strings (e.g., `["e2e4", "e7e8q"]`).

```typescript
function getLegalUciMoves(dfen: string): string[]
```

**Returns:** An array of full UCI move strings. If a pawn promotion is legal, the 5th character contains the target piece notation (e.g., `"e7e8q"`).

---

### `applyMove`

Applies a **micro-move** to the given DFEN and returns the resulting board state. 
This function acts as the **Single Source of Truth** for chess rules, ensuring correct handling of castling rights, en passant, and pawn promotions.

> [!NOTE]  
> Because a Dice Chess turn consists of multiple micro-moves, `applyMove` **does not** transition the turn to the opponent. The active color and full-move number remain unchanged. To formally end a turn, you must call `endTurn`.

```typescript
function applyMove(dfen: string, from: string, to: string, promotion?: string): string | undefined
```

**Returns:** The updated FEN string after the move is applied, or `undefined` if the move is pseudo-illegal.

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

See [Time Management](/dicechess-engine-scala/architecture/search/05-time-management/) for the
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
