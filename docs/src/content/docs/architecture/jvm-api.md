---
title: JVM API (JvmApi)
description: Reference documentation for the Dice Chess Engine facade used by Java, Kotlin, and other non-Scala JVM callers.
---

The engine exposes `dicechess.engine.jvmapi.JvmApi` to JVM consumers that are not written in
Scala — [dicechess-bot-java](https://github.com/rabestro/dicechess-bot-java) is the reference
consumer. It is the JVM row's counterpart to the JS row's
[`EngineFacade`](/dicechess-engine/architecture/javascript-api/): a deliberately narrow
surface that hides the Scala-shaped parts of the API.

For dependency coordinates and authentication, see
[Maven Artifact & JVM Integration](/dicechess-engine/guidelines/maven-artifact/).

> [!NOTE]
> The facade is exactly the methods documented below. Doubling-cube and draw-offer decisions are **not**
> among them: `DrawOfferLogic` is a Scala trait that bot implementations mix into a `SearchAlgorithm`, not
> a utility a Java caller can use. The Scaladoc published under the `-javadoc` classifier is the
> authoritative method list.

## Two shapes of consumer

The surface splits by how much of the game the caller owns:

- A **webhook bot** is handed a position and asked for one turn. The platform rolls the dice, applies the
  move and decides the result, so the bot needs only `parseDfen`, `activeColor` and `legalTurns`. This is
  what [dicechess-bot-java](https://github.com/rabestro/dicechess-bot-java) does.
- An **autonomous bot** drives the loop itself — self-play, offline analysis, a local tournament — and
  needs the rest: `dicePool`, `halfMoveClock`, `withDice`, `endTurn`, `isGameOver`, `winner`, `toDfen`,
  `evaluate` and `bestTurn`.

Both are supported. Everything below is reachable from Java with no reflection and no Scala types in any
signature.

## Why a facade exists

Most of the engine's Scala API is reachable from Java only in theory. Three constructs break at
the language boundary:

- **`Either` returns.** `FenParser.parse` gives back `Either[String, GameState]`, which from Java
  means chains like `parseResult.left().toOption().isDefined()` before any value can be read.
- **An extension method and an `inline def`.** `GameState.makeMove` is an extension method: it
  compiles onto a synthetic `$package` class with no ordinary Java entry point, and — since it's
  overloaded — Scala disambiguates it via `@targetName`, so the JVM name a Java caller would need
  is `makeMove_Move`, an implementation detail no contract promises to keep. `GameState.activeColor`
  fails for a different reason: it is an ordinary `inline def` member, erased at the call site, so
  it leaves no bytecode at all — not even one reachable by reflection.
- **Opaque types.** `Move` is an `opaque type Move = Int`, so it erases on the JVM. From Java,
  `TurnGenerator.generateAllLegalTurnPaths`'s `List[List[Move]]` arrives as an unchecked
  `List[List[Object]]` of boxed integers with no type-safe way to read it back.

Binding to the facade instead of working around these is what keeps a consumer independent of
engine internals. Treat everything outside `JvmApi` as internal, whatever its visibility.

## `JvmApi`

### `parseDfen`

Parses a DFEN string (FEN extended with a 7th field for the pending dice pool) into a `GameState`.

```java
static GameState parseDfen(String dfen)
```

**Returns:** the parsed `GameState`. **Throws** `IllegalArgumentException` if the DFEN is invalid;
the message is the parser's own error text. This is the one place the engine deliberately departs
from its `Either`-only error convention — translating into an exception a Java caller can `catch`
is the method's entire purpose.

---

### `activeColor`

The color to move.

```java
static int activeColor(GameState state)
```

**Returns:** `0` for White, `1` for Black. (`Color` is an opaque type over `Int`, so it erases to a
plain `int` — the value is directly usable as the `color` argument of the engine's evaluators.)

---

### `legalTurns`

Every legal **full turn** playable from the position — not individual micro-moves.

```java
static java.util.List<JvmApi.Turn> legalTurns(GameState state)
```

Each `Turn` carries both the turn's UCI micro-moves and the position they lead to, read through
accessors named after the fields:

```java
JvmApi.Turn turn = ...;
java.util.List<String> uci = turn.uci();
GameState finalState = turn.finalState();
```

`Turn` is a Scala case class, so it reads like a Java record — accessor per field, plus `equals`,
`hashCode`, and `toString` — but it is **not** a `java.lang.Record` and cannot be used in record
deconstruction patterns. Its compiled form also carries `scala.Product` and Scala's `copy`
machinery; those members are visible to a Java caller but are not part of this facade's contract.

**Returns:** a list of legal turns, empty when the roll has no legal turn (a forced pass).

`finalState` is what makes this a single call rather than an enumerate-then-apply loop: a bot
scoring candidate turns holds the resulting position without replaying the moves itself. Note it has
**not** been passed through `endTurn()` — the active color has not flipped and the dice pool has not
been cleared.

Score those positions with [`evaluate`](#evaluate), or let a built-in bot pick for you with
[`bestTurn`](#bestturn-and-algorithms) — neither requires reaching into `dicechess.engine.search`.

---

## The autonomous loop

These methods exist so a Java consumer can run a whole game without touching internal Scala surface.
Two of them are not merely inconvenient to call otherwise but impossible: `GameState.dicePool` and
`GameState.halfMoveClock` are `inline def`s, erased at the call site, so they leave no bytecode to bind
to — not even by reflection.

### `dicePool` and `halfMoveClock`

```java
static java.util.List<Integer> dicePool(GameState state)
static int halfMoveClock(GameState state)
```

`dicePool` is the dice still available to the side to move — 1–6 values, empty once they are spent.

`halfMoveClock` counts micro-moves since the last pawn move or capture, saturating at 127. The engine
never ends a game on it: a captured king is the only terminal condition in the rules, and a
50-move-style draw is a **host policy**. The bot arena, for instance, calls a game drawn at
`halfMoveClock >= 100`. This accessor is what lets a consumer apply a policy of its own.

---

### `withDice`

```java
static GameState withDice(GameState state, java.util.List<Integer> dice)
```

Sets the roll for the side to move. At most three values, each 1–6, none `null`; an empty list clears
the pool. Anything else **throws** `IllegalArgumentException` rather than being coerced — the packed
representation stores three bits per die, so an out-of-range `8` would silently read back as "no die"
and a fourth entry would vanish, turning a caller's bug into a position that looks legal and plays
wrong.

Generating the dice stays on the caller's side: `java.util.Random` covers an offline loop, and a
platform game gets its dice from the platform.

---

### `endTurn`

```java
static GameState endTurn(GameState state)
```

The other half of `legalTurns`' caveat. `Turn.finalState` is deliberately pre-`endTurn`, so an
autonomous loop calls this itself: it flips the active color, clears the dice pool, advances the
full-move number after Black and drops stale en-passant targets. A turn that captured the king is the
exception — the game is over and ending the turn serves no purpose.

---

### `isGameOver` and `winner`

```java
static boolean isGameOver(GameState state)
static int winner(GameState state)
```

`isGameOver` is true exactly when a king has been captured. A position with no legal turn is **not**
game over — that is a forced pass, not a loss.

`winner` returns `0` (White) or `1` (Black) in the same encoding as `activeColor`, or `JvmApi.NoWinner()`
(`-1`) when the game is undecided. Draws are not represented, because the engine has none of its own;
`NoWinner` covers an ongoing game, a draw the host declared on its own policy, and the
only-from-a-hand-written-DFEN case of both kings missing.

---

### `toDfen`

```java
static String toDfen(GameState state)
```

The inverse of `parseDfen`. Output is **canonical, not verbatim**: the dice pool is written in ascending
order, and omitted entirely when empty (leaving a plain six-field FEN). A round-trip therefore restores
the position and the same multiset of dice, but not necessarily the order they were given to `withDice`
— immaterial to play, since a turn may spend its dice in any order.

---

### `evaluate`

```java
static int evaluate(GameState state, int color)
```

Scores the position from `color`'s point of view in centipawns — positive means ahead — combining
material balance with a king-safety penalty. This is the same evaluation the built-in heuristic bots
use.

It returns `int`, not `double`: the engine's scale is integral centipawns throughout, and a
floating-point result would advertise resolution the evaluator does not have. Scores are meaningful for
ranking candidate turns, not as an absolute verdict, and are not comparable across engine versions.
**Throws** `IllegalArgumentException` unless `color` is `0` or `1`.

---

### `bestTurn` and `algorithms`

```java
static java.util.List<String> algorithms()
static java.util.Optional<JvmApi.ScoredTurn> bestTurn(GameState state, String algorithmId)
static java.util.Optional<JvmApi.ScoredTurn> bestTurn(GameState state, String algorithmId, long timeBudgetMs)
```

`bestTurn` is the JVM counterpart of the JS `getBestMove`: it asks a registered bot for its choice, so a
Java consumer can pit its own bot against the engine's without importing anything from
`dicechess.engine.search`. The dice pool of `state` is the roll the bot plays — set it with `withDice`
first.

```java
Optional<JvmApi.ScoredTurn> chosen = JvmApi.bestTurn(rolled, "greedy");
if (chosen.isPresent()) {
    JvmApi.ScoredTurn turn = chosen.get();
    List<String> uci = turn.uci();
    int score = turn.score();
    GameState next = JvmApi.endTurn(turn.finalState());
}
```

An empty `Optional` means the roll leaves no legal turn: a forced pass, answered with `endTurn`. The id
is case-insensitive and **throws** `IllegalArgumentException` when no bot is registered under it — use
`algorithms()` for the valid ones, ordered weakest to strongest. That list is a snapshot: the registry is
a process-wide singleton a host can add to at runtime.

### Thinking time

Most built-in bots do a fixed amount of work per position — one pass over the roll's legal turns,
microseconds — and ignore `timeBudgetMs` entirely. `monte-carlo` is different: it is a
`TimeBudgetedSearch`, and its untimed path scores **every** legal turn of the roll. A Dice Chess roll
routinely offers thousands, so on an ordinary middlegame position that path runs for **minutes** of CPU
with no way for a Java caller to interrupt it.

The facade therefore never takes that path. The two-argument `bestTurn` gives such a bot
`JvmApi.DefaultTimeBudgetMs()` — one second — and the three-argument form takes the budget from the
caller:

```java
Optional<JvmApi.ScoredTurn> quick = JvmApi.bestTurn(rolled, "monte-carlo", 200L);
```

Budgeted bots hold to an anytime contract: whatever the budget, they return a legal turn, taking an
immediate king capture for free. A non-positive budget **throws** `IllegalArgumentException`.

### Reading `score`

`ScoredTurn` carries `uci` and `finalState` with exactly the meaning they have on `Turn` (`finalState`
included: still pre-`endTurn`), plus `score` from the mover's perspective.

**`score` is on the scale of the bot that produced it, and on no other.** Only the sign and the ordering
are shared: higher is better, and `Integer.MAX_VALUE` means the turn captures the king. The unit is not:

| Bot | Scale of `score` |
| :--- | :--- |
| `greedy`, `greedy-v2`, `aggressive`, `checkmate-aware` | centipawns, comparable with `evaluate` |
| `monte-carlo` | estimated win probability scaled to `0`–`1000000` (a level position sits near `500000`) |

Compare scores only among turns from one call to one algorithm. Reading a `monte-carlo` score as
centipawns overstates the position by three orders of magnitude. Bots may also break ties randomly, so
two calls on one position can legitimately differ.

---

## A complete autonomous turn

```java
GameState state = JvmApi.parseDfen(dfen);
var random = new java.util.Random();

while (!JvmApi.isGameOver(state)) {
    List<Integer> roll = List.of(random.nextInt(6) + 1, random.nextInt(6) + 1, random.nextInt(6) + 1);
    GameState rolled = JvmApi.withDice(state, roll);

    state = JvmApi.bestTurn(rolled, "greedy")
            .map(JvmApi.ScoredTurn::finalState)
            .map(next -> JvmApi.isGameOver(next) ? next : JvmApi.endTurn(next))
            .orElseGet(() -> JvmApi.endTurn(rolled));   // forced pass
}

System.out.println(JvmApi.toDfen(state) + " won by " + JvmApi.winner(state));
```

This is the shape the facade's own test plays out in full; see
[what is verified](#what-java-and-kotlin-is-based-on) below. Swap `"greedy"` for `"monte-carlo"` and the
loop still terminates in bounded time, because the two-argument `bestTurn` carries a default budget.

## Working with the platform's `legalMoves`

The Dice Chess platform delivers its own legal-turn tree in the webhook envelope
(`TurnContext.legalMoves`), so a bot may already hold the UCI sequences before calling the engine.

There is intentionally **no** `applyTurn(state, List<String>)` on this facade. The platform treats
UCI strings as opaque tokens matched by exact string equality against engine-generated paths and
never decodes them; a decoder here would be a second implementation that can drift from the
encoder. Match the externally-sourced sequence against `legalTurns`' own `uci` field to obtain the
corresponding `finalState`.

That also covers the case where the tree is absent: `legalMoves` is `null` whenever the enumeration
exceeded the platform's inline size cap, and `legalTurns` is the fallback that keeps a bot playing
rather than forfeiting.

## What "Java and Kotlin" is based on

Only the Java half is verified mechanically, by two real Java source files that CI compiles and runs on
every build — so a signature that stops being reachable from Java fails the build rather than the next
consumer. `JvmApiSmokeCheck.java` covers the webhook-shaped surface, and `JvmApiSelfPlayCheck.java`
plays a complete game to a captured king using only `dicechess.engine.jvmapi` imports, which is what
keeps the autonomous loop above honest: a gap in the facade shows up there as code that cannot be
written. That game is reproducible — both sides choose through `legalTurns` from one seeded `Random`,
and `bestTurn` is checked against the same enumeration rather than played, because the built-in bots
break ties with an unseeded `Random` of their own.

Kotlin gets no such check — this repository has no Kotlin toolchain and no Kotlin example. The claim
that Kotlin works rests on the mechanism the Java test pins: the facade exposes plain static methods
whose parameters and return types are primitives, `java.util` collections, `String`, `GameState`
handles, and `JvmApi.Turn`. Kotlin consumes those exactly as Java does. Treat it as sound by
construction, not as tested.

## API documentation in the IDE

There is no separate JavaDoc build, and there cannot be one: the `javadoc` tool parses `.java`
source files, and every source file in this repository is Scala. The `jvm/` directory name refers
to the cross-compilation target (as opposed to `js/` and `.wasm/`), not to the language — its
sources live under `jvm/src/main/scala/`. All of that code compiles to ordinary JVM bytecode and
is callable from Java; it is simply not *written* in Java, so there is nothing for `javadoc` to
read.

Documentation still reaches Java and Kotlin callers, because the published `-javadoc.jar` contains
rendered **Scaladoc**. In Maven the `-javadoc` classifier means "API documentation", not "output of
the javadoc tool", so shipping Scaladoc under it is the standard convention for Scala artifacts.
IDEs attach it exactly as they would real JavaDoc — `JvmApi`'s documentation shows up on hover with
no extra setup.
