---
title: Zero-GC Move Encoding
description: How we pack chess moves into a single 16-bit integer for zero-allocation performance.
sidebar:
  order: 1
---

Move generation is the beating heart of any chess engine. In our Dice Chess engine, we generate millions of pseudo-legal and legal moves per second during deep Expectimax searches. To achieve maximum performance, we strictly avoid object allocations (Zero-GC) and rely heavily on Bitboard arithmetic.

## Object Allocation Overhead

In languages like Scala or Java, creating a `new Move(from, to)` object millions of times per second would overwhelm the Garbage Collector (GC). The engine would experience massive lag spikes as the GC pauses the search threads to clean up millions of dead `Move` objects.

To solve this, we encode every possible chess move into a **single 16-bit integer**, wrapped in a Scala 3 `opaque type` for type safety.

```scala
opaque type Move = Int
```

## Memory Layout
We pack the source square, destination square, and special flags into exactly 16 bits:

| Bits 12-15 (4 bits) | Bits 6-11 (6 bits) | Bits 0-5 (6 bits) |
| :---: | :---: | :---: |
| **Flags** (Capture, Promotion) | **From** Square (0-63) | **To** Square (0-63) |

### Flag Definitions
The 4-bit flag is used to decode the exact type of move:
- `0000` (0): Quiet Move
- `0001` (1): Double Pawn Push
- `0010` (2): King-side Castle
- `0011` (3): Queen-side Castle
- `0100` (4): Standard Capture
- `0101` (5): En Passant Capture
- `1xxx` (8-15): Promotions (and promotion-captures)

By extracting data via bitwise masks (`(move & 0x3f)`), we get instantaneous access to move properties without ever instantiating an object on the JVM heap.

## MicroMove vs Search Move

The engine utilizes two slightly different 16-bit encodings for different contexts:

### 1. Search Move (`Move`)
Optimized for the **search tree**. It uses 4 bits for **Flags** (e.g., "is this a capture?", "is this a castle?"). This allows the search algorithm to quickly identify move types without checking the board state.

### 2. Game Move (`MicroMove`)
Used for **Game State transitions** and UI. Instead of abstract flags, it uses the 4 bits to store an optional **Promotion PieceType** (1-6).

**MicroMove Memory Layout:**
| Bits 12-15 (4 bits) | Bits 6-11 (6 bits) | Bits 0-5 (6 bits) |
| :---: | :---: | :---: |
| **Promotion Piece** (0 if none) | **Target** Square | **Origin** Square |
