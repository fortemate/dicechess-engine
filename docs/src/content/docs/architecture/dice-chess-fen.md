---
title: "Dice Chess FEN (DFEN) Specification"
description: "Detailed specification of the modified Forsyth-Edwards Notation for Dice Chess (DFEN)."
sidebar:
  order: 6
---

To fully represent the state of a **Dice Chess** game, standard Forsyth-Edwards Notation (FEN) is insufficient. Dice Chess introduces two major mechanics that break standard FEN assumptions:

1. **Multiple En Passant Squares**: Because a player can execute up to three micro-moves in a single turn, they can perform multiple double pawn pushes (e.g., `a2-a4`, `c2-c4`, `e2-e4`). In the subsequent turn, the opponent must be able to capture *any* of these pawns on the pass.
2. **Mid-Turn Game State (Dice Pool)**: A standard turn consists of rolling three dice and performing up to three micro-moves. To support game pauses, bookmarks, and search-tree evaluations mid-turn, the FEN must encode the active player's remaining dice pool.

To address these requirements, we specify **Dice Chess FEN (DFEN)**.

---

## The DFEN Structure

DFEN extends the standard FEN to **seven space-separated fields**:

```text
<pieces> <color> <castling> <en-passant> <halfmove> <fullmove> [dice-pool]
```

Fields 1, 2, 3, 5, and 6 remain fully compatible with standard chess. Fields 4 and 7 are modified to support Dice Chess rules.

---

## Field 4: Multiple En Passant Target Squares

In standard FEN, Field 4 is either a single square (e.g. `e3`) or `-`.
In DFEN, Field 4 supports **multiple en-passant squares** through **Alphabetical Concatenation**:

- **No Active Targets**: Represented by a single dash `-`.
- **Single Active Target**: Represented by the standard algebraic notation (e.g., `e3`).
- **Multiple Active Targets**: Represented as a concatenated string of 2-character square notations, sorted alphabetically (e.g., `a3c3e3` or `b6f6`).

### Sorting & Determinism

To guarantee that logically identical positions serialize to identical DFEN strings:

* En-passant squares **must** be sorted alphabetically (e.g., `a3` < `c3` < `e3`).
* Only squares that are valid en-passant capture coordinates (the squares skipped by double-pushed pawns in the immediate previous turn) are included.

### Example (3 Double Pawn Pushes)

If White plays `a2-a4`, `c2-c4`, and `e2-e4` in one turn:

* The en-passant field becomes: `a3c3e3`
* Complete DFEN (Field 7 omitted or empty `-`): `rnbqkbnr/pppppppp/8/8/P1P1P3/8/1P1P1PPP/RNBQKBNR b KQkq a3c3e3 0 1 -`

---

## Field 7: The Dice Pool (Turn State)

Field 7 is an **optional** field that represents the active player's remaining dice pool for their current turn.

The dice values are mapped to piece types:

* `P` or `p` = Pawn (♙) = 1
* `N` or `n` = Knight (♘) = 2
* `B` or `b` = Bishop (♗) = 3
* `R` or `r` = Rook (♖) = 4
* `Q` or `q` = Queen (♕) = 5
* `K` or `k` = King (♔) = 6

### Representation Rules

- **No Dice Rolled / Fresh Turn**: Represented by a single dash `-`. This indicates that the active color has transitioned, and the player must roll the dice before making any moves.
- **Active Dice Pool**: Represented by a string of piece letters (e.g. `PNB` or `pnb`). The case must match the active color (uppercase for White, lowercase for Black). The letters must be sorted by their piece value (P < N < B < R < Q < K).

### 🔄 Five-Phase Turn State Progression (Example)

Here is how the active color and 7th field progress during a White turn where White rolls three pawns (`P, P, P`) and plays three micro-moves:

| Phase | State Description | Color Field | Dice Field | Meaning |
| :--- | :--- | :---: | :---: | :--- |
| **1** | Turn started, waiting for roll | `w` | `-` | White's turn, dice not rolled yet |
| **2** | White rolls `P, P, P` | `w` | `PPP` | White has 3 pawn moves available |
| **3** | White plays `a2-a4` | `w` | `PP` | White has 2 pawn moves remaining |
| **4** | White plays `c2-c4` | `w` | `P` | White has 1 pawn move remaining |
| **5** | White plays `e2-e4` (turn ends) | `b` | `-` | Black's turn, waiting for roll |

---

## Impact on Engine Domain & Search

### 1. `GameState` Updates

`enPassant` is represented as a high-performance `Bitboard` instead of an `Option[Square]`:

```scala
opaque type Bitboard = Long // Zero-GC 64-bit mask
```

A new field `dicePool` is introduced to `GameState`:

```scala
case class GameState(
    ...,
    mailbox: Map[Square, Piece],
    flags: GameFlags,         // Packed 29-bit int holding activeColor, dicePool (List[Int]), etc.
    enPassant: Bitboard,      // Holds multiple target squares
    ...
)
```

### 2. Move Generation & Application

* **Move Generation**: Iterates through set bits in `state.enPassant` to yield all possible en-passant capture moves.
* **Move Application**: If a micro-move is applied:
  - The played die is removed from `dicePool`.
  - If a double pawn push occurs, its target square is added (bitwise OR) to the `enPassant` bitboard.
  - If a pawn is captured (via regular or en-passant capture), its associated target square is explicitly removed from the bitboard.
* **Turn Boundaries**: En-passant targets are NOT cleared during individual micro-moves. Instead, when a player's turn formally starts, the engine calls `state.clearEnPassant(activeColor)` to wipe out their own old EP targets from previous turns, while keeping the opponent's EP targets fully intact and available for capture.

### 3. Bot & Expectimax Engine Alignment

Expectimax search transitions between states using the 7th field:

* When evaluating chance nodes (dice rolls), the engine branches on the 6 possible rolls (`1` to `6`), populating the `dicePool` field in child states.
* Maximizing/Minimizing players then search through paths matching the `dicePool` combinations, fully aligned with multiple en-passant opportunities.
