---
title: "Domain Modeling & Zero-Cost Abstractions"
description: "Detailed architecture behind the Scala 3 Opaque Types, bitwise memory packing, and LERF Bitboards."
---

The core engine is built to traverse millions of game states rapidly during the Expectimax search. To achieve this, we prioritize **Zero-Cost Abstractions**—ensuring that our code is readable and type-safe during development, but compiles down to raw, zero-allocation primitives at runtime.

## 1. Opaque Types (Scala 3)

We strictly avoid `case class` or `AnyVal` wrappers for fundamental domain objects to prevent JVM heap allocations and Garbage Collector (GC) pressure. 

Instead, we use Scala 3 `opaque type`s. At compile-time, they provide strict type safety. At runtime, they are completely erased, leaving only the underlying primitive (`Int` or `Long`).

*   `Color`: An `Int` (0 = White, 1 = Black). Toggled instantly using bitwise XOR (`^ 1`).
*   `PieceType`: An `Int` directly corresponding to the dice value (1 = Pawn, 6 = King).
*   `Square`: An `Int` representing an index from 0 (a1) to 63 (h8).

## 2. Bitwise Memory Packing

When an object logically requires multiple fields, we pack them into a single primitive integer using bitwise operations, rather than allocating a container object.

### The `Piece` (4 bits)
A `Piece` needs both a `Color` and a `PieceType`. We pack them into 4 bits of a standard `Int`:
*   **Bit 3**: Color (0 for White, 1 for Black)
*   **Bits 0-2**: PieceType (1 to 6)

### The `MicroMove` (16 bits)
A complete move packet requires an origin, a destination, and an optional promotion piece. We pack this into 16 bits:
*   **Bits 12-15**: Promotion `PieceType` (0 if no promotion)
*   **Bits 6-11**: Target `Square` index (0-63)
*   **Bits 0-5**: Origin `Square` index (0-63)

## 3. LERF Bitboards

The core mechanism for representing piece placements across the board is the **Bitboard** (`opaque type Bitboard = Long`).

We use the **Little-Endian Rank-File (LERF)** mapping:
*   `a1` is mapped to bit `0`
*   `h8` is mapped to bit `63`

### The 8-Bitboard Split
Instead of maintaining 12 individual bitboards (White Pawns, Black Pawns, etc.), our engine architecture utilizes an 8-bitboard split:
*   **2 Color Bitboards**: `whitePieces`, `blackPieces`
*   **6 Type Bitboards**: `pawns`, `knights`, `bishops`, `rooks`, `queens`, `kings`

**Performance Benefits:**
*   **Intersection:** To find all White Knights, we perform a single bitwise AND: `whitePieces & knights`.
*   **Occupancy:** To find all pieces on the board: `whitePieces | blackPieces`.
*   **Hardware Acceleration:** Counting pieces uses the JVM intrinsic `java.lang.Long.bitCount`, which maps directly to the CPU's `POPCNT` instruction (executing in 1 clock cycle).
*   **Memory Footprint:** The entire board state is exactly 64 bytes (8 x 8 bytes), fitting perfectly into a single L1 CPU cache line.

## 4. The Hybrid Mailbox Approach

While Bitboards are incredibly fast for generating moves (e.g., "Where can white knights go?"), they are slow for answering: *"What piece is standing on e4?"*

To solve this, our `GameState` utilizes a **Hybrid Architecture**:
1.  **Bitboards:** Used for move generation and rapid set intersections.
2.  **Mailbox Array:** An auxiliary flat array (`Array[Piece]` of size 64) maintained in parallel. This provides `O(1)` instant piece lookup without needing to check all 6 type bitboards sequentially.
