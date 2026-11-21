#!/usr/bin/env bash
# Playwright functional suite against the Bazel-built web bundle. Usage: <bun> <web_dist.tar.gz>
set -euo pipefail
BUN="$(realpath "$1")"; DIST_TAR="$(realpath "$2")"
ROOT="$(dirname "$(realpath "$TEST_SRCDIR/$TEST_WORKSPACE/MODULE.bazel")")"
DIST="$(mktemp -d)"; tar -xzf "$DIST_TAR" -C "$DIST"
cd "$ROOT/e2e"
"$BUN" install --frozen-lockfile
"$BUN" x playwright install --with-deps chromium
CI=true DEMO_DIST="$DIST" exec "$BUN" x playwright test tests/demo.spec.ts
