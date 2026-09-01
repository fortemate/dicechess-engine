#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "usage: $0 <bundle-directory> [js-output-directory] [wasm-output-directory]" >&2
  exit 2
fi

BUNDLE_DIRECTORY=$1
JS_OUTPUT_DIRECTORY=${2:-dist}
WASM_OUTPUT_DIRECTORY=${3:-dist-wasm}
MANIFEST="$BUNDLE_DIRECTORY/manifest.json"

if [[ ! -f "$MANIFEST" ]]; then
  echo "error: npm release bundle manifest '$MANIFEST' does not exist" >&2
  exit 1
fi

extract_package() {
  local package_name=$1
  local output_directory=$2
  local filename
  filename=$(jq -er --arg name "$package_name" '.packages[] | select(.name == $name) | .filename' "$MANIFEST")

  if [[ -d "$output_directory" ]] && [[ -n "$(find "$output_directory" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "error: output directory '$output_directory' is not empty" >&2
    exit 1
  fi

  mkdir -p "$output_directory"
  tar -xzf "$BUNDLE_DIRECTORY/$filename" -C "$output_directory" --strip-components=1

  local actual_name
  actual_name=$(jq -er '.name' "$output_directory/package.json")
  if [[ "$actual_name" != "$package_name" ]]; then
    echo "error: extracted '$actual_name' into '$output_directory', expected '$package_name'" >&2
    exit 1
  fi
}

extract_package '@fortemate/dicechess-engine' "$JS_OUTPUT_DIRECTORY"
extract_package '@fortemate/dicechess-engine-wasm' "$WASM_OUTPUT_DIRECTORY"
