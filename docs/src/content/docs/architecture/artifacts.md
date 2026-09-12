---
title: Published Artifacts & Rules-Only Migration
description: What each published artifact contains — dicechess-rules, dicechess-engine, the two npm packages and the Android source path — what is deliberately absent, and how a consumer moves to the coordinate it actually needs.
---

One Scala 3 code base is published as **two Maven coordinates** and **two npm packages**, all at the
same `X.Y.Z` version of a release (see [CI/CD & Releases](/dicechess-engine/architecture/releases/)).
The split follows the engine's architecture decision on artifact layering: the rules of the game are
one artifact, everything that *plays* the game is another, and **no package was renamed** — a
consumer that switches coordinate keeps every `import`.

The boundary is also an open-core boundary. The code on this page — rules, move generation, the
bots, the evaluators and the feature extractors — is public and lives in these artifacts. Trained
models, the configuration they run with in production and the results of the experiments behind
them are **not part of any artifact** and are not documented here.

## At a glance

| Artifact | Registry | Source roots | Depends on | Facade | Typical consumer |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `com.fortemate:dicechess-rules_3` (from 0.11.0) | Maven Central, GitHub Packages | `shared-rules/` | Scala standard library only | none (Scala API) | a server of truth, an analytics backend, a position editor |
| `com.fortemate:dicechess-engine_3` | Maven Central, GitHub Packages | `shared/`, `jvm/` | `dicechess-rules_3` (same version); `onnxruntime` **optional** | `JvmApi` (Java, Kotlin) | bots, evaluation and training tools, anything that searches or scores |
| `@fortemate/dicechess-engine` | npmjs.org, GitHub Packages | `shared-rules/`, `shared/`, `js/` | none | `DiceChess`, `EngineFacade` | browser and Node.js clients |
| `@fortemate/dicechess-engine-wasm` | npmjs.org, GitHub Packages | same as above, WasmGC build | none | same as above | Web Workers running heavy search |
| Android source path | no artifact | `shared-rules/`, `shared/`, `jvm/.../jvmapi` | Scala standard library | `JvmApi` + direct Scala calls | the on-device prototype |

The sbt build behind these rows is a `projectMatrix`: the `rules` matrix has a JVM row (published as
`dicechess-rules_3`) and a JS row (linked into the npm bundles, not published on its own); the `root`
matrix has a JVM row (`dicechess-engine_3`), a JS row and a Wasm row (the two npm packages).

## `dicechess-rules_3` — the rules of Dice Chess

Everything needed to hold a position, validate a move and enumerate a turn, and nothing that chooses
one.

**Packages**

- `dicechess.engine.domain` — `GameState`, `Position`, `Move`, colours and piece models, game flags,
  DFEN parsing and serialisation (`FenParser`), board symmetry and canonical keys (`Symmetry`),
  Zobrist hashing.
- `dicechess.engine.movegen` — `MoveGenerator`, pawn generation, leaper attacks and magic bitboards,
  `LegalMovesFilter`, `Dfen` helpers, `Perft`.
- `dicechess.engine.search`, **rules half of the split package** — `TurnGenerator` (every legal turn
  as a micro-move path), `DiceRolls`, `KingCaptureProbability` with its scratch board. These are
  rules-level: the probability that a roll allows a king capture is a property of the position and
  the dice, not of any bot.

**External dependencies:** none. The POM declares `org.scala-lang:scala3-library_3` and nothing
else outside test scope; the publish pipeline refuses to ship the jar otherwise
(`assertRulesPomHasNoThirdPartyDependencies`). Test-only dependencies (munit, circe fixtures) are not
part of the artifact.

**Facade:** none. Consumers call the Scala API directly — `FenParser.parse`, `GameState`,
`TurnGenerator.generateAllLegalTurnPaths`. A Java or Kotlin caller that wants the `JvmApi` facade
needs `dicechess-engine_3`.

**Deliberately absent:** every bot and search algorithm, `Evaluator`, the feature extractors, the
opening book, time management, ONNX integration, `JvmApi`, the JavaScript facades, benchmarks, the bot
arena and the CLI.

## `dicechess-engine_3` — bots, evaluation, features and the JVM facade

Everything that plays or scores a position. It depends on `dicechess-rules_3` **of exactly the same
version** (compile scope), so adding the engine coordinate brings the rules with it; declaring both is
harmless but unnecessary.

**Packages**

