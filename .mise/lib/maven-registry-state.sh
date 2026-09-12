#!/usr/bin/env bash
# Decide, per published Maven coordinate, whether a release version must be published to a
# registry, is already complete there, or is in a partial state that must never be overwritten.
#
# The engine publishes two coordinates from one tag (ADR 009, #219):
#   com.fortemate:dicechess-rules_3   — rules core, no third-party dependencies
#   com.fortemate:dicechess-engine_3  — search, evaluators, extractors; depends on the rules jar
# Registry publication is not transactional, so a failed run can leave one coordinate complete and
# the other absent. This script checks the four files of every coordinate (POM, jar, sources,
# javadoc) and emits one decision per coordinate; the caller publishes exactly the absent ones.
#
# Usage:
#   maven-registry-state.sh <registry> <version> [<user> <token>]
#     registry: 'central' (https://repo1.maven.org/maven2) or 'github'
#               (https://maven.pkg.github.com/fortemate/dicechess-engine, needs user + token)
# Output (stdout, one per line, suitable for $GITHUB_OUTPUT):
#   publish_rules=true|false
#   publish_engine=true|false
#   publish_any=true|false
#   publish_projects=<space-separated sbt project ids to publish, e.g. "rulesJVM rootJVM">
# Exit 1 on a partial coordinate or an unexpected HTTP status.
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <central|github> <version> [<user> <token>]" >&2
  exit 2
fi
REGISTRY=$1
VERSION=$2
USER=${3:-}
TOKEN=${4:-}

case "$REGISTRY" in
  central) BASE="https://repo1.maven.org/maven2/com/fortemate"; AUTH=() ;;
  github)
    BASE="https://maven.pkg.github.com/fortemate/dicechess-engine/com/fortemate"
    if [[ -z "$USER" || -z "$TOKEN" ]]; then
      echo "error: the github registry needs <user> and <token>" >&2
      exit 2
    fi
    AUTH=(--user "$USER:$TOKEN")
    ;;
  *) echo "error: unknown registry '$REGISTRY' (expected central|github)" >&2; exit 2 ;;
esac

SUFFIXES=(.pom .jar -sources.jar -javadoc.jar)
declare -A PROJECT_OF=([dicechess-rules_3]=rulesJVM [dicechess-engine_3]=rootJVM)
publish_projects=()
any=false

for ARTIFACT in dicechess-rules_3 dicechess-engine_3; do
  present=0
  missing=0
  for SUFFIX in "${SUFFIXES[@]}"; do
    URL="$BASE/$ARTIFACT/$VERSION/$ARTIFACT-$VERSION$SUFFIX"
    STATUS=$(curl --silent --show-error --location "${AUTH[@]}" --output /dev/null --write-out '%{http_code}' --retry 3 "$URL")
    case "$STATUS" in
      200) present=$((present + 1)) ;;
      404) missing=$((missing + 1)) ;;
      *) echo "::error::$REGISTRY returned HTTP $STATUS for $URL" >&2; exit 1 ;;
    esac
  done
  key=${ARTIFACT%_3}; key=${key#dicechess-}
  if [[ "$present" -eq "${#SUFFIXES[@]}" ]]; then
    echo "publish_$key=false"
    echo "$REGISTRY: $ARTIFACT $VERSION is complete; skipping" >&2
  elif [[ "$missing" -eq "${#SUFFIXES[@]}" ]]; then
    echo "publish_$key=true"
    publish_projects+=("${PROJECT_OF[$ARTIFACT]}")
    any=true
    echo "$REGISTRY: $ARTIFACT $VERSION is absent; publishing" >&2
  else
    echo "::error::$REGISTRY: $ARTIFACT $VERSION is partial ($present present, $missing missing); refusing to overwrite an immutable version" >&2
    exit 1
  fi
done

echo "publish_any=$any"
echo "publish_projects=${publish_projects[*]:-}"
