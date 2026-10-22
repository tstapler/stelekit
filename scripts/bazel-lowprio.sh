#!/usr/bin/env bash
# Run bazel as the first thing the kernel OOM-killer picks and the last thing to get CPU/IO, so a
# runaway build or test dies instead of taking the desktop (plasmashell, kwin) down with it.
#   scripts/bazel-lowprio.sh test //kmp:jvm_tests
# The Bazel server and everything it spawns inherit the setting from whoever starts the server,
# so run `bazel shutdown` once if a server started without it is already up.
set -euo pipefail

# Raising oom_score_adj needs no privileges (lowering it does); ignore failure on odd kernels.
echo 1000 > /proc/self/oom_score_adj 2>/dev/null || echo "bazel-lowprio: could not set oom_score_adj" >&2

if command -v ionice >/dev/null; then set -- ionice -c3 "${BAZEL_BIN:-bazel}" "$@"; else set -- "${BAZEL_BIN:-bazel}" "$@"; fi
exec nice -n 10 "$@"
