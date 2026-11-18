#!/usr/bin/env bash
# Self-test of check-sketch-baseline.sh (Task 6.2.5a): a shallow clone fails with the documented message, and an edited
# baseline test fails the comparison. Run from any checkout that has the baseline file committed.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fail=0
check() { # <name> <expected exit> <expected text> <dir>
  local out rc=0
  out=$(cd "$4" && scripts/sketch-baseline/check-sketch-baseline.sh 2>&1) || rc=$?
  if [ "$rc" -eq "$2" ] && printf '%s' "$out" | grep -q "$3"; then echo "ok: $1"; else echo "FAIL: $1 (exit $rc)"; echo "$out"; fail=1; fi
}

git clone -q --depth 1 "file://$root" "$work/shallow"
check "shallow clone is refused" 1 "baseline SHA not reachable: shallow clone or not on main" "$work/shallow"

git clone -q "$root" "$work/full"
# CI checks out a detached merge commit with no local `main`, so a clone of it has no origin/main; the script under test needs one.
git -C "$work/full" update-ref refs/remotes/origin/main "$(git -C "$root" rev-parse origin/main)"
cp -r "$root/scripts/sketch-baseline" "$work/full/scripts/"
cp "$root/project_plans/excalidraw-sketch-engine/implementation/regression-baseline.txt" \
  "$work/full/project_plans/excalidraw-sketch-engine/implementation/regression-baseline.txt"
victim=$(sed -n '/^# sketch test files/,/^# sketch golden fixtures/{/^#/d;/^change-record/d;p}' "$work/full/project_plans/excalidraw-sketch-engine/implementation/regression-baseline.txt" | sed -n 1p)
check "an unmodified tree passes" 0 "metric 6 baseline holds" "$work/full"
(cd "$work/full" && echo "// edited" >> "$victim" && git -c user.email=t@example.com -c user.name=t commit -q -am "edit a baseline test")
check "an edited baseline test fails" 1 "baseline test edited: $victim" "$work/full"
exit "$fail"
