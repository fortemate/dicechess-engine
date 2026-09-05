# AGENTS.md

Cross-compiled Scala 3 Dice Chess rules engine — the single source of truth for game rules across the dicechess ecosystem.

## Definition of Done — before every commit

<!-- dc-shared:definition-of-done v1 — keep identical across Fortemate Scala repositories -->

1. Format: `mise run format`. If `mise` is not on PATH: `~/.local/bin/mise exec -- sbt scalafmtAll`.
2. Gate: `mise run check` — the same command CI runs. If part of it cannot run in your sandbox (for
   example Docker for Testcontainers), run `mise exec -- sbt 'scalafmtCheckAll; Test/compile'` plus every
   suite that can run, and list what you skipped in the pull request.
3. Never publish unformatted Scala or code that does not compile: CI rejects both, and every red run
   costs a review cycle.

Sandboxed agents (Jules): the toolchain is provisioned by `scripts/jules-setup.sh` (Java, sbt, scalafmt
via mise). If a tool is missing, run `bash scripts/jules-setup.sh` instead of installing tools ad hoc.

<!-- /dc-shared:definition-of-done -->

## Project context

- Public repository, AGPL-3.0 (see `LICENSE`); contributions require a CLA (`CLA.md`, part of an open-core strategy) — external contributors sign inside their first PR (`.github/cla-signatures.json`, enforced by the `CI: CLA` workflow).
- Ships three artifacts per release: Maven Central jar `com.fortemate:dicechess-engine_3` (JVM), npmjs.org `@fortemate/dicechess-engine` (Scala.js, from `dist/`), and npmjs.org `@fortemate/dicechess-engine-wasm` (WebAssembly, from `dist-wasm/`). All three are also published to GitHub Packages as authenticated mirrors.
- Published contracts consumed by dicechess-analytics, the play site, and bots:
  - The DFEN string format (FEN extended with a 7th field = remaining dice pool) — parser in `shared/src/main/scala/dicechess/engine/domain/FenParser.scala`, canonicalization in `movegen/Dfen.scala`.
  - Two exported JS objects: `DiceChess` (`js/src/main/scala/dicechess/engine/api/JsApi.scala`) and `EngineFacade` (`js/src/main/scala/dicechess/engine/EngineFacade.scala`), both typed by the hand-written `js/dicechess-engine.d.ts`.
  - `JvmApi` (`jvm/src/main/scala/dicechess/engine/jvmapi/JvmApi.scala`) — the facade non-Scala JVM callers (Java, Kotlin) bind to, consumed by dicechess-bot-java. Everything outside it is Scala-shaped surface such consumers cannot use without reflection or unchecked casts, so treat the facade as the contract and the rest as internal. Its Java-callability is pinned by a Java-source test (`jvm/src/test/java/`) — a Scala-only test cannot catch a signature that stops being reachable from Java.
- Changing any of these contracts is a cross-repo event — flag it in the PR description and treat as high blast radius.

## Architecture map

- `shared/src/main/scala/dicechess/engine/` — cross-compiled core (JVM + JS + Wasm):
  - `domain/` — opaque-type game state: `Bitboard`/`Square`/`Piece`/`Color` (`Models.scala`), `Position`, `GameFlags`, `Move`, `FenParser` (DFEN), `Symmetry`.
  - `movegen/` — `MagicBitboards`, `LeaperAttacks`, `PawnGeneration`, `MoveGenerator`, `LegalMovesFilter`, `Dfen`. Allocation-sensitive hot path.
  - `search/` — `TurnGenerator` (exhaustive micro-move paths), `Evaluator`, `BotRegistry` (six built-in bots + runtime `registerCustomBot`), `KingCaptureProbability` (216 dice outcomes), `MonteCarloEquity`/`MonteCarloSearch`, `ExpectimaxSearch`, `OpeningBook`(+`Bot`/`Parser`), `TimeManager`/`TimeBudgetedSearch`, `DrawOfferLogic`, ONNX feature extractors (`OnnxFeatures`, `RichFeatures`, `KcpFeatures`).