- `dicechess.engine.search`, **engine half of the split package** — `SearchAlgorithm`,
  `BotRegistry`, `Evaluator`, the built-in bots (`RandomSearch`, `GreedySearch`, `GreedySearchV2`,
  `AggressiveSearch`, `CheckmateAwareSearch`, `ExpectimaxSearch`, `MonteCarloSearch`,
  `TimeBudgetedSearch`), `TimeManager` and `TimePolicy`, `TranspositionTable`, the opening book
  (`OpeningBook`, `OpeningBookParser`, `OpeningBookBot`), `DrawOfferLogic`, `MonteCarloEquity`, the
  evaluation terms (`MaterialValues`, `PieceMobility`, `PieceSafety`, `PieceDiversity`,
  `PassedPawns`) and the feature extractors (`KcpFeatures`, `KcpMobilityFeatures`,
  `KcpMobilityPawnsFeatures`, `RawBoardFeatures`, `RichFeatures`, `RichPdiFeatures`,
  `OnnxFeatures`).
- `dicechess.engine.search`, JVM only — `OnnxEvalSearch` and `OnnxExpectimaxSearch`, the two
  classes that import `ai.onnxruntime`.
- `dicechess.engine.jvmapi` — `JvmApi`, the Java/Kotlin facade documented in the
  [JVM API Reference](/dicechess-engine/architecture/jvm-api/).

**External dependencies:** `dicechess-rules_3` (compile) and `com.microsoft.onnxruntime:onnxruntime`
marked `<optional>true</optional>` since 0.10.0. A consumer that runs the ONNX-backed bots declares
`onnxruntime` itself; every other consumer never downloads the native runtime. The publish pipeline
verifies the optional flag (`assertOnnxRuntimeOptionalInPom`).

**Facade:** `JvmApi` — DFEN in, `GameState`, legal turns, `bestTurn` with a time budget, `evaluate`,
game-over queries. It exists because the Scala API leans on inline members and extension methods
that Java cannot bind to.

**Deliberately absent:** the JMH benchmarks (`benchmark`), the bot arena (`arena`) and the REPL/CLI
(`cli`) are sbt projects of this repository with `publish / skip := true`; the jar is additionally
checked to contain no `dicechess.engine.bench` classes (`assertNoBenchClasses`) and no coverage
instrumentation (`assertNoCoverageInstrumentation`). No model file, production profile or experiment
result ships in the jar.

## The npm packages

Both packages are one Scala.js link of `shared-rules/` + `shared/` + `js/`, differing only in the
target (JavaScript versus WasmGC). They export the same API and share one hand-written
`dicechess-engine.d.ts`:

- `DiceChess` — the primary facade: DFEN helpers, `getLegalUciMoves`, `applyMove`, `endTurn`, bot
  discovery and `getBestMove` with clock-aware budgets, doubling-cube and draw-offer decisions,
  `estimateEquity`, `perft`; see the [JavaScript API Reference](/dicechess-engine/architecture/javascript-api/).
- `EngineFacade` — the legacy facade (`getBotMove`, `getPieceTypeAt`, `applyMove`, `endTurn`), kept
  for existing callers.

Only code reachable from those exports survives Scala.js dead-code elimination: the ONNX searches
and the feature extractors are not in the bundle, the bots and the rules are. Package contents and
the JS-versus-Wasm trade-offs are covered in
[NPM Packaging & Local Integration](/dicechess-engine/guidelines/npm-packaging/); both tarballs carry
`README.md` and `LICENSE`.

