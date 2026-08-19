#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <repository> <release-tag> <commit-sha>" >&2
  exit 2
fi

REPOSITORY=$1
RELEASE_TAG=$2
COMMIT_SHA=$3

if [[ ! "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: '$RELEASE_TAG' is not an exact vX.Y.Z release tag" >&2
  exit 2
fi

if [[ ! "$COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "error: '$COMMIT_SHA' is not a full commit SHA" >&2
  exit 2
fi

DISPATCH_RESPONSE=$(jq -n \
  --arg ref main \
  --arg tag "$RELEASE_TAG" \
  --arg sha "$COMMIT_SHA" \
  '{ref: $ref, inputs: {tag: $tag, sha: $sha}, return_run_details: true}' | \
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
