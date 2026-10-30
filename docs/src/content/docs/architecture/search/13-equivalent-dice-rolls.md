---
title: Equivalent Dice Rolls
description: Exact promotion-aware grouping of unknown rolls for full-turn chance nodes.
sidebar:
  order: 13
---

`ActiveDicePartitioner` provides **Active Dice Equivalence Partitioning (ADEP)** in
`dicechess.engine.search`, in the dependency-free `dicechess-rules` artifact. Its Scala API is shared by
JVM, JavaScript and WebAssembly. It is a building block for chance-node consumers; existing built-in
bots do not automatically use it. This change adds no Java facade or JavaScript export.

## What is equivalent?

[`DiceRolls`](https://fortemate.github.io/dicechess-engine/api/dicechess/engine/search/DiceRolls$.html)
represents the 216 ordered outcomes of three fair six-sided dice as 56 sorted multisets with integer
weights. Some of those multisets permit exactly the same full turns on a particular board.

If a side has only a king, every non-King die is unusable. Its rolls can be represented by four classes:

| Retained dice | Ordered outcomes | Probability |
| --- | ---: | ---: |
| None | 125 | 125 / 216 |
| K | 75 | 75 / 216 |
| KK | 15 | 15 / 216 |
| KKK | 1 | 1 / 216 |

Each class retains the multiplicity of the usable dice. **The empty class is a forced pass, not
probability mass to discard.** All class weights are positive and sum to 216.

Equivalence means the same legal full-turn move paths, including maximum **dice consumed** and early
king captures. Successor states match after `endTurn()`, which clears remaining dice. Before that
boundary, the original and reduced pools can have different unused dice and Zobrist hashes.

This grouping is sound but deliberately conservative. It removes absent piece types, rather than
enumerating their legal moves. A blocked rook still retains Rook dice, and a blocked promotion pawn
may retain extra promotion dice. Other equivalences can therefore remain unmerged.

## Promotion within the same turn

A die for an absent piece type can become usable after promotion:

- With a pawn on rank 7 for White or rank 2 for Black, a roll containing at least one Pawn die retains
  all Knight, Bishop, Rook and Queen dice. The newly promoted piece can use them in the same turn.
- With a pawn on rank 6 for White or rank 3 for Black, at least two Pawn dice are needed.
- A pawn farther away cannot promote **and use another die for the promoted piece** within three dice.

For example, White pawn e7 can promote to a knight under PNN and then make two knight moves. Dropping
N because no knight was present at the start would lose legal paths. Blockers and capture routes do
not need to be inspected: the rank test retains a safe superset of potentially usable dice.

The engine precomputes 192 immutable partitions: 64 presence masks times three promotion modes.
Classes are ordered by descending weight, then descending retained-dice count, then lexicographic
face order. This order is deterministic across runtimes; it is not a tactical move-ordering policy.

## Scala usage

```scala
import dicechess.engine.domain.FenParser
import dicechess.engine.search.{ActiveDicePartitioner, DiceRolls, TurnGenerator}

val state = FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get
val classes = ActiveDicePartitioner.partitionsFor(state)
assert(classes.iterator.map(_.weight).sum == DiceRolls.totalOrderedRolls)

classes.foreach { entry =>
  val rolled = entry.roll.applyToState(state)
  val paths = TurnGenerator.generateAllLegalTurnPaths(rolled)
  val successors =
    if paths.isEmpty then List(rolled.endTurn())
    else paths.map(path => path.foldLeft(rolled)((s, move) => s.makeMove(move)).endTurn())
  // The consumer chooses its best legal successor and weights its value by
  // entry.weight.toDouble / DiceRolls.totalOrderedRolls.
  assert(successors.nonEmpty)
}
```

`partitionsFor` uses `state.activeColor` and ignores the existing dice pool. Call it at a **fresh unknown
roll**. After playing the preceding turn, first call `endTurn()` to select the next mover. For a known
roll, continue using that roll; applying this distribution would describe a different game state.

`CompactRoll` packs the retained pool into an integer. `applyToState` replaces only dice and updates
the Zobrist key while preserving the board, side, counters and rights. It allocates a new `GameState`
but avoids constructing a dice list. `toList` is available for diagnostics. The returned partition
is an `IArray` of packed `WeightedRoll` entries and is safe to reuse without copying.

Consumers remain responsible for:

- Detecting terminal positions before expanding a chance node, and scoring king captures as terminal.
- Choosing a legal full-turn successor for each class; a nonempty retained roll can still have no legal moves.
- Using values that depend on terminal outcomes or full-turn successor states, not discarded dice.
- Preserving the denominator 216 and using an accumulator wide enough for weighted values. Grouping is
  exact in integer/rational arithmetic, but changing floating-point summation order can change rounding.
- Their own pruning, evaluation, caching, deadlines and tie-breaking policies.

## Relationship to other APIs

`DiceRolls` is the unconditional distribution and the reference for exhaustive chance enumeration.
`ActiveDicePartitioner` reduces that distribution for one board before traversal. `PlayableDice.of`
answers a different question: which dice an already known roll can spend across maximal legal paths,
optionally after a move prefix. `TurnGenerator` remains the authority for legal turn paths, castling,
en passant and early king captures.

See [Expectimax Search Engine](/dicechess-engine/architecture/search/06-expectimax-search/) for the
consumer-side chance-node calculation. ADEP does not alter that implementation in this release.

## Verification and measurement

`ActiveDicePartitionerSuite` independently enumerates all 216 ordered rolls for all 192 table states.
It also compares legal paths and complete successor-state distributions against ungrouped enumeration
on both colors, all piece-presence subsets, and fixtures for promotion, castling, en passant, blocked
pieces, passes and king captures. These tests run on JVM, JavaScript and WebAssembly.

The reproducible `ActiveDicePartitionerBenchmark` measures cached lookup separately from full-turn
enumeration with and without grouping. Run it with:

```bash
mise run bench:filter '.*ActiveDicePartitionerBenchmark.*'
```

Benchmark timings are host- and position-dependent. They measure this primitive, not playing strength;
no performance threshold belongs in its correctness tests.
