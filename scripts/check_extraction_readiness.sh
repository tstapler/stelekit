#!/usr/bin/env bash
# check_extraction_readiness.sh — one-command GO/NO-GO aggregator for the
# Common Per-Package Extraction Recipe's steps 1-3 (see
# project_plans/commonmain-bazel-target-split/implementation/plan.md).
#
# Runs, in order: (1) the deps-derivation grep, (2) check_internal_visibility.py
# in both directions (declaring and consuming), (3) the @Composable/@Serializable
# grep — then prints a single GO/NO-GO summary. Thin wrapper, no new detection
# logic — the manual grep/script invocations remain the documented fallback.
#
# Usage: scripts/check_extraction_readiness.sh <package-name>

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <package-name>" >&2
  exit 2
fi

PKG="$1"
ROOT="kmp/src/commonMain/kotlin/dev/stapler/stelekit"
PKG_DIR="$ROOT/$PKG"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -d "$PKG_DIR" ]]; then
  echo "usage error: package directory not found: $PKG_DIR" >&2
  exit 2
fi

no_go=0

# ── 1/3: deps derivation ──────────────────────────────────────────────────────
deps=$(grep -rhoE 'import dev\.stapler\.stelekit\.[a-zA-Z0-9_]+' "$PKG_DIR" --include='*.kt' \
  | sed -E 's/^import dev\.stapler\.stelekit\.//' \
  | sort -u \
  | grep -v -x "$PKG" || true)
dep_count=$(printf '%s\n' "$deps" | grep -c . || true)
if [[ "$dep_count" -eq 0 ]]; then
  deps_summary="0 packages imported (stdlib only)"
else
  deps_summary="$dep_count package(s) imported ($(printf '%s\n' "$deps" | paste -sd, -))"
fi
echo "[1/3] deps: $deps_summary"

# ── 2/3: bidirectional internal-visibility check ─────────────────────────────
declaring_out=$(python3 "$SCRIPT_DIR/check_internal_visibility.py" "$PKG" --root "$ROOT" 2>&1) \
  && declaring_code=0 || declaring_code=$?

# Consuming direction: does $PKG use any OTHER package's internal symbols?
# Run the audit for every other package and check whether $PKG appears as a
# consumer in its output.
consuming_hits=""
for other in $(find "$ROOT" -maxdepth 1 -mindepth 1 -type d -printf '%f\n' | sort); do
  [[ "$other" == "$PKG" ]] && continue
  other_out=$(python3 "$SCRIPT_DIR/check_internal_visibility.py" "$other" --root "$ROOT" 2>&1) || true
  hit=$(printf '%s\n' "$other_out" | grep -E "used by ${PKG}:" || true)
  if [[ -n "$hit" ]]; then
    consuming_hits+="$hit"$'\n'
  fi
done

visibility_violations=0
if [[ "$declaring_code" -eq 1 ]]; then
  visibility_violations=$((visibility_violations + 1))
fi
if [[ -n "$consuming_hits" ]]; then
  visibility_violations=$((visibility_violations + 1))
fi

if [[ "$visibility_violations" -eq 0 ]]; then
  echo "[2/3] internal-visibility: 0 cross-package usages found"
else
  echo "[2/3] internal-visibility: cross-package usage found — see below"
  no_go=1
  if [[ "$declaring_code" -eq 1 ]]; then
    echo "  -- ${PKG}'s own internal symbols consumed elsewhere (needs associates FROM consumers TO ${PKG}):"
    printf '%s\n' "$declaring_out" | sed 's/^/     /'
  fi
  if [[ -n "$consuming_hits" ]]; then
    echo "  -- ${PKG} consumes another package's internal symbols (needs associates FROM ${PKG} TO declarer):"
    printf '%s\n' "$consuming_hits" | sed 's/^/     /'
  fi
fi

# ── 3/3: Compose/serialization plugin grep ───────────────────────────────────
plugin_hits=$(grep -rl '@Composable\|@Serializable' "$PKG_DIR" --include='*.kt' || true)
if [[ -z "$plugin_hits" ]]; then
  echo "[3/3] plugins: no @Composable/@Serializable found"
else
  plugin_count=$(printf '%s\n' "$plugin_hits" | grep -c . || true)
  echo "[3/3] plugins: @Composable/@Serializable found in $plugin_count file(s) — set uses_compose/uses_serialization"
fi

echo "---"
if [[ "$no_go" -eq 0 ]]; then
  echo "GO: $PKG is ready for extraction (see steps 4-8 of the Common Per-Package Extraction Recipe)"
  exit 0
else
  echo "NO-GO: $PKG needs manual internal-visibility follow-up before writing BUILD.bazel (see [2/3] above)"
  exit 1
fi
