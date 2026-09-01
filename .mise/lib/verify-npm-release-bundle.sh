#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo "usage: $0 <bundle-directory> <release-tag> <commit-sha> [manifest-sha512]" >&2
  exit 2
fi

BUNDLE_DIRECTORY=$1
RELEASE_TAG=$2
COMMIT_SHA=$3
EXPECTED_MANIFEST_SHA512=${4:-}
MANIFEST="$BUNDLE_DIRECTORY/manifest.json"

if [[ ! "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: '$RELEASE_TAG' is not an exact vX.Y.Z release tag" >&2
  exit 2
fi

if [[ ! "$COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "error: '$COMMIT_SHA' is not a full commit SHA" >&2
  exit 2
fi

if [[ -n "$EXPECTED_MANIFEST_SHA512" && ! "$EXPECTED_MANIFEST_SHA512" =~ ^[0-9a-f]{128}$ ]]; then
  echo "error: expected manifest SHA-512 is not 128 lowercase hexadecimal characters" >&2
  exit 2
fi

if [[ ! -f "$MANIFEST" ]]; then
  echo "error: npm release bundle manifest '$MANIFEST' does not exist" >&2
  exit 1
fi

hash_file() {
  node --input-type=module - "$1" <<'NODE'
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const contents = readFileSync(process.argv[2]);
const digest = createHash('sha512').update(contents).digest();
process.stdout.write(JSON.stringify({
  sha512: digest.toString('hex'),
  integrity: `sha512-${digest.toString('base64')}`,
  size: contents.length,
}));
NODE
}

ACTUAL_MANIFEST_SHA512=$(hash_file "$MANIFEST" | jq -er '.sha512')
if [[ -n "$EXPECTED_MANIFEST_SHA512" && "$ACTUAL_MANIFEST_SHA512" != "$EXPECTED_MANIFEST_SHA512" ]]; then
  echo "error: npm release manifest digest mismatch: expected '$EXPECTED_MANIFEST_SHA512', got '$ACTUAL_MANIFEST_SHA512'" >&2
  exit 1
fi

RELEASE_VERSION=${RELEASE_TAG#v}
if ! jq -e \
  --arg tag "$RELEASE_TAG" \
  --arg sha "$COMMIT_SHA" \
  --arg version "$RELEASE_VERSION" \
  '.schemaVersion == 1 and
   .release.tag == $tag and
   .release.sha == $sha and
   (.packages | length) == 2 and
   ([.packages[].name] | sort) == ["@fortemate/dicechess-engine", "@fortemate/dicechess-engine-wasm"] and
   all(.packages[];
     .version == $version and
     (.filename | test("^[A-Za-z0-9._+-]+[.]tgz$")) and
     (.size | type == "number" and . > 0) and
     (.sha512 | test("^[0-9a-f]{128}$")) and
     (.integrity | test("^sha512-[A-Za-z0-9+/]+={0,2}$")))' \
  "$MANIFEST" >/dev/null; then
  echo "error: npm release bundle manifest does not match $RELEASE_TAG at $COMMIT_SHA" >&2
  exit 1
fi

while IFS=$'\t' read -r package_name filename expected_size expected_sha512 expected_integrity; do
  tarball="$BUNDLE_DIRECTORY/$filename"
  if [[ ! -f "$tarball" ]]; then
    echo "error: npm release tarball for $package_name is missing: $filename" >&2
    exit 1
  fi

  hash_json=$(hash_file "$tarball")
  actual_size=$(jq -er '.size' <<<"$hash_json")
  actual_sha512=$(jq -er '.sha512' <<<"$hash_json")
  actual_integrity=$(jq -er '.integrity' <<<"$hash_json")
  if [[ "$actual_size" != "$expected_size" || "$actual_sha512" != "$expected_sha512" || "$actual_integrity" != "$expected_integrity" ]]; then
    echo "error: npm tarball digest mismatch for $package_name" >&2
    echo "  expected: $expected_integrity ($expected_sha512, $expected_size bytes)" >&2
    echo "  actual:   $actual_integrity ($actual_sha512, $actual_size bytes)" >&2
    exit 1
  fi

  echo "Verified $package_name: $actual_integrity"
done < <(jq -r '.packages[] | [.name, .filename, (.size | tostring), .sha512, .integrity] | @tsv' "$MANIFEST")

echo "Verified npm release manifest SHA-512: $ACTUAL_MANIFEST_SHA512"
