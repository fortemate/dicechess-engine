---
title: Build with the Engine
description: Choose an integration for a browser game, a TV application, a JVM service or a WebAssembly worker, using one shared Dice Chess rules implementation.
---

The engine is a library. It calculates legal play, transforms game state and can choose a bot's
turn. Your application owns rendering, input, persistence and any network protocol.
One Scala 3 rules implementation is compiled for JVM, JavaScript and WebAssembly, so each
client can use the same rules without rewriting them in its UI language.

## Choose a starting point

| What you are building | Start here | API |
| --- | --- | --- |
| Browser game, Node.js bot or compatible JavaScript app | `@fortemate/dicechess-engine` | `DiceChess`, including legal turn trees and bots |
| Lightweight JS board tooling or basic rules operations | `@fortemate/dicechess-engine/rules` | Rules subset; no legal turn tree or bots |
| Scala service that validates and enumerates complete turns | `com.fortemate:dicechess-rules_3` | Direct Scala API |
| Scala bot, Java or Kotlin application | `com.fortemate:dicechess-engine_3` | Direct Scala API or `JvmApi` |
| JavaScript application targeting a WasmGC runtime | `@fortemate/dicechess-engine-wasm` | Same full JavaScript-facing API |

**The two rules-only paths are different.** The Maven rules jar includes `TurnGenerator`;
the lightweight npm `./rules` entry does not include `getLegalTurnTree` or `getPlayableDice`.
An interactive JavaScript game that follows complete legal turns should use the full npm entry.
The [artifact guide](/dicechess-engine/architecture/artifacts/) lists the exact boundaries.

## JavaScript and TypeScript

```bash
npm install @fortemate/dicechess-engine
```

```typescript
import { DiceChess } from '@fortemate/dicechess-engine';

// White has rolled a pawn and two knights. The seventh DFEN field holds the dice.
const rolled = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PNN';
const turns = DiceChess.getLegalTurnTree(rolled);
const choices = Object.keys(turns); // Legal first actions, in UCI notation.
console.log(choices);

// The UI offers only keys of the current tree node.
const afterPawn = DiceChess.applyMove(rolled, 'e2', 'e4');
const continuations = turns['e2e4']; // Follow this branch for the rest of the turn.
console.log(afterPawn, Object.keys(continuations));

// The dice the rest of the turn can still spend. Pass the roll and the moves, not afterPawn.
const playable = DiceChess.getPlayableDice(rolled, ['e2e4']); // "NN": dim any other die
```

Generate the tree once for the roll and descend through it as actions are played.
`applyMove` carries the unspent dice forward, but is not a complete legality validator.
Recomputing first moves after each action loses the original turn's constraints.
At a leaf, check for king capture; if the game continues, call `endTurn` before the next roll.
For a valid DFEN with dice assigned and a game still in progress, an empty tree at the start
of the roll means a forced pass. Invalid DFENs and positions without dice also return an
empty tree; validate the state and assign the roll before interpreting it as a pass.

See the [turn lifecycle](/dicechess-engine/architecture/turn-lifecycle/) for the complete
controller contract and the [JavaScript reference](/dicechess-engine/architecture/javascript-api/)
for bot discovery, search and error behavior. Existing integrations upgrading to 0.13.0
should review its [release notes](https://github.com/fortemate/dicechess-engine/releases/tag/v0.13.0):
`applyMove` now preserves remaining dice and refuses moves unsupported by the dice pool.

### Browser client prototype

The [public browser client](https://github.com/fortemate/dicechess-play) is a working
prototype under development. It uses SvelteKit and a Web Worker running the npm engine:
the UI presents the board and handles input, while the worker executes local bot search.
Its source illustrates an integration; it is not a finished product.

### Dice Chess TV

[Dice Chess TV](https://fortemate.github.io/dicechess-tv/) uses **React Native for Vega** for
its native screens and remote input, with a pure TypeScript game controller calling
`@fortemate/dicechess-engine`. It follows the engine's legal turn tree and reads the dice
left from `applyMove`, rather than implementing a second set of rules.

Hotseat play, on-device opponents and mid-turn save/resume show how the library fits an
offline application. The TV app owns D-pad navigation, board rendering, sound and saved-game
storage. The engine owns legal turns and bot decisions.

The project's [demo](https://www.youtube.com/watch?v=Q7wWAmUp2Sc) was recorded on the
**Vega Virtual Device**. Consult the TV project's current testing information for physical
Fire TV coverage; this integration is not a claim of compatibility with every TV platform
or every React Native runtime.

Source: [game controller](https://github.com/fortemate/dicechess-tv/blob/main/src/core/game.ts)
and [local bot adapter](https://github.com/fortemate/dicechess-tv/blob/main/src/core/bot.ts).

## Scala, Java and Kotlin

Use Maven Central; no registry token is required. Choose the rules jar for direct Scala
rules work, or the engine jar for search and `JvmApi`. All coordinates in one release share
the same version.

```scala
libraryDependencies += "com.fortemate" %% "dicechess-engine" % "<release-version>"
// Or, for rules-only Scala code:
// libraryDependencies += "com.fortemate" %% "dicechess-rules" % "<release-version>"
```

Use a version from the [published releases](https://github.com/fortemate/dicechess-engine/releases).
Java and Kotlin callers use `JvmApi` for DFEN parsing, dice assignment, legal turns,
bot decisions and game-over queries. The rules jar alone does not contain that facade.
See [Maven setup](/dicechess-engine/guidelines/maven-artifact/) and the
[JVM API reference](/dicechess-engine/architecture/jvm-api/).

Android is a separate integration path: the documented
[source build](/dicechess-engine/architecture/artifacts/#the-android-source-path) selects the
shared sources and facade rather than consuming the published JVM jar unchanged.

ONNX-backed searches are JVM-only. Applications using them explicitly add ONNX Runtime
and supply a compatible model; the engine does not bundle trained weights.

## WebAssembly

`@fortemate/dicechess-engine-wasm` exports the full JavaScript-facing API through a WasmGC
build. It needs a runtime with WasmGC support and the package's JavaScript loader; it is
not a standalone C ABI or a universal native binary. There is no Wasm `./rules` subpath.

Start with [npm integration](/dicechess-engine/guidelines/npm-packaging/) and compare the
builds using the [JS/Wasm benchmark guide](/dicechess-engine/guidelines/js-wasm-benchmarks/).
Choose based on your actual workload and target runtime, rather than assuming Wasm is faster.

## Keep application state and engine state together

- Keep the full DFEN, including remaining dice, while a turn is in progress.
- Preserve the original turn tree and current branch, or enough action history to reconstruct
  them, when saving mid-turn. A mid-turn DFEN alone does not retain the original path constraints.
- Leave the active color unchanged during micro-moves; advance it only at the turn boundary.
- Keep expensive search off the UI thread where your runtime provides a worker or equivalent.
- Test the package in the actual application runtime, including bundling and lifecycle behavior.

The engine does not supply a board widget, persistence layer or HTTP server. This separation
lets a remote-controlled TV board and a browser game share the same rules while presenting
different experiences. See [runtime choices](/dicechess-engine/infrastructure/oracle-cloud/)
for local execution and hosted-service tradeoffs.
