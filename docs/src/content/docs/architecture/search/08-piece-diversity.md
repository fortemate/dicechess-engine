---
title: Piece Diversity Index
description: An unweighted count of represented piece types for evaluation features and position analysis.
---

Piece Diversity Index (PDI) counts how many non-king piece types a side still has.
It provides a small, deterministic feature for position analysis, custom evaluators,
and training-data enrichment:

```text
PDI(side) = hasPawn + hasKnight + hasBishop + hasRook + hasQueen
PDI difference = PDI(our side) - PDI(opponent)
```

Each presence flag is zero or one. Two knights contribute the same count as one
knight; a side with only its king has a count of zero.

## Scala API

The shared core exposes `dicechess.engine.search.PieceDiversity`:

| Method | Result | Range |
| --- | --- | --- |
| `count(state: GameState, color: Color): Int` | Types present for the requested side | 0 to 5 |
| `difference(state: GameState, color: Color): Int` | Own count minus the opponent's count | −5 to 5 |

The perspective is the explicit `color` argument, independently of the active
color or remaining dice in `state`. For example, White has a pawn, knight, and rook
while Black has a bishop and queen:

```scala
import dicechess.engine.domain.*
import dicechess.engine.search.PieceDiversity

val diversity = FenParser.parse("4k3/8/8/2bq4/2PNR3/8/8/4K3 w - - 0 1").map { state =>
  (
    PieceDiversity.count(state, Color.White),
    PieceDiversity.count(state, Color.Black),
    PieceDiversity.difference(state, Color.White)
  )
}
// Right((3, 2, 1)); Black's difference would be -1.
```

Both methods inspect the existing piece bitboards, perform a fixed amount of work,
and leave the position unchanged. They do not generate moves or use randomness.
The implementation is shared by the JVM, Scala.js, and WebAssembly targets.
These Scala methods are not exports of `DiceChess`, `EngineFacade`, or `JvmApi`;
JavaScript and Java-facing facade contracts remain separate.

## Interpretation and edge cases

- **Presence, not mobility.** A blocked piece still contributes its type. Moving
  a piece or changing the dice pool does not affect its side's count.
- **Last piece versus duplicates.** Capturing one of two rooks preserves the
  rook contribution; capturing the last rook removes it.
- **Promotion.** A promotion can restore a missing type. Promoting the last pawn
  also removes the pawn contribution, so PDI can increase, stay unchanged, or
  decrease depending on the other pieces present.
- **Kings and terminal states.** Kings never contribute. Capturing a king does
  not itself change either count; callers must still handle terminal outcomes
  through their usual search or evaluation logic.
- **Units.** The result is a count, not a centipawn score, expected number of
  usable dice, or win probability. One piece can use repeated matching dice by
  moving more than once, subject to the move rules.

## Using PDI as a model feature

Extract each side separately when a model needs both absolute diversity and the
imbalance. For a new model contract, an optional normalized representation is
`count(state, side).toFloat / 5.0f`. Keep the same definition and perspective in
training and inference.

PDI is already determined by a full board representation; an explicit input may
help a model learn that summary. Whether it improves predictions or playing
strength requires a controlled comparison. Adding a feature does not justify a
fixed positive evaluation bonus.

The engine does not apply a PDI weight to any built-in bot or append PDI to existing
ONNX feature vectors.

### Versioned rich-PDI input

`RichPdiFeatures` exposes the opt-in schema `rich-pdi-11-v1`. Its `columnNames`
and `extract(state, color)` are the CSV and ONNX contract:

```text
p_diff,n_diff,b_diff,r_diff,q_diff,material_diff,total_material,mobility_diff,king_safety_diff,own_pdi,opponent_pdi
```

The first nine float32 values are exactly `RichFeatures.extract(state, color)`.
The last two are `PieceDiversity.count(state, color) / 5f` and the same quantity
for `color.opponent`: each is one of 0, 0.2, 0.4, 0.6, 0.8, 1. They swap when
the evaluation perspective changes; they are not signed differences. The explicit
perspective remains authoritative even after `endTurn()` changes the active side.

Pass `RichPdiFeatures.extract` to the JVM ONNX evaluator or select
`--features rich-pdi-11-v1` in an arena runner with an eleven-input model.
The tensor contract is float32 `[batch, 11]`; existing `rich` models still take
nine columns. Training consumers must read all eleven engine-enriched columns
in this order, including material, rather than recomputing a subset independently.

Changing the layout, normalization, or definition requires a new schema identifier
and a matching trained model. Schema availability alone is not evidence of strength.

## Tests and measurement

The shared semantic suite covers every non-king type for both colors, duplicate
and last-piece captures, both difference bounds, color and dice invariance,
blocked and pinned pieces, empty and fully occupied boards, promotion transitions,
terminal kings, and state immutability.
It runs on JVM, JavaScript, and WebAssembly.

`PieceDiversityBenchmark` measures the standalone count and difference on opening,
middlegame, endgame, and promotion positions. For a short diagnostic run:

```bash
mise exec -- sbt 'benchmark/Jmh/run -wi 2 -i 3 -f 1 -t 1 -w 500ms -r 500ms -bm avgt .*PieceDiversityBenchmark.*'
```

Microbenchmark timings describe only these primitives on the measured runtime.
They do not establish whole-search overhead or playing strength.