A rules-only entry for JavaScript consumers is planned as a **subpath of the same package**
(`@fortemate/dicechess-engine/rules`), produced by Scala.js module splitting rather than a second npm
package — see [#222](https://github.com/fortemate/dicechess-engine/issues/222). Until it ships, JS
consumers import the full bundle as today.

## The Android source path

The on-device prototype consumes **no published artifact**: it compiles the engine sources from a git
submodule pinned to an engine commit, with a Gradle source set of `shared-rules/src/main/scala`,
`shared/src/main/scala` and `jvm/src/main/scala/dicechess/engine/jvmapi`. That set deliberately
excludes `jvm/src/main/scala/dicechess/engine/search` (the ONNX classes), the CLI, the arena and the
benchmarks, and its dependency allow-list contains only the Scala standard library. Because the
adapter calls `GreedySearch` as well as `JvmApi`, it needs the rules root **and** the engine root;
a rules-only source set would not compile it.

## One package, two jars

`dicechess.engine.search` is a **split package**: `dicechess-rules_3` contributes `TurnGenerator`,
`DiceRolls` and `KingCaptureProbability`, `dicechess-engine_3` contributes the bots and everything
else. This is legal on the JVM class path — Java only forbids split packages between *named* JPMS
modules, and neither jar declares a `module-info`. Two consequences:

- imports do not change when a consumer switches coordinate;
- do not put the two jars on the module path as named modules (automatic modules included); keep
  them on the class path.

## Version coupling

The two coordinates are released together and the engine POM pins the rules at its own version.
Do not mix versions (engine 0.11.1 with rules 0.11.0, or the reverse): the split package makes such a
mix compile against one half and run against another. Whether the two may ever version independently
is a separate decision ([#224](https://github.com/fortemate/dicechess-engine/issues/224)); until it is
taken, one version for both is the contract.

## Migration guide

### 1. Which coordinate do I need?

Look at what the project imports from `dicechess.engine.search`:

| The project uses… | Coordinate |
| :--- | :--- |
| only `domain`, `movegen`, and `TurnGenerator`, `DiceRolls`, `KingCaptureProbability` | `dicechess-rules_3` |
| any bot, `BotRegistry`, `Evaluator`, an evaluation term, a feature extractor, the opening book, `TimePolicy`, `MonteCarloEquity`, `DrawOfferLogic` | `dicechess-engine_3` |
| `JvmApi` (Java, Kotlin) | `dicechess-engine_3` |
| `OnnxEvalSearch` or `OnnxExpectimaxSearch` | `dicechess-engine_3` **plus** `onnxruntime` declared directly |

The compiler is the check: after switching to the rules coordinate, every remaining error names a
symbol that lives in the engine half — either the project genuinely needs the engine, or it carried an
accidental search dependency that can now be removed.

### 2. Rules-only JVM consumers: swap the coordinate

Requires 0.11.0 or later. Nothing else changes: same group, same version scheme, same imports.

```scala
// sbt
libraryDependencies += "com.fortemate" %% "dicechess-rules" % "<release>"
```

```xml
<!-- Maven -->
<dependency>
    <groupId>com.fortemate</groupId>
    <artifactId>dicechess-rules_3</artifactId>
    <version>RELEASE_VERSION</version>
</dependency>
```

```kotlin
// Gradle (Kotlin DSL)
implementation("com.fortemate:dicechess-rules_3:<release>")
```

Verify with the build tool's dependency tree: a rules-only consumer resolves `dicechess-rules_3`,
`scala3-library_3` and `scala-library` — no `dicechess-engine_3`, no `onnxruntime`. Container images
that copied the engine jar and its 54 MB native runtime shrink accordingly.

### 3. Feature, evaluation and bot consumers: stay on the engine

Projects that extract features (`KcpFeatures` and friends), run the built-in bots or register their
own through `BotRegistry` keep `dicechess-engine_3`. The rules arrive transitively.

### 4. ONNX consumers: declare `onnxruntime`

Since 0.10.0 the engine POM marks `onnxruntime` optional, so a project using `OnnxEvalSearch` or
`OnnxExpectimaxSearch` declares `com.microsoft.onnxruntime:onnxruntime` itself, pinned to the version
the engine was built against — see
[ONNX consumers declare onnxruntime directly](/dicechess-engine/guidelines/maven-artifact/#onnx-consumers-declare-onnxruntime-directly).
Do this **before** bumping to 0.10.0 or later; a project that forgets it fails at runtime with
`NoClassDefFoundError: ai/onnxruntime/...` the first time an ONNX bot is asked to move.

### 5. Java and Kotlin consumers: `JvmApi` stays in the engine

There is no rules-only Java facade. Java and Kotlin projects keep `dicechess-engine_3`; those that
need only the rules call the Scala classes directly from `dicechess-rules_3` and accept that some
`GameState` members are inline and therefore invisible from Java (the
[JVM API Reference](/dicechess-engine/architecture/jvm-api/) lists which ones `JvmApi` re-exposes and
why).

### 6. JavaScript and WebAssembly consumers: no action

Keep `@fortemate/dicechess-engine` or `@fortemate/dicechess-engine-wasm`. When the `./rules` subpath
lands (#222), rules-only front-ends can import it to shed the bots from their bundle; until then the
full bundle is the only entry and it keeps working unchanged.

### 7. Source-tree consumers: add the rules root

Anything that compiles the engine from its sources (the Android prototype today) must add
`shared-rules/src/main/scala` to its source set next to `shared/src/main/scala`: since 0.10.x the
rules files live there, and a source set that lists only `shared/` silently loses them. The
alternative is to switch to the published artifacts — `dicechess-engine_3` if the adapter uses a bot,
`dicechess-rules_3` if it does not.

## Checklist for a release consumer

- [ ] Both Maven coordinates resolve at the same version.
- [ ] Rules-only projects show no `dicechess-engine_3` and no `onnxruntime` in their dependency tree.
- [ ] ONNX projects declare `onnxruntime` directly.
- [ ] The jars stay on the class path, never on the module path as named modules.
- [ ] Source-tree builds include `shared-rules/src/main/scala`.
