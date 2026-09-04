---
title: Dependency Updates
description: How Dependabot keeps sbt, npm, and GitHub Actions dependencies current in the Dice Chess Engine.
---

The Dice Chess Engine uses **Dependabot version updates** to keep dependencies current and **Dependabot security updates** to open targeted fixes when GitHub identifies a vulnerable version. Update configuration lives in [`.github/dependabot.yaml`](https://github.com/fortemate/dicechess-engine/blob/main/.github/dependabot.yaml).

---

## Covered Ecosystems

| Ecosystem | Directory | Schedule | Covers |
| :--- | :--- | :--- | :--- |
| `sbt` | `/` | Weekly, Friday | Library dependencies in `build.sbt`, sbt plugins in `project/plugins.sbt`, and the sbt version in `project/build.properties` |
| `github-actions` | `/` | Weekly, Tuesday | Action versions pinned in `.github/workflows/**` |
| `npm` | `/docs` | Weekly, Wednesday | The Starlight/Astro documentation site's JavaScript dependencies |

Dependabot pull requests [#128](https://github.com/fortemate/dicechess-engine/pull/128) through [#130](https://github.com/fortemate/dicechess-engine/pull/130) confirm that the `sbt` ecosystem integration updates the sbt version and Scala/JVM library coordinates.

## Vulnerability Detection and Pull Request Enforcement

The dependency graph combines GitHub's native npm and GitHub Actions discovery with a resolved sbt snapshot submitted from `main` by `.github/workflows/dependency-graph.yaml`. Submitting the resolved graph is necessary because GitHub cannot infer the engine's complete transitive JVM graph from `build.sbt` alone. The submitted graph powers Dependabot alerts and security updates for merged Scala/JVM dependencies.

On pull requests, `.github/workflows/dependency-review.yaml` rejects newly introduced dependencies with known high or critical vulnerabilities when GitHub can derive the dependency change for both revisions. This gives pre-merge enforcement for the repository's npm lockfile and GitHub Actions dependencies. The review deliberately does not post a pull-request comment; its check summary contains the evidence without granting the workflow write access to pull requests.

Resolved sbt snapshots are intentionally not submitted from pull-request workflows: the submission API requires `contents: write`, and generating a snapshot executes the pull request's sbt build definition. Combining those in one job would expose a privileged token to pull-request code. As a result, sbt graph coverage is post-merge: the `main` snapshot feeds alerts and security updates, while a future pre-merge design would need to separate unprivileged snapshot generation from a validated privileged submission step.

The sbt submission runs in a dedicated job and shuts down any existing sbt server before invoking the submission action. Under sbt 2, a server started by an earlier command can retain an environment that predates the action's GitHub token.

---

## Why Not Scala Steward

Scala Steward is a common alternative for keeping Scala/sbt dependencies current, and this repository briefly carried a `.scala-steward.conf` file. It was removed because:

- It was never actually wired up — no `scala-steward.org/action` workflow existed, so the config was dead weight.
- Dependabot's native `sbt` ecosystem support (added [2026-05-26](https://github.blog/changelog/2026-05-26-dependabot-version-updates-now-support-the-sbt-ecosystem/)) already covers the same ground: library deps, plugins, and the sbt version.
- Running both would risk duplicate PRs for the same bump.

If Scala Steward is ever reconsidered, retire the `sbt` entry in `dependabot.yaml` first to avoid double coverage.

---

## Handling Breaking Major Bumps

Some updates are correct in principle but too risky to land as an unattended PR — most notably a major sbt version bump (e.g. `1.x` → `2.x`), which can break the plugin ecosystem and requires a deliberate migration. These are excluded via an `ignore` rule on the relevant `dependabot.yaml` entry:

```yaml
ignore:
  - dependency-name: "org.scala-sbt:sbt"
    update-types: ["version-update:semver-major"]
```

When a new class of breaking major bump is identified, add a similar `ignore` entry rather than repeatedly closing the same automated PR.
