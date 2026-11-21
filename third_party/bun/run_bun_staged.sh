#!/usr/bin/env bash
# Hermetic variant: run Bun from the runfiles directory of the calling package.
# Usage: <bun-rlocation> <bun args...>
set -euo pipefail
BUN="$(realpath "$1")"; shift
cd "$TEST_SRCDIR/$TEST_WORKSPACE/${TEST_BINARY%/*}"
exec "$BUN" "$@"
