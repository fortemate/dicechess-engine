#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 2 || $# -gt 4 ]]; then
  echo "usage: $0 <bundle-directory> <registry-url> [--provenance] [--verify-only]" >&2
  exit 2
fi

BUNDLE_DIRECTORY=$1
REGISTRY_URL=$2
shift 2
MANIFEST="$BUNDLE_DIRECTORY/manifest.json"

if [[ ! -f "$MANIFEST" ]]; then
  echo "error: npm release bundle manifest '$MANIFEST' does not exist" >&2
  exit 1
fi

RELEASE_TAG=$(jq -er '.release.tag' "$MANIFEST")
COMMIT_SHA=$(jq -er '.release.sha' "$MANIFEST")
bash "$(dirname "$0")/verify-npm-release-bundle.sh" "$BUNDLE_DIRECTORY" "$RELEASE_TAG" "$COMMIT_SHA"

while IFS=$'\t' read -r package_name filename integrity; do
  echo "Reconciling $package_name with $REGISTRY_URL"
  bash "$(dirname "$0")/npm-publish-if-missing.sh" \
    "$BUNDLE_DIRECTORY/$filename" \
    "$REGISTRY_URL" \
    --expected-integrity "$integrity" \
    "$@"
done < <(jq -r '.packages[] | [.name, .filename, .integrity] | @tsv' "$MANIFEST")
