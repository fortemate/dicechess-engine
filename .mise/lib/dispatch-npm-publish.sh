#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 6 ]]; then
  echo "usage: $0 <repository> <release-tag> <commit-sha> <source-run-id> <artifact-name> <manifest-sha512>" >&2
  exit 2
fi

REPOSITORY=$1
RELEASE_TAG=$2
COMMIT_SHA=$3
SOURCE_RUN_ID=$4
ARTIFACT_NAME=$5
MANIFEST_SHA512=$6

if [[ ! "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: '$RELEASE_TAG' is not an exact vX.Y.Z release tag" >&2
  exit 2
fi

if [[ ! "$COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "error: '$COMMIT_SHA' is not a full commit SHA" >&2
  exit 2
fi

if [[ ! "$SOURCE_RUN_ID" =~ ^[0-9]+$ ]]; then
  echo "error: '$SOURCE_RUN_ID' is not a GitHub Actions run ID" >&2
  exit 2
fi

if [[ ! "$ARTIFACT_NAME" =~ ^npm-release-${SOURCE_RUN_ID}-[0-9]+$ ]]; then
  echo "error: artifact name '$ARTIFACT_NAME' does not belong to source run $SOURCE_RUN_ID" >&2
  exit 2
fi

if [[ ! "$MANIFEST_SHA512" =~ ^[0-9a-f]{128}$ ]]; then
  echo "error: manifest SHA-512 is not 128 lowercase hexadecimal characters" >&2
  exit 2
fi

DISPATCH_RESPONSE=$(jq -n \
  --arg ref main \
  --arg tag "$RELEASE_TAG" \
  --arg sha "$COMMIT_SHA" \
  --arg source_run_id "$SOURCE_RUN_ID" \
  --arg artifact_name "$ARTIFACT_NAME" \
  --arg manifest_sha512 "$MANIFEST_SHA512" \
  '{
    ref: $ref,
    inputs: {
      tag: $tag,
      sha: $sha,
      source_run_id: $source_run_id,
      artifact_name: $artifact_name,
      manifest_sha512: $manifest_sha512
    },
    return_run_details: true
  }' | \
  gh api \
    --method POST \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2026-03-10' \
    "repos/$REPOSITORY/actions/workflows/npm-publish.yaml/dispatches" \
    --input -)

RUN_ID=$(jq -er '.workflow_run_id' <<<"$DISPATCH_RESPONSE")
RUN_URL=$(jq -er '.html_url' <<<"$DISPATCH_RESPONSE")
echo "Canonical npm publication: $RUN_URL"
gh run watch "$RUN_ID" --repo "$REPOSITORY" --exit-status
