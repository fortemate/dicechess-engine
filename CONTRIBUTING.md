# Contributing

## Contributor License Agreement

Before your first pull request can be accepted, you must sign the project's
[Contributor License Agreement](CLA.md). Signing is self-service: append yourself to the `signatures`
array in [`.github/cla-signatures.json`](.github/cla-signatures.json) in the same pull request
(see [CLA.md](CLA.md), "How to Sign"). The `CI: CLA` status check fails until the
entry is present. Pull requests from repository owners, organization members,
explicit collaborators, and bot accounts are exempt.

Why a CLA: the project follows an open-core model. The public repositories are
AGPL-3.0, and the project owner retains the ability to combine the code with
closed-source modules and to offer it under additional licenses. The CLA preserves
that option while your contribution always remains available under AGPL-3.0 — and
you keep the copyright to your work. A plain DCO (`Signed-off-by`) would not grant
relicensing rights, which is why a CLA is used instead.

## Development Workflow

**Workflow order for issue-driven work: Issue → Branch → Implementation → PR.** Routine work (`refactor`, `chore`, `docs`, `ci`, `test`, `perf`) may skip the issue.

- For issue-driven work, create an issue (Context, Objective, Definition of Done) before implementation.
- Create a branch: `<type>/<short-desc>`, optionally `<type>/<id>-<short-desc>` to link an issue. Types: `task` / `feat` / `bug` (issue-driven), `refactor` / `chore` / `docs` / `ci` / `test` / `perf` (issueless).
- Implement changes on that branch only. Do not work directly on `main`.
- Run `mise run check` to validate formatting, compilation, and tests before opening a PR.
- When the branch references an issue, include `Closes #<issue-number>` in the PR body and a checklist matching the issue DoD.

See `AGENTS.md` for branch-name patterns and agent-specific guidance.

## Release Pipeline

### Local Release Preparation

We use a hybrid approach for releases:
1. **Local:** Mise task bumps version and creates git tag
2. **Automated:** GitHub Actions publishes to npm on new tags

### Creating a Release

#### 1. Show Release Help (Optional)
```bash
mise run release:help
```

#### 2. Prepare Release Locally
```bash
# From your task branch (after Issue & implementation)
mise run release:prepare [patch|minor|major]

# Examples:
mise run release:prepare patch  # 0.1.0 → 0.1.1
mise run release:prepare minor  # 0.1.0 → 0.2.0
mise run release:prepare major  # 0.1.0 → 1.0.0
```

The task will:
- ✅ Validate the bump type
- ✅ Calculate the new semantic version
- ✅ Show you a summary and ask for confirmation
- ✅ Update `build.sbt` (removes `-SNAPSHOT` suffix)
- ✅ Update `package.json`
- ✅ Create a git commit with release notes
- ✅ Create a git tag (e.g., `v0.1.1`)

#### 3. Verify & Review
```bash
# Check the commit
git log -1 --oneline
git show --stat

# List all tags
git tag -ln
```

#### 4. Push to GitHub
```bash
git push origin                 # Push commit
git push origin --tags          # Push tags
```

#### 5. Create Pull Request
- Link the PR to your original Issue
- Reference the release commit: `Closes #XX`
- Wait for CI checks to pass

#### 6. Auto-Publish
When the version tag is pushed, the GitHub Actions `publish.yaml` workflow triggers
automatically. It runs sbt directly (no mise on the runner — see the
[Releases](docs/src/content/docs/architecture/releases.md) doc):
- Validation: `sbt scalafmtCheckAll 'scalafixAll --check' clean coverage test coverageReport`
- Publishes the JVM artifact to GitHub Packages (Maven)
- Builds the npm packages (`@fortemate/dicechess-engine`, `@fortemate/dicechess-engine-wasm`) and publishes them
- Creates a GitHub Release with auto-generated release notes

### Workflow Diagram
```
Issue #XX
    ↓
Branch: task/XX-release-version
    ↓
Implementation (if version-related changes)
    ↓
mise run release:prepare [type]  ← Creates commit + tag locally
    ↓
git push + git push --tags        ← Push to GitHub
    ↓
PR → Review & Merge
    ↓
GitHub Actions publish.yaml      ← Auto-publishes to GitHub Packages
    ↓
Release published! 🎉
```
