---
title: Glossary
description: Essential terminology used in the Dice Chess Engine architecture.
---

This glossary provides definitions for standard chess programming terms and unique concepts specific to the Dice Chess Engine.

## Board Representation

### Bitboard
A 64-bit integer where each bit represents a square on the chess board. This is the primary data structure for high-performance move generation. A bit is set to `1` if the square is occupied or satisfies a condition, and `0` otherwise.

### Square
A board location represented by an index from `0` (a1) to `63` (h8).

### LERF Mapping
**Little-Endian Rank-File** mapping. In this convention, bit 0 represents the a1 square, bit 1 represents b1, and so on, ending with bit 63 at h8. It is the standard mapping used in modern engines to simplify bitwise operations.

## Dice Chess Mechanics

### Turn
A complete sequence of play by one player. In Dice Chess, a Turn consists of a single dice roll outcome and a sequence of 1 to 3 **Micro-moves**.

### Micro-move
The smallest unit of movement in Dice Chess. A micro-move represents a single piece moving from an origin square to a destination square (including captures and promotions).

### Dice Filtering
The process of narrowing down the set of pseudo-legal moves based on the dice roll. For example, if the dice rolls a "4" (Rook), only Rook moves are allowed for that micro-move.

## Move Generation & Search

### Pseudo-legal Move
A move that follows the movement rules of a piece (e.g., a Knight moves in an 'L' shape) but does not yet account for whether the King is left in check.

### Legal Move
A pseudo-legal move that has been verified to not leave the active player's King under attack.

### Expectimax
A variation of the Minimax search algorithm designed for games with elements of chance (like dice rolls). It calculates the mathematical expectation of a position by weighting possible outcomes by their probability.

### Zobrist Hashing
A technique used to uniquely represent a board position as a 64-bit integer. It allows the engine to quickly identify if a position has been seen before.

### Transposition Table (TT)
A large hash table (cache) that stores the results of previously searched positions using **Zobrist Hashing**. This avoids redundant calculations and significantly speeds up the search process.
