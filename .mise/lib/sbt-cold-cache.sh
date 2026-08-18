#!/usr/bin/env bash
# Run sbt against a throwaway, repo-local build cache instead of the global one.
#
# Required for anything that measures coverage (#531). Coverage on Scala 3 is the
# compiler's own `-coverage-out:<dir>`, and the compiler is what creates that directory
# and writes the `scoverage.coverage` instrumentation metadata. If sbt 2's global build
# cache serves `compile`, the compiler never runs: nothing is instrumented, and
# `coverageReport` merely warns "No coverage data, skipping reports" — so
# `coverageFailOnMinimum` cannot fail and the 85% gate passes having measured nothing.
# `clean` does not help; the global cache lives outside `target/`.
#
# Every line below is load-bearing:
#   * `shutdown` — `-Dsbt.global.localcache` is read only when the sbt server starts, so
#     an already-running server silently ignores it and reuses the global cache.
#   * `rm -rf`   — guarantees a cold start even if a previous run left the directory.
#   * the cache lives under `target/`, so it is gitignored and `clean` disposes of it.
#
# Do NOT "simplify" this to `set Global / cacheStores := Nil`: that is the
# obvious-looking fix and it does change the setting's value, but the cache is not read
# from it — verified ineffective, the compile is still served from cache.
#
# This lives in .mise/lib/ rather than .mise/tasks/ deliberately: it takes the sbt
# command chain as an argument, so it is a helper, not a task, and should not show up in
# `mise tasks`. It is shared by mise (`coverage`, `check`) and by the CI/release
# workflows, which invoke it with bash directly — the runner already has the JVM
# toolchain, so it does not install mise (same pattern as `bash .mise/tasks/package/prepare`).
#
# Usage: .mise/lib/sbt-cold-cache.sh '<semicolon-joined sbt command chain>'

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 '<sbt command chain>'" >&2
  exit 2
fi

# Resolve the repo root from this script's location so the caller's cwd does not matter.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

cache_dir="$repo_root/target/covcache"

sbt shutdown >/dev/null 2>&1 || true
rm -rf "$cache_dir"

exec sbt --server -Dsbt.global.localcache="$cache_dir" "$@"
