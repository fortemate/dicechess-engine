#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 3 || $# -gt 5 ]]; then
  echo "usage: $0 <output-directory> <release-tag> <commit-sha> [js-package-directory] [wasm-package-directory]" >&2
  exit 2
fi

OUTPUT_DIRECTORY=$1
RELEASE_TAG=$2
COMMIT_SHA=$3
JS_PACKAGE_DIRECTORY=${4:-dist}
WASM_PACKAGE_DIRECTORY=${5:-dist-wasm}

if [[ ! "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: '$RELEASE_TAG' is not an exact vX.Y.Z release tag" >&2
  exit 2
fi

if [[ ! "$COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "error: '$COMMIT_SHA' is not a full commit SHA" >&2
  exit 2
fi

if [[ -d "$OUTPUT_DIRECTORY" ]] && [[ -n "$(find "$OUTPUT_DIRECTORY" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  echo "error: output directory '$OUTPUT_DIRECTORY' is not empty" >&2
  exit 2
fi

mkdir -p "$OUTPUT_DIRECTORY"
OUTPUT_DIRECTORY=$(cd "$OUTPUT_DIRECTORY" && pwd)
TEMP_DIRECTORY=$(mktemp -d)
trap 'rm -rf -- "$TEMP_DIRECTORY"' EXIT
PACKAGES_JSON="$TEMP_DIRECTORY/packages.jsonl"
RELEASE_VERSION=${RELEASE_TAG#v}

hash_tarball() {
  node --input-type=module - "$1" <<'NODE'
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const tarball = readFileSync(process.argv[2]);
const digest = createHash('sha512').update(tarball).digest();
process.stdout.write(JSON.stringify({
  sha512: digest.toString('hex'),
  integrity: `sha512-${digest.toString('base64')}`,
  size: tarball.length,
}));
NODE
}

pack_package() {
  local package_directory=$1
  local expected_name=$2
  local package_name
  local package_version
  local pack_json="$TEMP_DIRECTORY/pack-${expected_name##*/}.json"

  if [[ ! -d "$package_directory" ]]; then
    echo "error: package directory '$package_directory' does not exist" >&2
    exit 1
  fi
  package_directory=$(cd "$package_directory" && pwd)

  package_name=$(jq -er '.name' "$package_directory/package.json")
  package_version=$(jq -er '.version' "$package_directory/package.json")
  if [[ "$package_name" != "$expected_name" ]]; then
    echo "error: package directory '$package_directory' contains '$package_name', expected '$expected_name'" >&2
    exit 1
  fi
  if [[ "$package_version" != "$RELEASE_VERSION" ]]; then
    echo "error: $package_name has version '$package_version', expected '$RELEASE_VERSION' from $RELEASE_TAG" >&2
    exit 1
  fi

  npm pack --json --pack-destination "$OUTPUT_DIRECTORY" "$package_directory" >"$pack_json"

  local pack_count
  local filename
  local npm_integrity
  local tarball
  local hash_json
  local sha512
  local integrity
  local size
  pack_count=$(jq -er 'length' "$pack_json")
  if [[ "$pack_count" -ne 1 ]]; then
    echo "error: npm pack produced $pack_count results for $package_name, expected exactly one" >&2
    exit 1
  fi
  filename=$(jq -er '.[0].filename' "$pack_json")
  npm_integrity=$(jq -er '.[0].integrity' "$pack_json")
  if [[ "$filename" == */* || ! "$filename" =~ \.tgz$ ]]; then
    echo "error: npm pack returned unsafe tarball filename '$filename' for $package_name" >&2
    exit 1
  fi

  tarball="$OUTPUT_DIRECTORY/$filename"
  hash_json=$(hash_tarball "$tarball")
  sha512=$(jq -er '.sha512' <<<"$hash_json")
  integrity=$(jq -er '.integrity' <<<"$hash_json")
  size=$(jq -er '.size' <<<"$hash_json")
  if [[ "$npm_integrity" != "$integrity" ]]; then
    echo "error: npm pack integrity mismatch for $package_name: npm reported '$npm_integrity', computed '$integrity'" >&2
    exit 1
  fi

  jq -cn \
    --arg name "$package_name" \
    --arg version "$package_version" \
    --arg filename "$filename" \
    --arg sha512 "$sha512" \
    --arg integrity "$integrity" \
    --argjson size "$size" \
    '{name: $name, version: $version, filename: $filename, size: $size, sha512: $sha512, integrity: $integrity}' \
    >>"$PACKAGES_JSON"
}

pack_package "$JS_PACKAGE_DIRECTORY" '@fortemate/dicechess-engine'
pack_package "$WASM_PACKAGE_DIRECTORY" '@fortemate/dicechess-engine-wasm'

jq -s \
  --arg tag "$RELEASE_TAG" \
  --arg sha "$COMMIT_SHA" \
  '{schemaVersion: 1, release: {tag: $tag, sha: $sha}, packages: .}' \
  "$PACKAGES_JSON" >"$OUTPUT_DIRECTORY/manifest.json"

echo "Created npm release bundle for $RELEASE_TAG at $COMMIT_SHA:"
jq -r '.packages[] | "  \(.name) \(.integrity) \(.filename)"' "$OUTPUT_DIRECTORY/manifest.json"
