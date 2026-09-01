#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <package-directory-or-tarball> <registry-url> [--provenance] [--verify-only] [--expected-integrity <sha512-sri>]" >&2
  exit 2
fi

PACKAGE_SOURCE=$1
REGISTRY_URL=$2
shift 2
PROVENANCE=false
VERIFY_ONLY=false
EXPECTED_INTEGRITY=

while [[ $# -gt 0 ]]; do
  case "$1" in
    --provenance)
      PROVENANCE=true
      shift
      ;;
    --verify-only)
      VERIFY_ONLY=true
      shift
      ;;
    --expected-integrity)
      if [[ $# -lt 2 ]]; then
        echo "error: --expected-integrity requires a value" >&2
        exit 2
      fi
      EXPECTED_INTEGRITY=$2
      shift 2
      ;;
    *)
      echo "error: unsupported publish option '$1'" >&2
      exit 2
      ;;
  esac
done

if [[ "$PROVENANCE" == true && "$VERIFY_ONLY" == true ]]; then
  echo "error: --provenance and --verify-only cannot be used together" >&2
  exit 2
fi

if [[ -n "$EXPECTED_INTEGRITY" && ! "$EXPECTED_INTEGRITY" =~ ^sha512-[A-Za-z0-9+/]+={0,2}$ ]]; then
  echo "error: expected integrity '$EXPECTED_INTEGRITY' is not a SHA-512 SRI value" >&2
  exit 2
fi

if [[ -d "$PACKAGE_SOURCE" ]]; then
  PACKAGE_MANIFEST="$PACKAGE_SOURCE/package.json"
elif [[ -f "$PACKAGE_SOURCE" && "$PACKAGE_SOURCE" =~ \.tgz$ ]]; then
  PACKAGE_MANIFEST=$(mktemp)
  tar -xOf "$PACKAGE_SOURCE" package/package.json >"$PACKAGE_MANIFEST"
else
  echo "error: package source '$PACKAGE_SOURCE' is neither a directory nor an npm tarball" >&2
  exit 2
fi

PACKAGE_NAME=$(jq -er '.name' "$PACKAGE_MANIFEST")
PACKAGE_VERSION=$(jq -er '.version' "$PACKAGE_MANIFEST")
PACKAGE_SPEC="$PACKAGE_NAME@$PACKAGE_VERSION"
VIEW_ERROR=$(mktemp)
trap 'rm -f -- "$VIEW_ERROR"; if [[ -n "${PACKAGE_MANIFEST:-}" && "$PACKAGE_MANIFEST" != "$PACKAGE_SOURCE/package.json" ]]; then rm -f -- "$PACKAGE_MANIFEST"; fi' EXIT

verify_integrity() {
  local published_integrity

  if [[ -z "$EXPECTED_INTEGRITY" ]]; then
    return 0
  fi

  : >"$VIEW_ERROR"
  if ! published_integrity=$(npm view "$PACKAGE_SPEC" dist.integrity --registry="$REGISTRY_URL" 2>"$VIEW_ERROR"); then
    echo "error: could not read the published integrity for $PACKAGE_SPEC from $REGISTRY_URL" >&2
    sed 's/^/  /' "$VIEW_ERROR" >&2
    return 1
  fi
  if [[ "$published_integrity" != "$EXPECTED_INTEGRITY" ]]; then
    echo "error: registry digest mismatch for $PACKAGE_SPEC in $REGISTRY_URL" >&2
    echo "  expected: $EXPECTED_INTEGRITY" >&2
    echo "  actual:   $published_integrity" >&2
    return 1
  fi
  echo "Verified $PACKAGE_SPEC in $REGISTRY_URL: $published_integrity"
}

if PUBLISHED_VERSION=$(npm view "$PACKAGE_SPEC" version --registry="$REGISTRY_URL" 2>"$VIEW_ERROR"); then
  if [[ "$PUBLISHED_VERSION" != "$PACKAGE_VERSION" ]]; then
    echo "error: $REGISTRY_URL returned unexpected version '$PUBLISHED_VERSION' for $PACKAGE_SPEC" >&2
    exit 1
  fi
  verify_integrity
  echo "$PACKAGE_SPEC is already published to $REGISTRY_URL; skipping publication"
  exit 0
fi

if ! grep -Eq 'E404|404 Not Found' "$VIEW_ERROR"; then
  echo "error: could not determine whether $PACKAGE_SPEC exists in $REGISTRY_URL" >&2
  sed 's/^/  /' "$VIEW_ERROR" >&2
  exit 1
fi

if [[ "$VERIFY_ONLY" == true ]]; then
  echo "error: $PACKAGE_SPEC is absent from $REGISTRY_URL; verification requires an existing package" >&2
  exit 1
fi

echo "$PACKAGE_SPEC is absent from $REGISTRY_URL; publishing"
if [[ "$PROVENANCE" == true ]]; then
  if npm publish "$PACKAGE_SOURCE" --registry="$REGISTRY_URL" --access=public --provenance; then
    verify_integrity
    exit 0
  else
    PUBLISH_STATUS=$?
  fi
else
  if npm publish "$PACKAGE_SOURCE" --registry="$REGISTRY_URL" --access=public; then
    verify_integrity
    exit 0
  else
    PUBLISH_STATUS=$?
  fi
fi

# Another run can win the race between the absence check and publication. Treat that conflict as
# success only when the exact version is now visible; preserve the original publish failure otherwise.
if PUBLISHED_VERSION=$(npm view "$PACKAGE_SPEC" version --registry="$REGISTRY_URL" 2>"$VIEW_ERROR"); then
  if [[ "$PUBLISHED_VERSION" == "$PACKAGE_VERSION" ]]; then
    verify_integrity
    echo "$PACKAGE_SPEC was published concurrently to $REGISTRY_URL; continuing"
    exit 0
  fi
fi

echo "error: npm publish failed for $PACKAGE_SPEC in $REGISTRY_URL" >&2
exit "$PUBLISH_STATUS"
