---
title: Pawn Generation
description: Generating pawn pushes and captures in parallel using Bitboard shifts.
sidebar:
  order: 3
---

Unlike Knights and Kings which are evaluated square-by-square, pawns of the same color can be shifted as an entire front line simultaneously using Bitboard arithmetic. 

This parallel calculation takes full advantage of the CPU's ALU (Arithmetic Logic Unit) to compute moves for up to 8 pawns in a small, constant number of bitboard operations.

## Parallel Forward Pushes

To find all squares where white pawns can push forward by one step, we simply take the bitboard representing all white pawns and shift it "Up" (`<< 8` bits).

However, pawns cannot push into occupied squares. We apply a bitwise `AND` (`&`) with the board's `emptySquares` mask to instantly filter out blocked pawns:

```scala
// Computes single pushes for all White pawns instantly:
val pushes = (whitePawns << 8) & emptySquares
```

### Double Pushes

A pawn can double-push only if:
1. It is currently on its starting rank (Rank 2 for White, Rank 7 for Black).
2. The square immediately in front of it is empty.
3. The destination square is also empty.

For White, we solve this by taking the result of the `singlePushes` calculation, masking it with `Rank3` (meaning these pawns successfully moved one step and are now on Rank 3), and shifting it one more time:

```scala
val doublePushes = ((singlePushes & Rank3Mask) << 8) & emptySquares
```

(For Black, the calculation mirrors this using `singlePushes & Rank6Mask` shifted right by 8 bits).

## Diagonal Captures

Pawns capture diagonally. Similar to [Leaper Attacks](/dicechess-engine/architecture/move-generation/02-leapers/), shifting pawns diagonally across the edge of the board can result in illegal wrap-around captures (e.g. an H-file pawn capturing a piece on the A-file of the next rank).

To prevent this, we use the same **Not-File Masks**. We also restrict the generated attacks by `AND`ing them with the bitboard of `enemyPieces`:

```scala
// Capturing East (Right) for White
val eastCaptures = ((whitePawns & NotHFile) << 9) & enemyPieces

// Capturing West (Left) for White
val westCaptures = ((whitePawns & NotAFile) << 7) & enemyPieces
```

## En-Passant Under Multi-Move Turns

In standard chess, en-passant capture is strictly instantaneous: it is available only on the move immediately following the opponent's double pawn push.

In Dice Chess, a turn consists of up to three micro-moves:
1. **Adjacency Requirement (FIDE 3.7.4 & DiceChess.com)**: Only pawns that were already horizontally adjacent to the passed pawn at the start of the turn (when the double-push occurred) are eligible to capture en-passant. If a pawn advances to the adjacent rank during subsequent micro-moves of the same turn (e.g. `f7-f5`, `f5-f4` beside an enemy `e4` pawn pushed on the previous turn), it was never passed by the enemy pawn and **cannot** capture en-passant (`f4xe3` is illegal).
2. **Persistence Throughout the Turn**: An eligible pawn may execute the en-passant capture on micro-move 1, 2, or 3, as long as the en-passant target square remains valid (#351).
3. **DFEN Memorylessness**: Because DFEN is memoryless and does not record historical pawn positions prior to previous micro-moves, regenerating or evaluating an intermediate DFEN in isolation recomputes adjacency from the current board state and could falsely admit an originally ineligible pawn. Clients must therefore walk the prefix turn tree (`getLegalTurnTree`) or preserve the turn's initial eligibility mask across micro-moves.

## Pawn Promotion

In Dice Chess, when a pawn reaches the back rank (Rank 8 for White, Rank 1 for Black), it must be promoted.

Our generator handles this by splitting the candidate target bitboards (from pushes or captures) into two sets using rank masks:

```scala
// Mask for White promotion (Rank 8)
val push1Prom = push1 & Rank8Mask
val push1Std  = push1 & ~Rank8Mask
```

### Multiple Move Generation
For every square in the `promotionSquares` bitboard, the engine emits **four distinct moves**, one for each possible promotion piece:
- Queen Promotion
- Rook Promotion
- Bishop Promotion
- Knight Promotion

This ensures the move search algorithm can evaluate the trade-offs of different promotion choices. Since these are separate `MicroMove` objects, they are treated as unique options in the game tree.
