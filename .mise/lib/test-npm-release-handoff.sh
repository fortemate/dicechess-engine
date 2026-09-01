#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIRECTORY=$(cd "$(dirname "$0")" && pwd)
REPOSITORY_ROOT=$(cd "$SCRIPT_DIRECTORY/../.." && pwd)
TEMP_DIRECTORY=$(mktemp -d)
trap 'rm -rf -- "$TEMP_DIRECTORY"' EXIT
export NPM_CONFIG_CACHE="$TEMP_DIRECTORY/npm-cache"

JS_DIRECTORY="$TEMP_DIRECTORY/js"
WASM_DIRECTORY="$TEMP_DIRECTORY/wasm"
BUNDLE_DIRECTORY="$TEMP_DIRECTORY/bundle"
HANDOFF_DIRECTORY="$TEMP_DIRECTORY/handoff"
RELEASE_TAG=v9.9.9
COMMIT_SHA=1111111111111111111111111111111111111111
mkdir -p "$JS_DIRECTORY" "$WASM_DIRECTORY"

write_manifest() {
  local destination=$1
  local package_name=$2
  local main_file=$3
  jq -n \
    --arg name "$package_name" \
    --arg main "$main_file" \
    '{
      name: $name,
      version: "9.9.9",
      type: "module",
      main: $main,
      exports: {".": $main},
      repository: {url: "git+https://github.com/fortemate/dicechess-engine.git"},
      publishConfig: {access: "public"}
    }' >"$destination/package.json"
}

write_manifest "$JS_DIRECTORY" '@fortemate/dicechess-engine' './dicechess-engine.js'
printf '%s\n' '# fixture' >"$JS_DIRECTORY/README.md"
printf '%s\n' 'export declare const fixture: true;' >"$JS_DIRECTORY/dicechess-engine.d.ts"
printf '%s\n' 'export const fixture = true;' >"$JS_DIRECTORY/dicechess-engine.js"

write_manifest "$WASM_DIRECTORY" '@fortemate/dicechess-engine-wasm' './main.js'
printf '%s\n' '# fixture' >"$WASM_DIRECTORY/README.md"
printf '%s\n' 'export declare const fixture: true;' >"$WASM_DIRECTORY/dicechess-engine.d.ts"
printf '%s\n' 'export const fixture = true;' >"$WASM_DIRECTORY/main.js"
printf '%s\n' 'export const load = () => undefined;' >"$WASM_DIRECTORY/__loader.js"
printf 'fixture' >"$WASM_DIRECTORY/main.wasm"
printf '%s\n' '{}' >"$WASM_DIRECTORY/main.wasm.map"

(
  cd "$TEMP_DIRECTORY"
  bash "$SCRIPT_DIRECTORY/create-npm-release-bundle.sh" \
    bundle \
    "$RELEASE_TAG" \
    "$COMMIT_SHA" \
    js \
    wasm
)
bash "$REPOSITORY_ROOT/.mise/tasks/package/verify" --bundle "$BUNDLE_DIRECTORY"

MANIFEST_SHA512=$(node --input-type=module - "$BUNDLE_DIRECTORY/manifest.json" <<'NODE'
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

process.stdout.write(createHash('sha512').update(readFileSync(process.argv[2])).digest('hex'));
NODE
)

cp -R "$BUNDLE_DIRECTORY" "$HANDOFF_DIRECTORY"
bash "$SCRIPT_DIRECTORY/verify-npm-release-bundle.sh" \
  "$HANDOFF_DIRECTORY" \
  "$RELEASE_TAG" \
  "$COMMIT_SHA" \
  "$MANIFEST_SHA512"

bash "$SCRIPT_DIRECTORY/extract-npm-release-bundle.sh" \
  "$HANDOFF_DIRECTORY" \
  "$TEMP_DIRECTORY/recovered-js" \
  "$TEMP_DIRECTORY/recovered-wasm"
diff -r "$JS_DIRECTORY" "$TEMP_DIRECTORY/recovered-js"
diff -r "$WASM_DIRECTORY" "$TEMP_DIRECTORY/recovered-wasm"

TAMPERED_DIRECTORY="$TEMP_DIRECTORY/tampered"
cp -R "$HANDOFF_DIRECTORY" "$TAMPERED_DIRECTORY"
JS_TARBALL=$(jq -er '.packages[] | select(.name == "@fortemate/dicechess-engine") | .filename' "$TAMPERED_DIRECTORY/manifest.json")
printf 'tampered' >>"$TAMPERED_DIRECTORY/$JS_TARBALL"
if bash "$SCRIPT_DIRECTORY/verify-npm-release-bundle.sh" \
  "$TAMPERED_DIRECTORY" \
  "$RELEASE_TAG" \
  "$COMMIT_SHA" >"$TEMP_DIRECTORY/tamper.log" 2>&1; then
  echo "error: tampered npm tarball passed bundle verification" >&2
  exit 1