- `jvm/src/main/scala/dicechess/engine/` — entry point `Main.scala` (JLine REPL CLI, `cli/`), JVM-only ONNX inference bots (`search/OnnxEvalSearch.scala`, `OnnxExpectimaxSearch.scala` on onnxruntime), and `jvmapi/JvmApi.scala` — the Java/Kotlin-facing facade (the JVM row's counterpart to `js/`'s `EngineFacade`). ONNX bots are absent from the npm bundles.
- `arena/src/main/scala/dicechess/engine/bench/` — non-published sbt project (`arena`): six arena runners (`BotMatchRunner`, `TimedArenaRunner`, `OpeningBookArenaRunner`, `OnnxArenaRunner`, `OnnxExpectimaxArenaRunner`, `OnnxTimedArenaRunner`), SPRT/pentanomial machinery, and measurement probes.
- `js/` — Scala.js facade layer; `.wasm/` — the `rootWasm` project relinking the same sources to WebAssembly (ES2022 + WasmGC).
- `benchmark/` — JMH micro-benchmarks (excluded from coverage and publishing).
- `docs/` — Astro + Starlight documentation site (see Documentation below).
- There is no HTTP/WebSocket API, no database, and no effect system here — plain Scala 3 with opaque types; errors via `Either`.

## Commands

Prerequisites first:

```bash
mise install     # java temurin-25, sbt 2.0.8, node 26, lefthook, betterleaks, scalafmt (pinned), gh, jq
mise run setup   # brew install universal-ctags tree + install git hooks (sbt comes from mise, no brew needed)
```

- Node is required even for plain `sbt 'testOnly *'` — JS/Wasm tests execute on Node. Missing/old Node fails the JS test run, not just docs.
- Maven Central and npmjs.org consumption require no token. Consuming a GitHub Packages mirror needs a `read:packages` token plus the appropriate npm or Maven registry configuration; inside this repo use `mise run publish:local` for JVM development instead.

Daily tasks (defined in `mise.toml` + executable file tasks under `.mise/tasks/`):

```bash
mise run check          # THE pre-PR gate: scalafix check, clean, scalafmt check, coverage test + report
mise run test           # sbt testOnly * (JVM + JS + Wasm on Node)
mise run format         # sbt scalafmtAll; scalafixAll — git add new .scala files FIRST
mise run compile | run | console | coverage | clean
mise run bench | bench:quick | bench:filter <regex>     # JMH benchmarks (JVM)
mise run bench:js | bench:wasm | bench:all              # Node.js benchmarks (JS / Wasm)
mise run arena [base] [games]                           # bot arena (BotMatchRunner)
mise run arena:timed | arena:book                       # time-controlled / opening-book arenas
mise run arena:evaluate [bot] [baseline] [fixtures]     # deterministic search scenario comparison
mise run js:build | js:dev | wasm:build                 # bundles
mise run publish:local                                  # JVM jar to local Ivy for downstream dev
mise run docs:dev | docs:build                          # docs site (runs the doc generators first)
sbt rootJVM/doc                                         # Scaladoc with COMPILED snippets — not in `mise run check`,
                                                         # but enforced in `ci.yaml` on every PR (see Quality gates)
```

- ONNX arena runners have no mise task — run via `sbt "arena/runMain dicechess.engine.bench.OnnxArenaRunner <model.onnx> ..."`. Trained models are never committed (the tiny `synthetic_test_model.onnx` test fixtures are the deliberate exception).
- Releases are human-only: `gh workflow run release.yaml -f bump=patch|minor|major` (or local `mise run release:prepare`). Propose, never execute.

Common failure signatures:

