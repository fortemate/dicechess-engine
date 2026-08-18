---
title: "Board Symmetry & Position Canonicalization"
description: "Color-flip canonicalization of Dice Chess positions, why it preserves win probability, and how it pools statistics and feeds transposition reuse."
---

Two positions related by a **rules-preserving symmetry** have identical win probability. Collapsing
them to a single *canonical* representative lets the ecosystem reuse work across equivalent
positions: analytics pools empirical win-rate statistics, and search / Monte-Carlo can share a
transposition cache key.

This chapter covers the first symmetry — **color-flip** — implemented in
[`Symmetry`](https://github.com/fortemate/dicechess-engine/blob/main/shared/src/main/scala/dicechess/engine/domain/Symmetry.scala).
Horizontal mirror is a separate step; horizontal shift is intentionally excluded (see below).

## Which symmetries are exact?

A symmetry may be used for canonicalization only if it provably preserves win probability. For Dice
Chess that means it must be an **isometry of the board** (distances, and therefore mobility, are
preserved) and must respect file-specific rights.

| Transform | Exact? | Notes |
| --- | --- | --- |
| **Color-flip** (vertical mirror + colour swap) | ✅ always | Black's downward pawns become White's upward pawns; relabels the side to move. |
| **Horizontal mirror** (file reflection a↔h) | ✅ only without castling | Reflection is an isometry, but it swaps king-side ↔ queen-side, which are not equivalent while castling rights exist. |
| **Horizontal shift** (translate files) | ❌ never | Translation is **not** an isometry of the bounded board: it changes a piece's distance to the edge, hence its mobility. A bishop on a4 sees 7 squares, but shifting it three files to d4 (same rank) leaves it seeing 13. It does not preserve win probability and is excluded everywhere. |

## Color-flip

Color-flip is a vertical board mirror (rank `r` ↔ rank `9 − r`) combined with swapping every
piece's colour and the side to move.

Because the engine stores bitboards in **LERF** order (bit 0 = a1, bit 63 = h8), each rank is one
byte of the underlying `Long`, so a vertical mirror is a single `java.lang.Long.reverseBytes` —
files are preserved, ranks reversed. The colour/side swap is layered on top:

- **Piece bitboards** (`pawns`, `knights`, …) are mirrored in place — a flipped white pawn is still
  a pawn, just on the mirrored rank as Black.
- **Colour bitboards** are swapped *and* mirrored: `whitePieces' = flipVertical(blackPieces)` and
  vice-versa.
- **Castling rights** swap colours while keeping their side: White king-side ↔ Black king-side.
- **En-passant** target squares are mirrored vertically (a white-to-move target on rank 6 becomes a
  black-to-move target on rank 3); their *files* — and the `GameFlags` file mask — are unchanged.
- **Dice pool, half-move clock and full-move number** are preserved.

Color-flip is an **involution**: `colorFlip(colorFlip(s)) == s`.

## Horizontal mirror

Horizontal mirror reflects the files (a ↔ h) — a pure spatial reflection, so colours and the side to
move are unchanged. On a LERF bitboard it is `reverseBytes(reverse(bb))`: reversing all 64 bits flips
both rank and file, then reversing the bytes restores the rank order, leaving only the files mirrored
(`mailbox(sq) = mailbox(sq ^ 7)`). Castling rights swap king-side ↔ queen-side and the en-passant file
mask is reversed. It is also an **involution**.

Reflection is an isometry, so it preserves win probability — **but only when there are no castling
rights**. With castling rights the board mirror sends the king from the e-file to the d-file, which is
not a legal castling configuration; the rights no longer mean anything in the mirror. Canonicalization
therefore folds the mirror only when castling rights are absent (≈ 69% of positions in the database).

## Canonicalization

```scala
Symmetry.canonical(state)    // the canonical representative of the position's symmetry class
Symmetry.canonicalKey(state) // its DFEN — a stable key shared by all symmetry-equivalent positions
```

`canonical` first color-flips a black-to-move position to its white-to-move equivalent (so a position
and its color-flip share a representative). Then, **only when there are no castling rights**, it also
folds horizontal mirror by picking whichever of the position and its mirror has the smaller DFEN. The
result is deterministic and idempotent. Since neither transform changes the side-to-move win
probability, all members of the class can be pooled under this single representative.

## What it buys

- **Analytics**: a black-to-move position and its white-to-move flip share one canonical key, so
  empirical win-rate statistics can be pooled — tightening confidence intervals on common positions
  and lifting some sparse positions over usability thresholds. (The bulk of the position space is
  extremely sparse, so canonicalization complements — rather than replaces — Monte-Carlo estimation
  for genuinely off-book positions.)
- **Search / Monte-Carlo**: the canonical key doubles as a transposition / cache key, so an
  evaluation computed for one position can be reused for its flip.

## Verification

- Property tests (ScalaCheck): involution of both transforms, side-to-move flip (color-flip) /
  preservation (mirror), FEN round-trip, idempotent and always-white canonical, mirror folding when
  castling rights are absent, and **invariance of `KingCaptureProbability`** under both transforms.
- Example tests: castling-rights colour swap (color-flip) and king-side ↔ queen-side swap (mirror),
  en-passant rank mirror, and file reflection.
- A JMH benchmark (`SymmetryBenchmark`) tracks `colorFlip` / `horizontalMirror` / `canonical` /
  `canonicalKey` cost, as canonicalization runs on every analyzed position.
