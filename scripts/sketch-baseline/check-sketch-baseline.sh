#!/usr/bin/env bash
# Metric-6 comparison (Task 6.2.5a): the committed regression baseline against the tree at HEAD.
# Usage: check-sketch-baseline.sh [--baseline <file>]
#
# 1. The baseline SHA (line 1) must be reachable and an ancestor of origin/main, else the run fails with
#    "baseline SHA not reachable: shallow clone or not on main" (CI checks out with fetch-depth: 0).
# 2. The baseline is re-derived AT THAT SHA (not at HEAD) and must equal the committed file, apart from change records.
# 3. Every listed test file must still exist unmodified since the SHA, and every golden fixture must still hash the same,
#    unless a line `change-record: <path> -- <reason>` in the baseline file names the path.
# 4. The current count of such files must be at least the baseline count.
# That the listed tests PASS is the job of the test run itself; this script proves they were not removed or edited.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"
here="scripts/sketch-baseline"
baseline="project_plans/excalidraw-sketch-engine/implementation/regression-baseline.txt"
while [ $# -gt 0 ]; do
  case "$1" in
    --baseline) baseline="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

sha=$(sed -n '1s/^baseline-sha: //p' "$baseline")
if ! printf '%s' "$sha" | grep -Eq '^[0-9a-f]{40}$'; then
  echo "line 1 of $baseline is not 'baseline-sha: <40-hex>'" >&2
  exit 1
fi
if ! git cat-file -e "${sha}^{commit}" 2>/dev/null || ! git merge-base --is-ancestor "$sha" origin/main 2>/dev/null; then
  echo "baseline SHA not reachable: shallow clone or not on main" >&2
  exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail=0
note() { echo "FAIL: $*" >&2; fail=1; }

"$here/record-sketch-baseline.sh" --sha "$sha" --out "$tmp/rederived.txt" 2>/dev/null
grep -v '^change-record: ' "$baseline" > "$tmp/committed.txt"
if ! diff -u "$tmp/committed.txt" "$tmp/rederived.txt" > "$tmp/diff.txt"; then
  cat "$tmp/diff.txt" >&2
  note "the committed baseline differs from the list re-derived at $sha"
fi

recorded() { grep -q "^change-record: $1 -- " "$baseline"; }

while IFS= read -r path; do
  if recorded "$path"; then echo "change record accepted for $path"; continue; fi
  if ! git cat-file -e "HEAD:$path" 2>/dev/null; then note "baseline test removed: $path"
  elif ! git diff --quiet "$sha" HEAD -- "$path"; then note "baseline test edited: $path"
  fi
done < <(sed -n '/^# sketch test files/,/^# sketch golden fixtures/{/^#/d;p}' "$baseline")

while IFS= read -r line; do
  want=${line%%  *}
  path=${line#*  }
  if recorded "$path"; then echo "change record accepted for $path"; continue; fi
  if ! got=$(git show "HEAD:$path" 2>/dev/null | sha256sum | cut -d' ' -f1); then note "golden fixture removed: $path"
  elif [ "$got" != "$want" ]; then note "golden fixture changed: $path"
  fi
done < <(sed -n '/^# sketch golden fixtures/,${/^#/d;p}' "$baseline")

"$here/record-sketch-baseline.sh" --sha HEAD --allow-unmerged --out "$tmp/head.txt" 2>/dev/null
for key in test-class-count golden-fixture-count; do
  was=$(sed -n "s/^$key: //p" "$baseline")
  now=$(sed -n "s/^$key: //p" "$tmp/head.txt")
  if [ "$now" -lt "$was" ]; then note "$key fell from $was to $now"; else echo "$key: baseline $was, now $now"; fi
done

if [ "$fail" -ne 0 ]; then exit 1; fi
echo "metric 6 baseline holds at $sha"
