#!/usr/bin/env bash
# dc-shared:jules-setup v3 — keep identical across Fortemate JVM repositories (source of truth:
# fortemate-internal/skills/jules-repo-readiness/templates/jules-setup.sh; change it there, bump the
# version, roll it out with the jules-repo-readiness skill).
#
# Google Jules "Initial Setup" script. The Jules VM is Ubuntu with OpenJDK 21, Maven and Gradle but
# no sbt, scalafmt, mise or brew. This script provisions the same toolchain humans get from mise.toml,
# then warms the build caches so `mise run format` and `mise run check` are cheap inside a task.
# Supports sbt builds (project/build.properties) and Maven builds (pom.xml).
#
# Jules app: repository → Configuration → Initial Setup → `bash scripts/jules-setup.sh` → Run and Snapshot.
# Re-run "Run and Snapshot" whenever this script, mise.toml, project/build.properties or pom.xml changes.
#
# Knobs (set as Jules environment variables or inline in the Initial Setup command):
#   JULES_SETUP_WARMUP  build command used to warm caches: sbt command (default Test/compile) or Maven
#                       goals (default "-DskipTests test-compile"); "none" skips the warm-up
#   MISE_GITHUB_TOKEN   read-only GitHub token if `mise install` hits API rate limits (HTTP 403)
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"
if [[ ! -f mise.toml ]]; then
  echo "error: run from the root of a repository with a mise.toml (cwd: $PWD)" >&2
  exit 1
fi
if [[ -f project/build.properties ]]; then
  BUILD=sbt
elif [[ -f pom.xml ]]; then
  BUILD=maven
else
  echo "error: neither project/build.properties (sbt) nor pom.xml (Maven) found in $PWD" >&2
  exit 1
fi

export MISE_YES=1 # never prompt: the VM is non-interactive
MISE_BIN="$HOME/.local/bin/mise"
SHIMS_DIR="$HOME/.local/share/mise/shims"
export PATH="$HOME/.local/bin:$SHIMS_DIR:$PATH"
if [[ -z "${MISE_GITHUB_TOKEN:-}" && -n "${GITHUB_TOKEN:-}" ]]; then
  export MISE_GITHUB_TOKEN="$GITHUB_TOKEN"
fi

log() { printf '\n==> %s\n' "$*"; }

log "Installing mise"
if [[ ! -x "$MISE_BIN" ]]; then
  # --proto '=https' --tlsv1.2: -L follows redirects, so pin the scheme (SonarCloud shell:S6506)
  curl --proto '=https' --tlsv1.2 -fsSL https://mise.run | sh
fi
"$MISE_BIN" --version
"$MISE_BIN" trust "$REPO_ROOT/mise.toml"

log "Installing the tools pinned in mise.toml (Java, build tool, formatter, hooks tooling, ...)"
"$MISE_BIN" install

if [[ "$BUILD" == sbt ]]; then
  log "Ensuring the sbt runner is available"
  SBT_VERSION="$(sed -n 's/^sbt.version=//p' project/build.properties | tr -d '[:space:]')"
  if ! "$MISE_BIN" which sbt >/dev/null 2>&1; then
    # Repositories that do not pin sbt in mise.toml yet: install the runner matching the build.
    "$MISE_BIN" use --global "sbt@${SBT_VERSION}"
  fi
else
  log "Ensuring Maven is available"
  if ! "$MISE_BIN" which mvn >/dev/null 2>&1; then
    "$MISE_BIN" use --global maven@latest
  fi
fi

log "Persisting PATH for the shells Jules opens later"
# shellcheck disable=SC2016 # the line must stay literal so each shell expands it
PATH_LINE='export PATH="$HOME/.local/bin:$HOME/.local/share/mise/shims:$PATH"'
for profile in "$HOME/.bashrc" "$HOME/.profile"; do
  touch "$profile"
  grep -qxF "$PATH_LINE" "$profile" || printf '%s\n' "$PATH_LINE" >>"$profile"
done

log "Toolchain versions"
"$MISE_BIN" exec -- java -version
if [[ "$BUILD" == sbt ]]; then
  if "$MISE_BIN" which scalafmt >/dev/null 2>&1; then "$MISE_BIN" exec -- scalafmt --version; fi
  "$MISE_BIN" exec -- sbt --version # first boot downloads the sbt launcher and plugins
else
  "$MISE_BIN" exec -- mvn --version
fi

if [[ "$BUILD" == sbt ]]; then
  WARMUP="${JULES_SETUP_WARMUP:-Test/compile}"
  if [[ "$WARMUP" != "none" ]]; then
    log "Warming the sbt and coursier caches with: sbt $WARMUP"
    "$MISE_BIN" exec -- sbt "$WARMUP"
  fi
else
  WARMUP="${JULES_SETUP_WARMUP:--DskipTests test-compile}"
  if [[ "$WARMUP" != "none" ]]; then
    log "Warming the Maven repository with: mvn --batch-mode --no-transfer-progress $WARMUP"
    # shellcheck disable=SC2086 # WARMUP holds several Maven goals/flags on purpose
    "$MISE_BIN" exec -- mvn --batch-mode --no-transfer-progress $WARMUP
  fi
fi

log "Optional capabilities"
if docker info >/dev/null 2>&1; then
  echo "docker: available — Testcontainers-based suites can run in this environment"
else
  echo "docker: unavailable — Testcontainers-based suites cannot run here; the gate falls back to compile + non-Docker suites"
fi

log "Jules environment ready"
