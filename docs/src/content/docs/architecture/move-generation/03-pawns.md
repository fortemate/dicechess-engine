---
title: Pawn Generation
description: Generating pawn pushes and captures in parallel using Bitboard shifts.
sidebar:
  order: 3
---

Unlike Knights and Kings which are evaluated square-by-square, pawns of the same color can be shifted as an entire front line simultaneously using Bitboard arithmetic. 

This parallel calculation takes full advantage of the CPU's ALU (Arithmetic Logic Unit) to compute moves for up to 8 pawns in exactly 1 clock cycle.

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

We elegantly solve this by taking the result of the `singlePushes` calculation, masking it with `Rank3` (meaning these pawns successfully moved one step and are now on Rank 3), and shifting it one more time:

```scala
val doublePushes = ((singlePushes & Rank3Mask) << 8) & emptySquares
```

## Diagonal Captures

Pawns capture diagonally. Similar to [Leaper Attacks](./02-leapers.md), shifting pawns diagonally across the edge of the board can result in illegal wrap-around captures (e.g. an H-file pawn capturing a piece on the A-file of the next rank).

To prevent this, we use the same **Not-File Masks**. We also restrict the generated attacks by `AND`ing them with the bitboard of `enemyPieces`:

```scala
// Capturing East (Right)
val eastCaptures = ((whitePawns & NotHFile) << 9) & enemyPieces

// Capturing West (Left)
val westCaptures = ((whitePawns & NotAFile) << 7) & enemyPieces
```

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