- Pre-commit hook rejects a file that `mise run format` claims is already formatted → the file is untracked; `git add` it, format again.
- `sbt rootJVM/doc` errors inside a Scaladoc comment → a non-Scala example sits in a ```scala fence (see Gotchas).
- Second concurrent `sbt` invocation hangs/fails → sbt server socket collision; run sequential commands in one sbt session (#326).

## Quality gates — repository specifics

- `mise run check` passes locally. It is stricter than PR CI: **PR CI (`ci.yaml`) does not run scalafix** — only `check` and the release/publish workflows do, so code can pass PR CI yet fail at release.
- Statement coverage >= 90% for `rootJVM`, >= 70% for `arena`, enforced by `build.sbt` (`coverageFailOnMinimum`). JVM-only; `benchmark/` and `.*Main\.scala` excluded.
- The compiler is a gate: `-Werror`, `-Wunused:all`, `-language:strictEquality`, `-Yexplicit-nulls` — any warning fails the build.
- CI also runs a SonarCloud scan; PR policy workflow enforces branch naming and issue links (see Git & PR workflow).
- `ci.yaml`'s `Scaladoc` step runs `sbt rootJVM/doc` on every PR (the workflow-file exception below applies here too)
  and fails on an unresolved `[[...]]` cross-reference or a broken snippet fence (#621) — `-Werror` does not reach
  the doc tool (it silently drops unsupported `scalacOptions`), so this grep-on-log step is what makes those
  failures loud instead of scrolling past as warnings. It is not part of `mise run check` (a fresh `doc` compile is
  a second full recompile pass check does not otherwise pay for), so run `sbt rootJVM/doc` locally before pushing
  Scaladoc changes rather than relying on CI to catch it first.
- Per-change-type extras:
  - Touched Scaladoc → run `sbt rootJVM/doc` locally; CI's `Scaladoc` step re-checks it on the PR.
  - Touched `movegen/` or `search/` hot paths → attach JMH evidence (`mise run bench:filter <pattern>`) to the PR.
  - Changed bot behavior/strength → attach an arena run (`mise run arena` or `arena:timed`).
  - Touched `.github/workflows/` → trigger the run manually with `gh workflow run ci.yaml`; such PRs have been
    observed not to trigger `pull_request` CI. `main` requires a PR and rejects deletion/force-push, but does not
    require approvals or status checks — extra care.
  - Changed the JS API surface → update `js/dicechess-engine.d.ts` in the same PR.

## Code conventions

- Scala 3 "new" syntax enforced (scalafmt `convertToNewSyntax`, Scala3 dialect): braceless bodies with colons, extension methods, opaque types. `maxColumn` 120, 2-space indent, LF.
- Forbidden by scalafix `DisableSyntax`: `null`, `return`, `throw`. Errors via `Either` (e.g. `FenParser.parse`).
- `strictEquality` is on: derive `CanEqual` before using `==` on custom types. `-Yexplicit-nulls` is on: Java interop values are typed `| Null` and must be handled.
- Opaque types must document their bitwise memory layout in Scaladoc; companion objects carry the ops.
- Hot paths (`movegen/`, `search/`): bitwise ops on `Long`, `inline`/opaque zero-cost abstractions, avoid allocations in loops.
- Scaladoc: document *why*, not *what*; Markdown fences (never `{{{ }}}`); `[[Type]]` cross-references; strictly English.

## Testing conventions

- MUnit `FunSuite` + `munit-scalacheck` for properties. Suites named `*Suite`/`*Spec`; sentence-style test names; regression suites cite the issue number in the Scaladoc header.
- Two accepted ways to build positions: most suites use `FenParser.parse` + `.withDicePool(...)` directly; the movegen golden fixtures use the `ChessDsl` test DSL (`shared/src/test/scala/dicechess/engine/movegen/ChessDsl.scala`: `"<fen>".withDice(...)` builders taking a die or a tuple, or a FEN that already carries its dice pool in the 7th field, plus `Move.toNotation`). Both patterns are fine.
- The movegen golden catalog is Scala, not JSON: `shared/src/test/scala/dicechess/engine/movegen/MoveGenFixtures.scala`. It compiles into the JVM, JS and Wasm test runs, so the golden net is cross-platform (#123). It doubles as docs-site content via `DocGenerator` — changing it changes the published docs.
- JSON fixtures that remain are JVM-only: `shared/src/test/resources/movegen/perft_suite.json` (`PerftSpec`). King-capture probability cases live in the shared Scala fixture `shared/src/test/scala/dicechess/engine/search/KingCaptureFixtures.scala`, which also feeds `KingCaptureDocGenerator`.
- Single suite: `sbt "rootJVM/testOnly dicechess.engine.search.TurnGeneratorSuite"` (JVM-only, fastest loop). Beware: a non-matching FQCN exits 0 with zero tests run — confirm the suite actually executed.
- Shared-code tests also run on the JS/Wasm Node runner, which is slower — avoid tight time budgets in tests or they will flake there (a MonteCarlo test already timed out once).
- No Docker is needed for any test in this repo.

## Gotchas

- On macOS, `gh` stores its login in Keychain, but an inherited `GH_TOKEN` takes precedence over that
  credential. If `gh auth status` reports an invalid token even though the Keychain login is valid, run
  `GH_TOKEN="" gh auth status` and prefix subsequent `gh` commands the same way. In a restricted Codex
  sandbox, Keychain or network access may still be unavailable; retry the command with elevated sandbox
  permission before asking the user to log out or authenticate again. Never print `gh auth token` or copy
  the Keychain credential into a file.
- Every ```` ```scala ```` fence in Scaladoc is **compiled** by `sbt rootJVM/doc` (`-snippet-compiler:compile`). Non-Scala examples (JSON, pseudocode) must use ```` ```text ````/```` ```json ```` fences — `mise run check` will not catch a bad fence, but `ci.yaml`'s `Scaladoc` step does, on the PR that introduces it (see Documentation).
- `git add` new `.scala` files **before** `mise run format`: `sbt scalafmtAll` skips untracked files, then the native-scalafmt pre-commit hook fails the commit.
- Do not "optimize" the `check` task order: `clean` runs before `scalafmtCheckAll` deliberately — sbt-scalafmt's warm cache can skip a misformatted file (#354).
- `publish.yaml` and `release.yaml` duplicate Maven Central and GitHub Packages steps intentionally: tags created by `release.yaml` via `GITHUB_TOKEN` do not trigger `publish.yaml` (GitHub anti-recursion). Both dispatch and wait for the canonical `npm-publish.yaml` Trusted Publishing workflow because npm permits only one trusted publisher per package. Edit both entry points in sync and keep npmjs.org publication inside the canonical workflow.
- `deploy-docs.yaml` dynamically discovers `target/out/jvm/scala-<version>/dicechess-engine/api` for the Scaladoc merge.
- Turn maximality is measured in **dice consumed, not move count** — castling spends two dice in one move; the active color never changes within a turn. Regression suites: `TurnGeneratorSuite` (#347), `EnPassantMicroMoveSuite`.
- The engine does **not** support Chess960 castling — squares e1/h1/a1 are hardcoded.
- Root `package.json` version is dead weight — the real version comes from sbt at `package:prepare` time. Never "fix" or trust it.
- `BotRegistry` is a process-wide mutable singleton (`registerCustomBot`) — arena runners and the JS `registerOpeningBookBot` mutate global state; isolate tests that depend on registry contents.
- The pinned scalafmt version in `mise.toml` must exactly match `version` in `.scalafmt.conf` — the native pre-commit CLI does not auto-dispatch versions.
- Doc generators must run in ONE sbt session (`mise run docs:generate:all`); two parallel sbt boots collide on the server socket (#326).
- 🔑 **sbt 2's thin client reuses one server across workflow steps, and `sys.env` is frozen at server start.** A later step's `env:` is invisible to the build: the v1.11.4 release died with `401 Unauthorized` because publishing credentials were absent when the validation server started. The same reuse carries session settings such as `coverageEnabled := true` forward, and the v0.6.0 release later hung in the thin client before its publish command started. Release publishing and package builds therefore use `sbt --server`, which runs a foreground process for that command so every registry sees the intended environment. JVM publication additionally runs `clean` and `rootJVM/assertNoCoverageInstrumentation` to prove that its artifacts contain uninstrumented bytecode; those JVM-only safeguards do not apply to the JS/Wasm package builds. Do not use `--no-server`: in sbt 2 it still runs the thin client and merely fails when no server exists.
- A broken workflow **does not fail CI** — it silently stops running, so its checks disappear rather than turn red, and `publish.yaml`/`release.yaml` are never exercised by a pull request at all. Invalid indentation reached `main` that way once (a step lost its `- name:` and its `run:` was out-dented). Both the pre-commit hook and `ci.yaml` now parse every `.github/workflows/*.y*ml` with Ruby's YAML. When editing a workflow: never use a multi-line regex (a trailing match silently swallows the rest of the file), and verify against the last **known-good** commit — not your own previous commit — that step names, order and counts are unchanged.
- Exclude `.claude/worktrees/` from repo-wide greps — a leftover worktree contains a full source copy and produces duplicate hits.
- sbt 2 rejects multiple CLI arguments and space-separated command lists (sbt 1 style) — every multi-step invocation must be ONE string joined with `;` (e.g. `sbt 'clean; coverage; testOnly *; coverageReport'`). This affects every `mise` task and CI workflow step that chains sbt commands.
- sbt 2's bare `test` key is defined as `testQuick`'s "skip if unchanged" semantics, not sbt 1's "run everything" — a warm build can silently report "0 tests, success". `testOnly *` always runs the full suite and is used everywhere `test` used to mean "run everything" (mise tasks, CI, publish/release workflows).
- `build.sbt`'s explicit root project (`dicechessEngineAggregate`) carries the whole `test`/`coverage` gate via `.aggregate(rootJVM, rootJS, rootWasm, benchmark, arena, cli)` — sanity-check `mise run test` still prints all test totals.
- sbt 2 defaults `Test / exportJars` to `true` (sbt 1 defaulted to `false`), packing test resources into a CAS-cached jar. `OnnxEvalSearchSpec`/`OnnxExpectimaxSearchSpec` resolve the bundled ONNX test model via `getClass.getResource(...).getPath`, which needs a real filesystem path — `build.sbt` sets `Test / exportJars := false` on `rootJVM` to keep it working.
- 🔑 **Coverage must run against a throwaway build cache** (#531). Coverage on Scala 3 is the compiler's own `-coverage-out:<dir>`, and the *compiler* is what creates that directory and writes the `scoverage.coverage` instrumentation metadata. sbt 2's global cache (`~/Library/Caches/sbt/v2`, `~/.cache/sbt/v2` on Linux) can serve `compile` outright — then the compiler never runs, nothing is instrumented, and `coverageReport` only *warns* "No coverage data, skipping reports", so `coverageFailOnMinimum` cannot fail and the 85% gate passes having measured nothing. It can instead surface as ~72 × `FileNotFoundException` on `scoverage.measurements.*` (presenting as `NoClassDefFoundError: Could not initialize class …FenParser$`), taking down the whole JVM suite. **`clean` does not help — the cache lives outside `target/`.** Every coverage entry point (mise `coverage`/`check`, `ci.yaml`, `publish.yaml`, `release.yaml`) therefore runs `sbt shutdown` + `rm -rf target/covcache`, then `sbt -Dsbt.global.localcache="$PWD/target/covcache" '…; rootJVM/coverageDataCheck; coverageReport'`. All three parts matter: the property is read **only at server startup** (an already-running server silently ignores it and reuses the global cache), the `rm -rf` guarantees a cold start, and `coverageDataCheck` fails loudly if metadata is missing anyway. Carry all of it over to any new coverage path.
- Do **not** "simplify" the above to `set Global / cacheStores := Nil`. It is the obvious-looking fix and it does change the setting's value, but the cache is not read from it — verified ineffective, the compile is still served from cache. Project-scoped `cacheStores` does nothing either.
- Any build task whose job is to *detect* a bad state must be `Def.uncached`. sbt 2 caches task results, so `coverageDataCheck` without it replays its own earlier success and passes even once the metadata is gone — the very trap it guards against.
- `sbt -batch` talks to a **persistent server**, so a `set` from one invocation leaks into later ones: after any `sbt coverage ...`, `coverageEnabled` stays true for the whole session. Run `sbt shutdown` between runs when comparing behaviour, or conclusions about caching/coverage will be measuring leaked state rather than the build.

## Git & PR workflow
<!-- dc-shared:git-pr v3 — keep identical across dicechess repos -->

- Follow the branch-name and Issue-link contract in `dc-shared:issue-management v6`. Agents that
  choose a branch name follow its canonical grammar; integration-owned branch names are accepted
  only when the target repository's live PR policy allows them.
- **The branch type chooses the release-notes section** — `.github/labeler.yml` turns it into a
  PR label and `.github/release.yml` groups by that label. `task/` is issue-driven work and counts
  as a feature, so a fix belongs on `bug/` even when it closes an issue; `chore/` is the grab-bag
  and files under "Other Changes". A type that maps to no label mis-files the whole PR: play-api
  v0.16.0 shipped ten features under 📚 Documentation because every branch was `task/` (which
  mapped to nothing) while every PR touched AGENTS.md (which mapped to `documentation`).
- Before editing anything: run `git status`. If the tree has unrelated uncommitted work,
  stop and report — never let it bleed into your commit.
- Stage specific files by name. `git add -A` / `git add .` are forbidden.
- Commits, PR descriptions, issues, and review replies are English-only. Commit subjects
  use conventional style: `feat: …`, `fix: …`, `docs: …`, `test: …`, `chore: …`.
- Before opening a PR: make the repo check task pass locally. Never pipe test output
  through `grep`/`head` — it masks exit codes.
- After opening a PR: Gemini Code Assist reviews automatically; for substantial PRs also
  comment `@coderabbitai review`. Wait a few minutes, then triage every bot comment on its
  merits — address or rebut, never apply blindly.
- The human owner reviews, approves, and merges. Never merge a PR, never push tags.
- Split large work into small, reviewable PRs.

**Repo-specific note:** `enforce-pr-policy.yaml` accepts a non-conventional integration-owned
branch only when the pull-request body explicitly includes `Closes #<id>`, `Fixes #<id>`, or
`Resolves #<id>`. Agents that choose a branch name still follow the canonical shared grammar.

## Issue management
<!-- dc-shared:issue-management v6 — keep identical across Fortemate repositories -->

- Use the native GitHub Issue Type as the canonical work classification:
  - `Bug` for unexpected or incorrect behavior.
  - `Feature` for a request, idea, or new user-visible capability.
  - `Task` for a specific piece of engineering, research, maintenance, or documentation work.
- Never commit directly to a repository's default branch. For branches whose names the agent controls, use `<type>/<short-description>` or `<type>/<issue-id>-<short-description>` with the preferred types `task|feat|bug|refactor|chore|docs|ci|test|perf`. The legacy `feature/` and `fix/` forms are compatibility aliases, not preferred names for new agent-created branches. If a branch name contains an Issue id, the pull-request body must close that exact independently actionable Issue. Before dispatching an external tool or opening its pull request, read the target repository's live PR-policy workflow. A tool-managed branch whose name cannot be controlled, including a Jules `jules-*` branch, is acceptable only when that live policy permits non-conventional issue-linked branches and the pull-request body closes the delegated leaf Issue. Never edit a workflow merely to make a generated branch pass; if the exception is absent, stop and report the repository-policy prerequisite.
- Do not apply `bug` or `enhancement` labels to Issues merely to repeat their Type. Keep those labels for pull-request release classification. On Issues, labels describe only a technical domain or cross-cutting concern, and only existing repository labels may be used.
- Applying or reapplying the `jules` label is a live execution trigger. On an open Issue the label denotes the current Jules delegation; on a closed Issue it may remain as historical execution metadata. By default, agents must never apply or reapply it. Exception: a top-level Codex or Claude Code orchestrator directly handling the current human request may apply or reapply `jules` only when that human is authorized to direct work in the target repository and explicitly authorizes Jules delegation for the current parent task. Jules, Antigravity, CI, delegated subagents, and agents without that task-scoped authorization must never apply or reapply `jules`, start Jules through the label, CLI, API, or another mechanism, or recursively delegate work.
- Removing `jules` is cleanup, not dispatch. During takeover of an open Issue, only the top-level primary orchestrator acting under the original task-scoped delegation authorization or an explicit recovery request from a current user authorized to direct work in the target repository may remove it. Separately, a top-level Codex or Claude Code agent directly triaging an already reopened Issue may remove a stale historical `jules` label without Jules-delegation authorization only when the latest application of `jules` predates the latest reopen event; if that ordering cannot be verified, do not remove it. A request to triage an already reopened Issue authorizes only this verified historical-label cleanup. This narrow cleanup exception grants no authority to apply or reapply `jules`, take over active Jules work, or delegate work. Jules, Antigravity, CI, delegated subagents, and all other agents must never remove `jules`.
- Before an authorized orchestrator applies `jules`, it must read the Issue back and verify that it is an open, independently mergeable leaf Issue with no blocker, competing owner or pull request, overlapping active work, or dependency on unmerged changes; belongs to Fortemate Engineering; has Status `Ready`, Execution tier `Routine`, and `spec:ready`; and contains self-contained Context, Objective, testable Definition of Done, Guards, Verification gates, Non-goals, and a bounded file-level blast radius. Apply `jules` last, read it back, monitor the Issue/session/pull request through completion, review the result, and take over stalled work. Never dispatch the same task through both the label and Jules CLI. Follow the `jules-delegation` skill when it is available.
- Actionable Jules feedback must be a submitted pull-request conversation or inline comment from the GitHub user who triggered the task, explicitly mention `@jules`, and be followed by acknowledgement and re-review of the resulting commit. A review body is not a Jules feedback channel. A delegated pull request and its commits may close only its leaf Issue, never its parent or sibling.
- Removing `jules` or using Jules CLI pull/teleport does not prove that the remote session stopped. Never write concurrently to a possibly active Jules branch. Continue the existing pull request only after terminal state is confirmed; otherwise recover verified work in an isolated branch and replacement pull request.
- After successful Jules work closes an Issue, retain `jules` as an audit marker. If that Issue is reopened, remove the historical label before triage under the reopened-Issue cleanup rule above; applying it again requires fresh task-scoped authorization and all dispatch checks, because a new label event starts a new session. During an authorized takeover of an open Issue, the permitted primary orchestrator must remove `jules` and record `outcome:escalated`.
- Before creating or updating an Issue, search relevant Fortemate repositories across open and closed Issues for semantic duplicates. Read the live Types, field options, labels, assignees, and relationships before mutation; never rely on cached IDs or invent metadata.
- GitHub-facing work items are English-only. Use the appropriate Issue Form when available, or `gh issue create --body-file <file>` for CLI creation; never pass a multiline body inline. Every Issue must contain `Context`, `Objective`, and a testable `Definition of Done`.
- Add every actionable Issue (never pull requests) to the organization Project [Fortemate Engineering](https://github.com/orgs/fortemate/projects/1).
- Use Project `Status` only for workflow state:
  - `Backlog` means triaged but not committed for active work.
  - `Ready` means sufficiently defined and available to start.
  - `In progress` means someone is actively working on it.
  - `In review` means implementation is waiting for review or validation.
  - `Done` means the Issue is closed.
- Set the Project `Execution tier` during triage:
  - `Routine` for a bounded, reversible task suitable for Jules or another low-cost agent.
  - `Mid` for a well-scoped task that needs a stronger coding agent with iterative supervision.
  - `Frontier` for architecture, public contracts, complex diagnosis, or other high-blast-radius work; human-led.
  - `Human-only` for releases, production operations, secrets, or legal decisions that must never be delegated.
  - `Decompose` for work too large to route as-is: split it into sub-issues, tier each, then re-tier or close the parent.
  - A blank value means the Issue has not been routed yet.
- Leave the organization `Priority` Issue field blank for normal work. Set it only to deliberately jump the queue: `Urgent` for an immediate incident, security problem, or release blocker; `High` for important or blocking planned work. Never replace organization fields with labels or duplicate Project fields.
- Triage establishes Type, Execution tier, applicable labels, Project membership, Status, and relationships (plus Priority only for queue-jumpers). Assign an Issue only when a person owns its next action, and assign the active owner before moving it to `In progress`; unassigned means agent pool or no current owner, not low priority.
- Use parent/sub-issue relationships for independently actionable decomposition, `Blocking`/`Blocked by` for hard ordering dependencies, and `Relates to` for non-blocking associations. If the live UI or API cannot create a relation, add an explicit typed cross-reference that preserves its semantics: `Parent:`, `Sub-issue:`, `Blocking:`, `Blocked by:`, or `Related:` followed by `owner/repository#<id>`. Do not simulate relationships with title prefixes, labels, or duplicate task lists.
- When a pull request targets the repository's default branch and fully completes an Issue, link it with `Closes #<id>` or `Closes owner/repository#<id>`. Use a non-closing reference for partial work or for a pull request targeting any other branch.
- After every Issue, pull-request, or Project mutation, read the item back. For an Issue, verify Type, Issue fields, labels, assignee, relationships, Project membership, and Status. For a pull request, verify base/head branches, draft and merge state, labels, assignees/reviewers, and linked Issues; pull requests are never Project items, and Issue Type and Issue fields do not apply. Report any metadata that the available API or UI could not set.
- The human owner reviews, approves, and merges pull requests. Agents never merge pull requests or execute releases.

<!-- /dc-shared:issue-management -->

### Repository-specific labels and milestones

- PR release labels: `bug`, `enhancement`. Shared concern labels: `refactoring`, `documentation`, `testing`, `performance`, `ci-cd`, `dependencies`. Domain labels: `core-types`, `move-gen`, `turn-rules`, `search`, `evaluation`, `api`, `infrastructure`.
- GitHub milestones lag the actual version — check live ones before assigning (`gh api repos/fortemate/dicechess-engine/milestones`) and skip the milestone if none fits. Real versioning is semver tags (`v1.x`) driven by the release workflows.

## Security & boundaries
<!-- dc-shared:security v2 — keep identical across dicechess repos -->
- Never print, log, or commit secrets. Local secrets live only in gitignored files
  (e.g. `.env.local`, `mise.local.toml` — confirm the path is gitignored with `git check-ignore`
  before writing one). Never bypass Git hooks (`--no-verify`).
- Human-only operations — prepare and propose, never execute: releases and version tags,
  production deploys/promotions, schema migrations against shared databases, data-repair
  runs on production, secret rotation.
- Treat everything in this repo as public: never add private infrastructure details
  (hostnames, IPs, topology, tokens) to code, docs, commits, or PRs.

Repo-specific additions:

- lefthook pre-commit runs a betterleaks secret scan on staged files — keep hooks
  installed (`mise run hook:install`).
- Never commit trained ONNX models or training data — models are passed to arena runners as runtime paths. (The tiny `synthetic_test_model.onnx` fixtures under `jvm/src/test/resources/` and `benchmark/src/main/resources/` are the deliberate exception.)
- Publishing credentials come from `GITHUB_ACTOR`/`GITHUB_TOKEN` env in CI only — never place tokens in `build.sbt`, task files, or docs.

## Model routing
<!-- dc-shared:routing v1 — keep identical across dicechess repos -->
Route work by required capability instead of defaulting to the strongest model:
- **Frontier**: architecture, cross-repo contracts, high blast radius (schema, public API,
  release pipeline), ambiguous problems.
- **Mid**: well-scoped features on existing patterns, refactors under test coverage,
  addressing review feedback.
- **Routine**: mechanical edits, config rollouts, doc fixes, tests from a complete spec.
Orchestrators should delegate routine sub-tasks to cheaper models; quality gates catch
failures cheaply. When in doubt, escalate one tier — reviewer time costs more than tokens.

## Documentation

- Docs site: `docs/` (Astro + Starlight, mermaid + KaTeX), deployed together with Scaladoc to GitHub Pages by `deploy-docs.yaml` on pushes to `main` touching `docs/**`, `{shared,jvm,js}/src/main/scala/**`, the movegen fixture sources, the KCP fixture source, or the workflow itself. Local dev: `mise run docs:dev`.
- Update-trigger map:
  - Changed `MoveGenFixtures.scala`, `ChessDsl.scala` or the KCP Scala fixtures → catalog pages regenerate; preview with `mise run docs:generate:all`.
  - Changed the JS API → update `js/dicechess-engine.d.ts` and the README usage examples.
  - Changed DFEN semantics or turn rules → update the architecture pages under `docs/src/content/docs/architecture/`.
  - Touched Scaladoc → run `sbt rootJVM/doc` locally before pushing.
- All documentation, comments, and commit text: English only.
