#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: $0 <package-directory> <registry-url> [--provenance]" >&2
  exit 2
fi

PACKAGE_DIRECTORY=$1
REGISTRY_URL=$2
PROVENANCE_FLAG=${3:-}

if [[ "$PROVENANCE_FLAG" != "" && "$PROVENANCE_FLAG" != "--provenance" ]]; then
  echo "error: unsupported publish option '$PROVENANCE_FLAG'" >&2
  exit 2
fi

PACKAGE_NAME=$(jq -er '.name' "$PACKAGE_DIRECTORY/package.json")
PACKAGE_VERSION=$(jq -er '.version' "$PACKAGE_DIRECTORY/package.json")
PACKAGE_SPEC="$PACKAGE_NAME@$PACKAGE_VERSION"
VIEW_ERROR=$(mktemp)
trap 'rm -f -- "$VIEW_ERROR"' EXIT

if PUBLISHED_VERSION=$(npm view "$PACKAGE_SPEC" version --registry="$REGISTRY_URL" 2>"$VIEW_ERROR"); then
  if [[ "$PUBLISHED_VERSION" != "$PACKAGE_VERSION" ]]; then
    echo "error: $REGISTRY_URL returned unexpected version '$PUBLISHED_VERSION' for $PACKAGE_SPEC" >&2
    exit 1
  fi
  echo "$PACKAGE_SPEC is already published to $REGISTRY_URL; skipping"
  exit 0
fi

if ! grep -Eq 'E404|404 Not Found' "$VIEW_ERROR"; then
  echo "error: could not determine whether $PACKAGE_SPEC exists in $REGISTRY_URL" >&2
  sed 's/^/  /' "$VIEW_ERROR" >&2
  exit 1
fi

echo "$PACKAGE_SPEC is absent from $REGISTRY_URL; publishing"
if [[ -n "$PROVENANCE_FLAG" ]]; then
  if npm publish "$PACKAGE_DIRECTORY" --registry="$REGISTRY_URL" --access=public --provenance; then
    exit 0
  else
    PUBLISH_STATUS=$?
  fi
else
  if npm publish "$PACKAGE_DIRECTORY" --registry="$REGISTRY_URL" --access=public; then
    exit 0
  else
    PUBLISH_STATUS=$?
  fi
fi

# Another run can win the race between the absence check and publication. Treat that conflict as
# success only when the exact version is now visible; preserve the original publish failure otherwise.
if PUBLISHED_VERSION=$(npm view "$PACKAGE_SPEC" version --registry="$REGISTRY_URL" 2>"$VIEW_ERROR"); then
  if [[ "$PUBLISHED_VERSION" == "$PACKAGE_VERSION" ]]; then
    echo "$PACKAGE_SPEC was published concurrently to $REGISTRY_URL; continuing"
    exit 0
  fi
fi

echo "error: npm publish failed for $PACKAGE_SPEC in $REGISTRY_URL" >&2
exit "$PUBLISH_STATUS"
