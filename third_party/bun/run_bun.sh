#!/usr/bin/env bash
# Usage (sh_test args): <bun-rlocation> <workdir-relative-to-workspace> [--install] <bun args...>
# Runs the pinned Bun in the real source tree (targets are tagged `local`: install/build need
# the writable checkout and network, which the sandbox would block).
set -euo pipefail
BUN="$(realpath "$1")"; DIR="$2"; shift 2
ROOT="$(dirname "$(realpath "$TEST_SRCDIR/$TEST_WORKSPACE/MODULE.bazel")")"
cd "$ROOT/$DIR"
if [[ "${1:-}" == "--install" ]]; then shift; "$BUN" install --frozen-lockfile; fi
exec "$BUN" "$@"
