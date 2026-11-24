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
the search package. Everything else below (`getLegalTurnTree`, `getPlayableDice`, `threatWeight`, `getBestMove`,
bot discovery, time policies, the doubling and draw decisions, `estimateEquity`) lives on the full
entry only; `getLegalTurnTree` and `getPlayableDice` are there because they are built from
`TurnGenerator`, and `threatWeight` from `KingThreat`, which `./rules` does not carry. See [Published Artifacts](/dicechess-engine/architecture/artifacts/#the-rules-only-entry)
for the sizes and the reason the WebAssembly package has no such subpath.

## Rules configuration

Full JavaScript and WebAssembly builds accept a trailing `RulesOptions` on
[`getLegalTurnTree`](#getlegalturntree), [`getPlayableDice`](#getplayabledice),
[`applyTurn`](#applyturn), [`requiredRandomDice`](#requiredrandomdice), [`prepareRoll`](#prepareroll), [`applyMove`](#applymove),
and [`endTurn`](#endturn), on `DiceChess` and as named exports. The JavaScript
`./rules` entry accepts the same options on `applyMove` and `endTurn`.
`EngineFacade.applyMove` and `EngineFacade.endTurn` also accept those options.

All presets use the canonical `dicechesscom` board-move profile, including its en passant
rules. The Chess Hero opening preset names the opening dice policy only.

| Preset | `layout` | `rollPolicy` |
| :--- | :--- | :--- |
| Standard Dice Chess | `classical` | `standard` |
| Chess960 | `chess960` | `standard` |
| Guaranteed opening pawn | `classical` | `opening-pawn` |
| Chess960 with a guaranteed opening pawn | `chess960` | `opening-pawn` |
| Fortemate Choice | `classical` | `choice-2x2d6` |

Omitted or `undefined` options and fields use `dicechesscom`, `classical` and
`standard`. An explicit `null` configuration, unknown keys, invalid field values,
and unsupported combinations throw `TypeError`. Persist the resolved values and rules
revision with the game, plus the initial DFEN or Scharnagl number for Chess960. All
five presets use revision 1. Position 518 can have the same initial DFEN as a
classical game, so replay needs the recorded layout.

On an orthodox layout, a king-side castle from e1 is `e1g1` under classical rules.
Under Chess960 rules the token names the participating rook's square, so with the
rook on h1 it is `e1h1`. Pass the same options throughout the turn. For example,
`applyMove(dfen, 'e1', 'h1', undefined, { layout: 'chess960' })` keeps promotion
in the fourth argument.

## `DiceChess`

The primary interface for interacting with the engine from JavaScript/TypeScript.

### `getLegalUciMoves`

Returns all legal moves for a given position and a set of available dice rolls as a flat array of UCI strings (e.g., `["e2e4", "e7e8q"]`).

```typescript
function getLegalUciMoves(dfen: string): string[]
```

**Returns:** An array of full UCI move strings. If a pawn promotion is legal, the 5th character contains the target piece notation (e.g., `"e7e8q"`).

For Chess960, use the root keys of `getLegalTurnTree(dfen, { layout: 'chess960' })`.
This flat helper returns the legal *first* actions for the classical layout, and the call judges the position
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

function getLegalTurnTree(dfen: string, options?: RulesOptions): MoveTree
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
- **For the classical layout the first level equals `getLegalUciMoves(dfen)`.** Deeper levels can be narrower than
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

### `getPlayableDice`

Returns the dice that a legal turn can still spend, given the micro-moves already played. Full
entry only.

```typescript
function getPlayableDice(dfen: string, moves?: readonly string[], options?: RulesOptions): string | undefined
```

A die is **playable** while at least one legal turn that begins with `moves` spends it after them.
A client can dim every other die. No turn left can use it, and none will for the rest of the turn,
because each action played only narrows the turns that can follow.

- **`dfen` is the roll**: the position at the start of the turn with its dice, as for
  [`getLegalTurnTree`](#getlegalturntree).
- **`moves` are the micro-moves played since**, in UCI and in order: a path from the root of the
  tree. Omitted, they are none.
- **The result** is the playable dice as the DFEN dice field writes them: ascending by face
  (`PNBRQK`), upper case for White and lower case for Black. A face appears as often as the most
  dice showing it that one legal turn spends, so `"NN"` means both knight dice can be used.
- **`""`** means that no legal turn continues: the turn is complete, a king capture included, the
  roll has no legal move, or the DFEN has no dice.
- **`undefined`** means an invalid DFEN, `moves` that are not an array of UCI strings, or moves
  that are not the beginning of a legal turn.

The answer is about the whole turn, not about the next action. In the start position with the dice
queen, rook and knight, only a knight can move first. After `b1a3`, though, the rook can go
`a1b1`, and every legal turn is "knight, then rook". The rook die is therefore playable although
the rook cannot move first:

```typescript
const roll = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 QRN'
DiceChess.getPlayableDice(roll)                   // "NR": only the queen die is lost
DiceChess.getPlayableDice(roll, ['b1a3'])         // "R"
DiceChess.getPlayableDice(roll, ['b1a3', 'a1b1']) // "": the turn is complete
```

**Pass the roll and the moves, not the DFEN after them.** The Maximum Micro-moves Rule counts the
dice the whole turn could use, and a turn that takes the king ends there. The DFEN after an action
records neither. With `8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP`, `Nb3xc5` takes the king and ends the
turn, yet the DFEN it leaves still carries both pawn dice:

```typescript
const roll = '8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP'
DiceChess.getPlayableDice(roll, ['b3c5'])                      // "": the turn is over
DiceChess.getPlayableDice('8/8/8/2N5/8/8/2P5/K7 w - - 0 1 PP') // "PP": asked afresh, and wrong
```

**When to call it.** A client that already holds the tree knows the answer without a call in the two
common cases, at the roll and after each action alike:

- **The current node is empty**, so no legal turn continues: no die is playable.
- **Some path below the current node has as many actions as there are dice left.** Every action
  spends at least one die, so that path spends them all, and every die left is playable.

Only in between does it need the call: with three dice, when the longest path below the current node
has one or two actions. A castling path can have fewer actions than the dice it spends, and then the
call simply answers that every die is playable.

Each call enumerates legal turns from the original roll. Chess960 uses the classical traversal
when no continuation can castle. A third die can clear the castling path during the turn.
When a castle is possible, any matching path that spends the whole roll establishes that all
dice left after the prefix are playable.

---

### `applyTurn`

```typescript
function applyTurn(dfen: string, moves: readonly string[], options?: RulesOptions): string | undefined
```

Apply an exact complete path from the rolled DFEN using the selected layout's
UCI notation. An empty path is accepted for a rolled forced pass. A successful
turn returns DFEN with the dice cleared. The opponent becomes active after
a normal turn or pass. After a king capture the winner stays active. An invalid
DFEN or an illegal or incomplete path yields `undefined`.

### `requiredRandomDice`

Call `DiceChess.requiredRandomDice(firstTurnForPlayer, options)` before drawing faces. A standard turn
needs three random faces. An opening-pawn turn needs two on that player's first turn and three thereafter.
Choice needs four to form its two ordered offers. The full bundles also export this function by name.

### `prepareRoll`

```typescript
type PreparedRoll =
  | { kind: 'pool'; dice: number[] }
  | { kind: 'offers'; first: number[]; second: number[] }

function prepareRoll(
  randomFaces: readonly number[],
  firstTurnForPlayer: boolean,
  options?: RulesOptions,
): PreparedRoll | undefined
```

The host draws independent faces from 1 through 6 and tracks whether the active player
is taking their first turn, including across a pass, resume, and replay. `prepareRoll`
deterministically formats those draws. It returns `undefined` if the faces, dice count,
or first-turn flag are invalid.

- `standard` takes three faces on every turn and returns a playable pool.
- `opening-pawn` takes two faces on each player's first turn and adds one fixed pawn die.
  Both drawn faces are retained, including duplicate pawns. The entire pool follows
  the ordinary maximal-use rule. Later turns take three random faces.
- `choice-2x2d6` takes four faces in draw order. The first two form the first offer.
  The last two form the second offer. Commit one whole pair, at index 0 or 1, before
  play. Either pair can be selected, including one that forces a pass.

```javascript
const options = { rollPolicy: 'choice-2x2d6' };
const roll = DiceChess.prepareRoll([3, 4, 1, 2], true, options);
if (roll?.kind === 'offers') {
  const pairs = [roll.first, roll.second];
  const selectedIndex = 1;
  const pool = pairs[selectedIndex]; // [1, 2], a pawn and a knight
}
```

Record the drawn faces, the player's opening entitlement, and the selected index
when applicable. Keep both offers in order even when they are equal. Use the
realized pool as the DFEN dice field for gameplay. The move APIs use only those
DFEN dice.

---

### `threatWeight`

Returns the total weight (0 to 216) of ordered dice rolls that allow the active color to capture the king on `targetSquare` within up to `maxActions` actions (1, 2, or 3). Full entry only.

```typescript
function threatWeight(dfen: string, targetSquare: string, maxActions?: number): number
```

- **`dfen`**: The starting board state in DiceChess FEN notation before the roll.
- **`targetSquare`**: The algebraic coordinate of the target king square (e.g., `"e8"`, `"f3"`). Returns `0` if the square does not hold the opponent's king or is invalid.
- **`maxActions`**: Search depth bound in actions (1, 2, or 3; defaults to 3). Non-positive values return `0`, and values above 3 are clamped to 3.
- **Returns:** An integer `0` to `216` measuring the threat weight out of all 216 equally probable ordered outcomes of three standard dice (`6 * 6 * 6`).

**How it works.** Unlike tree enumeration, `threatWeight` runs an exact, low-allocation multi-action search (`KingThreat`) directly on the engine's bitboards. It traverses depth 1 and depth 2 using a 56-bit coverage bitmask for roll multisets, prunes branches whose roll combinations are already covered, caches transpositions via an in-place hash set, and verifies terminal king captures using magic bitboards and leaper attack masks without generating full move objects.

On resource-constrained hardware (e.g. low-power smart TV sticks), a full 3-action search via `threatWeight` has a measured median latency of ~11 ms (p90: 20.2 ms, max: 30.7 ms under `node --jitless` across 300 positions), making it suitable for calculating turn-by-turn danger levels and driving dynamic audio cues without freezing the UI thread.

```typescript
// Evaluate whether White threatens Black's king on d7 within 3 actions
const weight = DiceChess.threatWeight('r5r1/2pk2R1/p1nb4/1p4P1/NPP5/3PK3/P2BP3/R5N1 w - - 3 13', 'd7', 3);
// weight is 105 (out of 216)
```

---

### `applyMove`

Applies a **micro-move** to the given DFEN and returns the resulting board state. 
This function acts as the **Single Source of Truth** for chess rules, ensuring correct handling of castling rights, en passant, and pawn promotions.

:::note
Because a Dice Chess turn consists of multiple micro-moves, `applyMove` **does not** transition the turn to the opponent. The active color and full-move number remain unchanged. To formally end a turn, you must call `endTurn`.
:::

```typescript
function applyMove(dfen: string, from: string, to: string, promotion?: string, options?: RulesOptions): string | undefined
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

:::danger[Behaviour change (#279)]
Up to 0.12.3, `applyMove` emptied the dice pool after every move and did not check the dice, and clients
removed the played die themselves. A client that appends or re-attaches the remaining dice after
`applyMove` must stop doing so, and a move the dice do not allow now returns `undefined`.
:::

---

### `endTurn`

Explicitly ends the current player's turn. This function is critical for the micro-move architecture. It performs three vital operations:
1. Toggles the active color to the opponent.
2. Increments the full-move number (if the current player was Black).
3. Clears any stale *en-passant* targets from the previous turn, preventing illegal captures.

```typescript
function endTurn(dfen: string, options?: RulesOptions): string | undefined
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

### `registerTacticianBot`

Registers an experimental tactical search under an unused bot id. The host must
supply every profile field; the engine provides no default profile.

```typescript
interface TacticianConfig {
    candidateLimit: number;
    kingThreatCp: number;
    kingDangerCp: number;
    hangingWeight: number;
}

function registerTacticianBot(config: TacticianConfig, newBotId: string, newBotName: string): boolean
```

`candidateLimit` must be a positive integer; both centipawn fields must be
nonnegative integers. These integer fields must fit a signed integer.
`hangingWeight` must be finite and nonnegative. The id and name must be nonblank.
Malformed profiles and occupied ids return `false`, leaving existing bots intact.
Successful registration returns `true` and exposes the bot through
`getAvailableBots()` with `isExperimental: true`; pass its id to `getBestMove`.

The candidate limit bounds tactical rescoring after cheap move ranking. Legal
turn generation and the overall call remain synchronous, with no wall-clock
deadline or UI responsiveness guarantee.

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

- `options.algorithm`: The bot ID to use. Built-in algorithms are `"random"`, `"random-king-capture"`, `"checkmate-aware"`, `"greedy"`, `"greedy-v2"`, `"aggressive"`, and `"monte-carlo"`. Defaults to `"greedy"`.
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

### Chess960 DFEN interchange

Use the named export `normalizeChess960Dfen(dfen)` from the engine, rules, or WebAssembly module to read and write
Chess960 castling rights in DFEN. On each back rank, `K` and `Q` select the outermost friendly
rook to the right and left of the king respectively. A file letter selects a specific rook.
White uses uppercase and Black lowercase. For example, with a white king on e1 and white rooks
on f1 and h1, `K` selects h1 and `F` selects f1. Output uses the shorthand whenever it
identifies the selected rook. An absent rook or a second right on the same side is invalid.

This operation returns canonical DFEN or `undefined` on invalid input. Scala callers can use
`FenParser.parseChess960(dfen)` to inspect the eligible rook squares and serialize the snapshot.
The JVM facade exposes `JvmApi.normalizeChess960Dfen(dfen)`. Select
`{ layout: 'chess960' }` in the configurable gameplay APIs. The Chess960-specific
exports below also remain available.

The named export `chess960StartingDfen(number)` builds a reproducible starting position from a
Scharnagl number (0 through 959). Position 518 is the classical array. White is to roll, both
players retain both castling rights, and the dice field is absent. Invalid numbers yield
`undefined`.

The named export `applyChess960Castle(dfen, rook)` applies one pseudo-legal castling micro-move.
Pass the participating rook's square, for example `"h1"`. Both a king die and a rook die are
required, including when one piece already occupies its destination. Both paths must be clear
of other pieces. Dice Chess allows castling through attacked squares. The result keeps the
active player and the remaining dice. An unavailable castle yields `undefined`.
Whole-turn maximality belongs to the turn generator.

Use the named export `getChess960LegalTurnTree(dfen)` from the full JavaScript or WebAssembly package to
follow a complete turn from its initial roll. The keys are UCI moves. A castle names the king's start
and the participating rook's start (for example, `f1g1` when the two pieces swap). Every leaf
captures the opposing king or spends the maximum available dice across all paths. The empty tree
covers invalid input, a captured king, an absent roll and a forced pass. JVM callers can enumerate the same paths
with `JvmApi.chess960LegalTurnPaths(dfen)`, which returns lists of UCI move strings. The JVM method
throws `IllegalArgumentException` for invalid DFEN and returns an empty list for a finished game, an absent roll or a forced pass.

The tree uses the same nested `MoveTree` shape as [`getLegalTurnTree`](#getlegalturntree), with
children sorted in UCI order and an empty object at each terminal path.

The full JavaScript and WebAssembly packages also export `applyChess960Turn(dfen, moves)`. It
accepts a rolled DFEN and a complete legal path of UCI strings, returning the canonical DFEN
with Chess960 rook rights updated. Invalid input or an illegal or incomplete path returns
`undefined`. An empty path is accepted only for a forced pass on a rolled position. A king capture
clears the dice and keeps the mover active. Other legal paths hand the turn to the opponent.

```javascript
import { applyChess960Turn } from '@fortemate/dicechess-engine';

const dfen = '6k1/8/8/8/8/8/P7/5KR1 w K - 0 1 KRP';
const next = applyChess960Turn(dfen, ['f1g1', 'a2a4']);
// '6k1/8/8/8/P7/8/8/5RK1 b - a3 0 1'
```

For the intermediate DFEN immediately after a castle, use `applyChess960Castle`.
