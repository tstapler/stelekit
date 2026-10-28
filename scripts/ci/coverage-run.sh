#!/usr/bin/env bash
# Full coverage baseline: JVM (jvmTest = businessTest + commonTest + jvmTest sources) and Robolectric
# (testDebugUnitTest) under JaCoCo. Writes jacoco.xml, jacoco-android.xml and runtime.json
# (wall-clock seconds, peak RSS and exit code per tier) into the output directory.
#
# Usage: scripts/ci/coverage-run.sh [outdir]      (default: kmp/build/coverage-baseline)
# Env:   TIER_TIMEOUT (default 90m), SAMPLE_SECS (default 5)
#
# Peak RSS is the largest sum of RSS over all Gradle/Kotlin-daemon/test-worker JVMs seen while the
# tier ran (the Gradle client alone would under-report). Test failures do not stop the run: the
# coverage report is still produced, and the tier's exit code is recorded in runtime.json.
set -uo pipefail

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root" || exit 1
out=${1:-kmp/build/coverage-baseline}
mkdir -p "$out"
runtime_json="$out/runtime.json"
[ -f "$runtime_json" ] || echo '{}' > "$runtime_json"
tier_timeout=${TIER_TIMEOUT:-90m}
sample_secs=${SAMPLE_SECS:-5}

record() { # tier seconds peak_rss_mb exit_code command
    python3 - "$runtime_json" "$@" <<'PY'
import json, sys
path, tier, secs, rss, code, cmd = sys.argv[1:7]
data = json.load(open(path))
data[tier] = {"wall_clock_seconds": int(secs), "peak_rss_mb": int(rss), "exit_code": int(code), "command": cmd}
json.dump(data, open(path, "w"), indent=2, sort_keys=True)
PY
}

jvm_rss_mb() { # sum RSS (MB) of every JVM belonging to Gradle (client, daemons, test workers)
    ps -eo rss,args --no-headers | awk '/[j]ava/ && /[G]radle|[g]radle|[K]otlin/ {s+=$1} END {printf "%d", s/1024}'
}

measure() { # tier command...
    local tier=$1; shift
    local start peak=0 now rss pid code
    start=$(date +%s)
    "$@" &
    pid=$!
    while kill -0 "$pid" 2>/dev/null; do
        rss=$(jvm_rss_mb); [ "$rss" -gt "$peak" ] && peak=$rss
        sleep "$sample_secs"
    done
    wait "$pid"; code=$?
    now=$(date +%s)
    record "$tier" "$((now - start))" "$peak" "$code" "$*"
    echo "coverage-run: $tier exit=$code seconds=$((now - start)) peak_rss_mb=$peak" >&2
    return 0
}

gw() { timeout "$tier_timeout" ./gradlew --no-daemon "$@"; }

jvm_tier() {
    local steps="timeout $tier_timeout ./gradlew --no-daemon :kmp:jvmTest --continue; \
        timeout $tier_timeout ./gradlew --no-daemon :kmp:jacocoTestReport -x :kmp:jvmTest"
    if scripts/jvm-display-check.sh; then
        scripts/jvm-display-check.sh -- bash -c "$steps"
    else
        # No display and no Xvfb (local Wayland-only box): Compose Desktop UI tests will fail with
        # XToolkit errors, so those failures are display artifacts, not regressions. CI installs xvfb.
        echo "coverage-run: NO DISPLAY - jvmTest UI tests run without X11; coverage of UI code is understated" >&2
        python3 - "$runtime_json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["jvm_display_available"] = False
json.dump(d, open(sys.argv[1], "w"), indent=2, sort_keys=True)
PY
        bash -c "$steps"
    fi
}
android_tier() {
    gw :kmp:testDebugUnitTest --continue
    gw :kmp:jacocoAndroidTestReport -x :kmp:testDebugUnitTest
}

measure jvm_jvmTest jvm_tier
cp -f kmp/build/reports/jacoco/jacoco.xml "$out/jacoco.xml" 2>/dev/null || echo "coverage-run: jacoco.xml missing" >&2

measure android_testDebugUnitTest android_tier
cp -f kmp/build/reports/jacoco/jacoco-android.xml "$out/jacoco-android.xml" 2>/dev/null || echo "coverage-run: jacoco-android.xml missing" >&2

# Totals straight from the XML report-level counters (run output, not an estimate).
python3 - "$out" <<'PY'
import sys, xml.etree.ElementTree as ET, os, json
out = sys.argv[1]
totals = {}
for name in ("jacoco.xml", "jacoco-android.xml"):
    p = os.path.join(out, name)
    if not os.path.isfile(p):
        continue
    root = ET.parse(p).getroot()
    t = {}
    for c in root.findall("counter"):
        covered, missed = int(c.get("covered")), int(c.get("missed"))
        t[c.get("type").lower()] = {"covered": covered, "total": covered + missed,
                                    "pct": round(100 * covered / max(1, covered + missed), 1)}
    totals[name] = t
json.dump(totals, open(os.path.join(out, "totals.json"), "w"), indent=2)
print(json.dumps(totals, indent=2))
PY
