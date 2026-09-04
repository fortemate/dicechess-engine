---
title: Testing Strategy & DSL
description: How we ensure correctness of the move generator using human-readable Test DSL.
---

In a chess engine, the core logic (move generation, board representation) is performance-critical and heavily bitwise. Testing these components using raw hexadecimal masks or bit shifts is error-prone and difficult for humans to verify.

To solve this, we use a **Test Domain-Specific Language (DSL)** that allows us to write tests in plain chess notation or even visual ASCII boards.

## The Test DSL

Our testing utilities live in `src/test/scala/dicechess/engine/testutils/TestBoard.scala`. They provide three primary ways to interact with bitboards.

### 1. Visual ASCII Boards

For complex scenarios like **Magic Bitboards** (where blockers determine the range of sliding pieces), we can define the board state visually.

```scala
import dicechess.engine.testutils.TestBoard

val pos = TestBoard.fromAscii("""
  - - - - - - - -
  - - - - - - - -
  - - - - P - - -
  - - - - - - - -
  - - P - R - P -
  - - - - - - - -
  - - - - P - - -
  - - - - - - - -
""")

val occupancy = pos.occupied // Bitboard containing all 'P' and 'R' positions
```

The parser uses FEN-standard characters (`P`, `R`, `k`, etc.) and ignores whitespace, dots, and dashes.

### 2. Algebraic Varargs

When you just need to place a few pieces or verify specific targets, you can use the varargs constructor:

```scala
// Creates a bitboard with bits set at d3 and f5
val occupancy = TestBoard("d3", "f5")
```

### 3. Extension Methods

For maximum brevity, we provide extension methods on standard `String` literals:

```scala
import dicechess.engine.testutils.TestBoard.*

val sq = "e4".sq  // Returns a Square object
val bb = "e4".bb  // Returns a Bitboard with e4 set
```

## Example: Testing a Rook Attack

Here is how a real test looks using the DSL:

```scala
test("Rook attacks with blockers") {
  val sq = "e4".sq
  
  val pos = TestBoard.fromAscii("""
    - - - - - - - -
    - - - - - - - -
    - - - - P - - -
    - - - - - - - -
    - - P - R - P -
    - - - - - - - -
    - - - - P - - -
    - - - - - - - -
  """)
  
  val attacks = MagicBitboards.rookAttacks(sq, pos.occupied)
  
  val expected = TestBoard(
    "e5", "e6", // North
    "e3", "e2", // South
    "d4", "c4", // West
    "f4", "g4"  // East
  )
  
  assertEquals(attacks, expected)
}
```

## Dice Chess Move Generator Testing

For complex, multi-move path-optimization rules under Dice Chess mechanics, standard unit tests can quickly become verbose and difficult for humans to review. To solve this, we use a structured **ChessDsl testing framework** paired with an **automatic visual catalog compiler** that bridges the gap between bitwise engine logic and human verification.

### 1. Golden Test Suites via ChessDsl

All move generator golden test cases are structured directly in Scala in `shared/src/test/scala/dicechess/engine/movegen/MoveGenFixtures.scala` using our fluent DSL, categorized by the number of dice rolled:
* `1-Die Scenarios` — 1-die fundamental leaper/slider moves.
* `2-Dice Scenarios` — 2-dice micro-move sequences.
* `3-Dice Scenarios` — 3-dice full turn path optimizations.

