#!/usr/bin/env bash
# gfxinfo-session.sh — Android exploratory jank testing via dumpsys gfxinfo
#
# Usage:
#   ./scripts/gfxinfo-session.sh                        # detects package from adb
#   ./scripts/gfxinfo-session.sh dev.stapler.stelekit   # explicit package name
#
# Workflow:
#   1. Resets gfxinfo counters on the connected device
#   2. Waits while you run through the exploratory testing checklist
#   3. Dumps and parses frame stats (janky frames, p50/p90/p95/p99 frame times)
#
# Prerequisites: adb in PATH, device connected and unlocked

set -euo pipefail

PACKAGE="${1:-}"

# ── resolve package name ──────────────────────────────────────────────────────

if [[ -z "$PACKAGE" ]]; then
    PACKAGE=$(adb shell pm list packages 2>/dev/null | grep "stelekit" | sed 's/package://' | tr -d '\r' | head -1)
fi

if [[ -z "$PACKAGE" ]]; then
    echo "Error: could not detect SteleKit package. Pass it as the first argument." >&2
    echo "  ./scripts/gfxinfo-session.sh dev.stapler.stelekit" >&2
    exit 1
fi

echo "── Package: $PACKAGE"

# ── verify device ─────────────────────────────────────────────────────────────

DEVICE_STATE=$(adb get-state 2>/dev/null || echo "no-device")
if [[ "$DEVICE_STATE" != "device" ]]; then
    echo "Error: no device connected (adb get-state = $DEVICE_STATE)" >&2
    exit 1
fi

DEVICE_MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
REFRESH_HZ=$(adb shell dumpsys display 2>/dev/null | grep -i "mBaseDisplayInfo" | grep -oP "(?<=refreshRate=)\d+\.\d+" | head -1 || echo "60")
JANK_THRESHOLD_MS=$(echo "scale=1; 2 * (1000 / ${REFRESH_HZ%.*})" | bc 2>/dev/null || echo "33")

echo "── Device: $DEVICE_MODEL  (${REFRESH_HZ}Hz, jank threshold >${JANK_THRESHOLD_MS}ms)"

# ── reset counters ─────────────────────────────────────────────────────────────

echo ""
echo "── Resetting gfxinfo counters..."
adb shell dumpsys gfxinfo "$PACKAGE" reset

# ── exploratory session ────────────────────────────────────────────────────────

echo ""
echo "┌─────────────────────────────────────────────────────────────────┐"
echo "│  Exploratory Testing Checklist                                  │"
echo "│                                                                 │"
echo "│  1. Open a graph with 100+ pages                                │"
echo "│  2. Scroll through the journal list quickly                     │"
echo "│  3. Navigate to 5-10 different pages (back and forward)         │"
echo "│  4. Rapidly type in a block editor (tests debounce + recomp)    │"
echo "│  5. Open the search dialog and type quickly                     │"
echo "│  6. Switch between graphs (if multiple are configured)          │"
echo "│  7. Open All Pages and scroll through the full list             │"
echo "└─────────────────────────────────────────────────────────────────┘"
echo ""
echo "Run through the checklist, then press Enter to capture frame stats..."
read -r

# ── capture and parse ──────────────────────────────────────────────────────────

RAW=$(adb shell dumpsys gfxinfo "$PACKAGE" framestats 2>/dev/null)

echo ""
echo "── Raw summary:"
echo "$RAW" | grep -E "Total|Janky|90th|95th|99th|Number|Missed" | tr -d '\r' | sed 's/^/   /'

echo ""

# Parse janky frame percentage
JANKY_LINE=$(echo "$RAW" | grep -i "Janky frames:" | tr -d '\r' | head -1)
TOTAL_LINE=$(echo "$RAW" | grep -i "Total frames rendered:" | tr -d '\r' | head -1)

if [[ -n "$JANKY_LINE" && -n "$TOTAL_LINE" ]]; then
    JANKY=$(echo "$JANKY_LINE" | grep -oP '\d+' | head -1 || echo "?")
    TOTAL=$(echo "$TOTAL_LINE" | grep -oP '\d+' | head -1 || echo "?")
    if [[ "$TOTAL" != "?" && "$TOTAL" -gt 0 && "$JANKY" != "?" ]]; then
        JANK_PCT=$(echo "scale=1; $JANKY * 100 / $TOTAL" | bc 2>/dev/null || echo "?")
        echo "── Jank rate: ${JANKY}/${TOTAL} frames (${JANK_PCT}%)"
        if [[ "$JANK_PCT" != "?" ]]; then
            JANK_INT=${JANK_PCT%.*}
            if (( JANK_INT >= 10 )); then
                echo "   WARNING: ≥10% jank — investigate with Layout Inspector or JFR profiling"
            elif (( JANK_INT >= 5 )); then
                echo "   NOTICE:  5-10% jank — acceptable but worth profiling"
            else
                echo "   OK:      <5% jank"
            fi
        fi
    fi
fi

P50=$(echo "$RAW" | grep -i "50th percentile" | grep -oP '\d+ms' | head -1 | tr -d 'ms' || echo "?")
P90=$(echo "$RAW" | grep -i "90th percentile" | grep -oP '\d+ms' | head -1 | tr -d 'ms' || echo "?")
P95=$(echo "$RAW" | grep -i "95th percentile" | grep -oP '\d+ms' | head -1 | tr -d 'ms' || echo "?")
P99=$(echo "$RAW" | grep -i "99th percentile" | grep -oP '\d+ms' | head -1 | tr -d 'ms' || echo "?")

if [[ "$P50" != "?" || "$P95" != "?" ]]; then
    echo "── Frame percentiles: p50=${P50}ms  p90=${P90}ms  p95=${P95}ms  p99=${P99}ms"
fi

# ── save raw output ────────────────────────────────────────────────────────────

OUTDIR="kmp/build/reports/gfxinfo"
mkdir -p "$OUTDIR"
TIMESTAMP=$(date +%Y-%m-%d_%H-%M-%S)
OUTFILE="$OUTDIR/gfxinfo-$TIMESTAMP.txt"
echo "$RAW" > "$OUTFILE"
echo ""
echo "── Full output saved: $OUTFILE"
echo ""
echo "── Next steps if jank is high:"
echo "   • Android: enable JankStats overlay — open Debug Menu in app → Frame Overlay"
echo "   • Android: check Compose recomposition — ./gradlew :kmp:testDebugUnitTest -PrecompositionReport"
echo "   • Desktop:  run with Skia FPS overlay  — ./gradlew :kmp:run -PfpsOverlay"
echo "   • Desktop:  profile with JFR           — ./gradlew :kmp:run (always-on)"
