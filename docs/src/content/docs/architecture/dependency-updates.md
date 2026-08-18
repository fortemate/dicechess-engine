---
title: Dependency Updates
description: How Dependabot keeps sbt, npm, and GitHub Actions dependencies current in the Dice Chess Engine.
---

The Dice Chess Engine relies entirely on **Dependabot version updates** to keep its dependencies current. All configuration lives in [`.github/dependabot.yaml`](https://github.com/fortemate/dicechess-engine/blob/main/.github/dependabot.yaml).

---

## Covered Ecosystems

| Ecosystem | Directory | Schedule | Covers |
| :--- | :--- | :--- | :--- |
| `sbt` | `/` | Weekly, Friday | Library dependencies in `build.sbt`, sbt plugins in `project/plugins.sbt`, and the sbt version in `project/build.properties` |
| `github-actions` | `/` | Weekly, Tuesday | Action versions pinned in `.github/workflows/**` |
| `npm` | `/docs` | Weekly, Wednesday | The Starlight/Astro documentation site's JavaScript dependencies |

Dependabot opened its first `sbt` ecosystem pull requests on 2026-07-05, confirming it bumps sbt plugins and the sbt version itself — not just `build.sbt` library coordinates.

---

## Why Not Scala Steward

Scala Steward is a common alternative for keeping Scala/sbt dependencies current, and this repository briefly carried a `.scala-steward.conf` file. It was removed (see [#439](https://github.com/fortemate/dicechess-engine/issues/439)) because:

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
