#!/usr/bin/env bash
# scripts/benchmark-local.sh — Run the graph-load benchmark locally, matching CI output.
#
# Usage:
#   ./scripts/benchmark-local.sh                        # synthetic graph only
#   ./scripts/benchmark-local.sh /path/to/your/graph   # include real-graph test
#
# Prerequisites (macOS):
#   brew install async-profiler librsvg
#
# Prerequisites (Linux):
#   sudo apt-get install -y librsvg2-bin
#   # async-profiler: download tarball from github.com/async-profiler/async-profiler/releases
#   # and place it at async-profiler-4.4-linux-x64/ in the repo root, or set AP_LIB below.
#
# Outputs in kmp/build/reports/:
#   graph-load.jfr              — raw JFR recording (alloc + CPU)
#   graph-load-wall.jfr         — async-profiler wall-clock recording
#   graph-load-alloc.collapsed  — allocation stacks (collapsed)
#   graph-load-cpu.collapsed    — wall-clock stacks filtered to coroutine threads
#   flamegraph.html             — interactive allocation flamegraph
#   flamegraph-alloc.png        — allocation flamegraph image
#   flamegraph-cpu.png          — wall-clock CPU flamegraph image

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPORTS_DIR="$REPO_ROOT/kmp/build/reports"
GRAPH_PATH="${1:-}"

# ── async-profiler library ───────────────────────────────────────────────────

AP_LIB="${AP_LIB:-}"
if [[ -z "$AP_LIB" ]]; then
    for candidate in \
        "$REPO_ROOT/async-profiler-4.4-linux-x64/lib/libasyncProfiler.so" \
        "/opt/homebrew/lib/libasyncProfiler.dylib" \
        "/usr/local/lib/libasyncProfiler.dylib" \
        "/usr/local/lib/libasyncProfiler.so"; do
        if [[ -f "$candidate" ]]; then
            AP_LIB="$candidate"
            break
        fi
    done
fi

if [[ -n "$AP_LIB" ]]; then
    echo "Wall-clock profiling: $AP_LIB"
else
    echo "Wall-clock profiling: unavailable — CPU flamegraph will use JFR samples instead"
    echo "  macOS:  brew install async-profiler"
    echo "  Linux:  download async-profiler-4.4-linux-x64.tar.gz to repo root"
fi

# ── run benchmark ────────────────────────────────────────────────────────────

GRADLE_ARGS=(":kmp:jvmTestProfile" "--rerun-tasks" "--no-daemon")
[[ -n "$GRAPH_PATH" ]] && GRADLE_ARGS+=("-PgraphPath=$GRAPH_PATH")
[[ -n "$AP_LIB" ]]    && GRADLE_ARGS+=("-PapLib=$AP_LIB")

cd "$REPO_ROOT"
./gradlew "${GRADLE_ARGS[@]}"

# ── generate PNG flamegraphs ─────────────────────────────────────────────────

svg_to_png() {
    local svg="$1" png="$2"
    if command -v rsvg-convert &>/dev/null; then
        rsvg-convert -w 1800 "$svg" -o "$png"
    elif command -v convert &>/dev/null; then
        convert -resize 1800 "$svg" "$png"
    else
        echo "  (skipping PNG — install librsvg: brew install librsvg)"
        return
    fi
    echo "  $png"
}

SHORT_SHA=$(git rev-parse --short HEAD 2>/dev/null || echo "local")

if [[ -f "$REPORTS_DIR/graph-load-alloc.collapsed" ]]; then
    ./gradlew -q --no-daemon :tools:flamegraph:run \
        -Pfg.width=1800 \
        "-Pfg.title=Alloc flamegraph ($SHORT_SHA)" \
        -Pfg.colors=mem \
        "-Pfg.input=$REPORTS_DIR/graph-load-alloc.collapsed" \
        "-Pfg.output=$REPORTS_DIR/flamegraph-alloc.svg"
    svg_to_png "$REPORTS_DIR/flamegraph-alloc.svg" "$REPORTS_DIR/flamegraph-alloc.png"
fi

if [[ -f "$REPORTS_DIR/graph-load-cpu.collapsed" ]]; then
    ./gradlew -q --no-daemon :tools:flamegraph:run \
        -Pfg.width=1800 \
        "-Pfg.title=CPU flamegraph — wall-clock, coroutine threads ($SHORT_SHA)" \
        -Pfg.colors=java \
        "-Pfg.input=$REPORTS_DIR/graph-load-cpu.collapsed" \
        "-Pfg.output=$REPORTS_DIR/flamegraph-cpu.svg"
    svg_to_png "$REPORTS_DIR/flamegraph-cpu.svg" "$REPORTS_DIR/flamegraph-cpu.png"
fi

# ── summary ──────────────────────────────────────────────────────────────────

echo ""
echo "── Results in $REPORTS_DIR/ ──"
for f in graph-load.jfr graph-load-wall.jfr graph-load-alloc.collapsed \
          graph-load-cpu.collapsed flamegraph.html flamegraph-alloc.png flamegraph-cpu.png; do
    [[ -f "$REPORTS_DIR/$f" ]] && echo "  $f"
done