Defining test fixtures in shared Scala code compiles them natively into JVM bytecode, Scala.js JavaScript, and WebAssembly without runtime I/O or platform-dependent resource loading, ensuring discriminating cross-platform test coverage (#123).

Each test case is expert-vetted and contains:
* `fen`: The board position in standard FEN notation (with dice pool in the 7th field).
* `expectedMoves`: A list of all legal UCI move sequences (e.g., `"e2e4"`, `"g1f3"`).
* `title`: A short, descriptive scenario title.
* `description`: A clear explanation of the expected chess mechanics.

Example entry:
```scala
"rnbqkbnr/ppp1pppp/8/3pP3/2B5/5Q2/PPPP1PPP/RNB1K1NR w KQkq d6 0 1 P"
  .titled("En Passant and Path Blockage")
  .describedAs(
    "A complex pawn scenario: the pawn on e5 can capture the black d5 pawn en passant (exd6). The c2 pawn's two-square advance is blocked..."
  )
  .shouldYield("e5e6", "e5d6", "c2c3", "d2d3", "d2d4", "h2h4")
```

### 2. The Movegen Test DSL

To keep our test files clean and type-safe, we extended our fluent DSL in `shared/src/test/scala/dicechess/engine/movegen/ChessDsl.scala`. This allows developers to dynamically construct and verify test cases using simple, readable extension methods:

```scala
import dicechess.engine.movegen.ChessDsl.*
import dicechess.engine.domain.PieceType.*

val testCase = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  .withDice(Pawn)
  .titled("Initial position: pawn moves")
  .describedAs("Standard starting position...")
  .shouldYield("a2a3", "a2a4", "b2b3", "b2b4")
```

`.withDice(...)` renders the pool as the piece letters the 7th FEN field is made of (`Pawn` → `P`, `(Pawn, King)` → `PK`), so the two forms are interchangeable: the fixtures in `MoveGenFixtures.scala` spell the dice directly into the FEN and chain `.titled(...)` straight off the string, while `.withDice(...)` is the readable choice when the base position is shared between scenarios.

### 3. Automated MUnit Integration

MUnit executes these test cases across all three target platforms via `MoveGenGoldenSpec`. The test runner uses the custom titles and descriptions to name and document tests inside the console outputs, providing extremely readable test logs:

```bash
==> i dicechess.engine.movegen.MoveGenGoldenSpec.1-Die Scenarios: En Passant and Path Blockage (A complex pawn scenario...)
```

### 4. Dynamic Visual Documentation Pipeline

To make these test cases easy to audit by chess players and domain experts, we build a **live visual test catalog** directly inside our documentation portal. 

When you run:
```bash
mise run docs:build
```

The build task executes our documentation compiler `DocGenerator.scala` which:
1. **Reads the Golden Suites**: Directly iterates over `MoveGenFixtures.allSuites` in memory.
2. **Extracts FEN Metadata**: Dynamically parses each FEN string to display Active Color, Castling Rights, and En Passant targets.
3. **Flips the Board**: Generates a dynamic graphical chess board using Lichess GIF exports, automatically setting `color=black` for black-active positions so they render from the player's perspective.
4. **Applies Responsive HTML Grid**: Wraps each scenario in a premium two-column CSS grid that automatically adapts to mobile screens.
5. **Compiles the Astro Site**: Automatically updates `/architecture/move-generation/06-test-cases/` on the docs site.

## Shared vs. Platform-Specific Tests

Because our engine is a cross-compiled Scala project targetting **JVM**, **JS (Scala.js)**, and **Wasm (WebAssembly)**, we categorize our test suite files based on their runtime environment constraints:

* **Shared Tests (`shared/src/test/`)**: Programmatic unit and golden tests (such as `MoveGenGoldenSpec.scala`, `MutableLegalMovesFilterSpec.scala`, and `MakeMoveSpec.scala`) that construct states in-memory without external I/O. These compile and execute on JVM, JS, and Wasm environments to ensure identical behavior across server, web client, and WebAssembly runtimes.
* **Platform-Specific Tests (`jvm/src/test/`)**: Tests that rely on Java-specific runtime APIs or heavy dependencies. Examples include `PerftSpec.scala` (which loads large perft suites from JVM resources) and ONNX model evaluation suites (which require the Java ONNX runtime).

---

## Why this matters

1. **Self-Documenting**: The tests show exactly what is being tested without requiring comments.
2. **Fast Debugging**: If a test fails, the diff between two `Bitboard` objects is rendered by MUnit, and you can easily cross-reference it with the visual board in the test code.
3. **Correctness**: It is much harder to make a mistake when typing `"e4"` than when typing `1L << 28`.
