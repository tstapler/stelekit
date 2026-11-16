#!/usr/bin/env bash
# Records the metric-6 regression baseline for the excalidraw-sketch-engine project (Task 0.1.7d).
# Usage: record-sketch-baseline.sh [--sha <40-hex>] [--out <file>]
# Content comes only from `git ls-tree -r <sha>` and `git show <sha>:<path>`, never the working tree,
# so re-running with the recorded SHA reproduces the file byte for byte.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"
out="project_plans/excalidraw-sketch-engine/implementation/regression-baseline.txt"
sha=""
while [ $# -gt 0 ]; do
  case "$1" in
    --sha) sha="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$sha" ]; then
  sha=$(git merge-base origin/main HEAD)
fi
sha=$(git rev-parse --verify "${sha}^{commit}")
# A SHA only on the engine branch vanishes on squash merge, so refuse it.
if ! git merge-base --is-ancestor "$sha" origin/main; then
  echo "refusing $sha: not an ancestor of origin/main" >&2
  exit 1
fi

base=kmp/src
test_re="^${base}/(commonTest|businessTest|jvmTest|androidUnitTest)/"

# All commands below sort with LC_ALL=C so output is locale independent.
export LC_ALL=C
tests=$(git ls-tree -r --name-only "$sha" \
  | grep -E "${test_re}.*kotlin/.*\.kt$" \
  | grep -iE 'sketch' | grep -v '/sketch/v2/' | sort || true)
fixtures=$(git ls-tree -r --name-only "$sha" \
  | grep -E "${test_re}(.*/)?resources/" \
  | grep -iE 'sketch' | grep -v '/sketch/v2/' | sort || true)

test_count=0; [ -n "$tests" ] && test_count=$(printf '%s\n' "$tests" | wc -l | tr -d ' ')
fixture_count=0; [ -n "$fixtures" ] && fixture_count=$(printf '%s\n' "$fixtures" | wc -l | tr -d ' ')

{
  echo "baseline-sha: $sha"
  echo "test-class-count: $test_count"
  echo "golden-fixture-count: $fixture_count"
  echo "# sketch test files (path)"
  [ -n "$tests" ] && printf '%s\n' "$tests"
  echo "# sketch golden fixtures (sha256  path)"
  if [ -n "$fixtures" ]; then
    while IFS= read -r p; do
      printf '%s  %s\n' "$(git show "$sha:$p" | sha256sum | cut -d' ' -f1)" "$p"
    done <<<"$fixtures"
  fi
} > "$out"
echo "wrote $out (sha $sha, $test_count tests, $fixture_count fixtures)" >&2
