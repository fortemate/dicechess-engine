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
#     registry: 'central' (https://repo1.maven.org/maven2; <user> <token> = optional Central Portal
#               user token, used only to confirm an all-404 verdict, see below) or 'github'
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
  central)
    BASE="https://repo1.maven.org/maven2/com/fortemate"
    AUTH=()
    PORTAL_API="${CENTRAL_PORTAL_API:-https://central.sonatype.com/api/v1/publisher}"
    ;;
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

# Central only. repo1.maven.org lags the Central Portal after `sonaRelease` (minutes, up to about
# half an hour), so four 404s in a recovery run do not prove the version is unpublished, and a
# second deployment of an existing version would fail. With Portal credentials the authoritative
# published-status endpoint is asked first. Only a definite `"published": true` changes the verdict
# (to skip); no credentials, 401, 5xx or a network error keep the repo1 verdict, so this check can
# never block a release on its own.
portal_says_published() { # $1 artifact → 0 when the Portal reports <artifact> <version> as published
  [[ "$REGISTRY" == central && -n "$USER" && -n "$TOKEN" ]] || return 1
  local bearer body status
  bearer=$(printf '%s:%s' "$USER" "$TOKEN" | base64 | tr -d '\n')
  body=$(mktemp)
  status=$(curl --silent --location --header "Authorization: Bearer $bearer" --output "$body" \
    --write-out '%{http_code}' --retry 3 \
    "$PORTAL_API/published?namespace=com.fortemate&name=$1&version=$VERSION" || echo 000)
  if [[ "$status" == 200 ]] && grep -Eq '"published"[[:space:]]*:[[:space:]]*true' "$body"; then
    rm -f "$body"
    return 0
  fi
  if [[ "$status" == 200 ]]; then
    echo "$REGISTRY: the Central Portal reports $1 $VERSION as not published" >&2
  else
    echo "$REGISTRY: Central Portal published-status check for $1 $VERSION unavailable (HTTP $status); keeping the repo1 verdict" >&2
  fi
  rm -f "$body"
  return 1
}

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
    if portal_says_published "$ARTIFACT"; then
      echo "publish_$key=false"
      echo "$REGISTRY: $ARTIFACT $VERSION is published on the Central Portal and still synchronising to repo1.maven.org; skipping" >&2
    else
      echo "publish_$key=true"
      publish_projects+=("${PROJECT_OF[$ARTIFACT]}")
      any=true
      echo "$REGISTRY: $ARTIFACT $VERSION is absent; publishing" >&2
    fi
  else
    echo "::error::$REGISTRY: $ARTIFACT $VERSION is partial ($present present, $missing missing); refusing to overwrite an immutable version" >&2
    exit 1
  fi
done

echo "publish_any=$any"
echo "publish_projects=${publish_projects[*]:-}"