fi
grep -F 'npm tarball digest mismatch for @fortemate/dicechess-engine' "$TEMP_DIRECTORY/tamper.log" >/dev/null

FAKE_BIN="$TEMP_DIRECTORY/fake-bin"
FAKE_STATE_DIRECTORY="$TEMP_DIRECTORY/registry-state"
FAKE_PUBLISH_LOG="$TEMP_DIRECTORY/publish.log"
mkdir -p "$FAKE_BIN" "$FAKE_STATE_DIRECTORY"

REAL_NPM=$(command -v npm)
export REAL_NPM FAKE_STATE_DIRECTORY FAKE_PUBLISH_LOG
export FAKE_MANIFEST="$HANDOFF_DIRECTORY/manifest.json"
export FAKE_MISMATCH_PACKAGE=

cat >"$FAKE_BIN/npm" <<'FAKE_NPM'
#!/usr/bin/env bash
set -euo pipefail

if [[ $1 == view ]]; then
  package_spec=$2
  package_name=${package_spec%@*}
  state_file="$FAKE_STATE_DIRECTORY/${package_name//\//_}"
  if [[ ! -f "$state_file" ]]; then
    echo "npm error code E404" >&2
    exit 1
  fi
  if [[ " $* " == *" dist.integrity "* ]]; then
    if [[ "$package_name" == "${FAKE_MISMATCH_PACKAGE:-}" ]]; then
      printf '%s\n' 'sha512-bWlzbWF0Y2g='
    else
      jq -er --arg name "$package_name" '.packages[] | select(.name == $name) | .integrity' "$FAKE_MANIFEST"
    fi
  else
    jq -er --arg name "$package_name" '.packages[] | select(.name == $name) | .version' "$FAKE_MANIFEST"
  fi
  exit 0
fi

if [[ $1 == publish ]]; then
  package_source=$2
  package_name=$(tar -xOf "$package_source" package/package.json | jq -er '.name')
  printf '%s\n' "$package_name" >>"$FAKE_PUBLISH_LOG"
  : >"$FAKE_STATE_DIRECTORY/${package_name//\//_}"
  exit 0
fi

exec "$REAL_NPM" "$@"
FAKE_NPM
chmod +x "$FAKE_BIN/npm"

touch "$FAKE_STATE_DIRECTORY/@fortemate_dicechess-engine" "$FAKE_STATE_DIRECTORY/@fortemate_dicechess-engine-wasm"
PATH="$FAKE_BIN:$PATH" bash "$SCRIPT_DIRECTORY/publish-npm-release-bundle.sh" \
  "$HANDOFF_DIRECTORY" \
  https://npm.pkg.github.test \
  --verify-only

export FAKE_MISMATCH_PACKAGE='@fortemate/dicechess-engine'
if PATH="$FAKE_BIN:$PATH" bash "$SCRIPT_DIRECTORY/publish-npm-release-bundle.sh" \
  "$HANDOFF_DIRECTORY" \
  https://registry.npmjs.test >"$TEMP_DIRECTORY/registry-mismatch.log" 2>&1; then
  echo "error: mismatched first-registry digest did not fail closed" >&2
  exit 1
fi
grep -F 'registry digest mismatch for @fortemate/dicechess-engine@9.9.9' "$TEMP_DIRECTORY/registry-mismatch.log" >/dev/null
if [[ -f "$FAKE_PUBLISH_LOG" ]]; then
  echo "error: registry mismatch attempted publication to the second registry" >&2
  exit 1
fi

export FAKE_MISMATCH_PACKAGE=
rm -f -- "$FAKE_STATE_DIRECTORY/@fortemate_dicechess-engine" "$FAKE_STATE_DIRECTORY/@fortemate_dicechess-engine-wasm"
PATH="$FAKE_BIN:$PATH" bash "$SCRIPT_DIRECTORY/publish-npm-release-bundle.sh" \
  "$HANDOFF_DIRECTORY" \
  https://registry.npmjs.test
if [[ $(wc -l <"$FAKE_PUBLISH_LOG") -ne 2 ]]; then
  echo "error: fixture registry did not receive both npm tarballs" >&2
  exit 1
fi

echo "npm release artifact handoff contract passed"
