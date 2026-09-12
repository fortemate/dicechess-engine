#!/usr/bin/env bash
# Every Scala source must start with the project's SPDX header (engine #223, ADR 009 in fortemate-internal).
set -euo pipefail
header='// SPDX-License-Identifier: AGPL-3.0-only'
missing=0
while IFS= read -r f; do
  if [ "$(head -n 1 "$f")" != "$header" ]; then echo "missing SPDX header: $f"; missing=$((missing+1)); fi
done < <(git ls-files '*.scala')
if [ "$missing" -gt 0 ]; then echo "$missing file(s) without '$header'"; exit 1; fi
echo "SPDX headers OK ($(git ls-files '*.scala' | wc -l | tr -d ' ') Scala files)"
