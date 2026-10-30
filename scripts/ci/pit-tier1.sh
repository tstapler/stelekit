#!/usr/bin/env bash
# Timed Tier-1 PIT run (db/ and repository/ classes). Records duration and survivor count in
# runtime.json under "pit_tier1"; does not gate (-Pmutate.threshold=0).
#
# Usage: scripts/ci/pit-tier1.sh [outdir]      (default: kmp/build/coverage-baseline)
# Env:   PIT_TIMEOUT (default 90m), PIT_TIMEOUT_MINUTES (Gradle-side PIT timeout, default 80)
set -uo pipefail

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root" || exit 1
out=${1:-kmp/build/coverage-baseline}
mkdir -p "$out"
runtime_json="$out/runtime.json"
[ -f "$runtime_json" ] || echo '{}' > "$runtime_json"

classes='dev.stapler.stelekit.db.*,dev.stapler.stelekit.repository.*'
cmd=(./gradlew --no-daemon :kmp:mutationTest "-Pmutate.classes=$classes"
     "-Pmutate.tests=$classes" -Pmutate.threshold=0 "-Pmutate.timeoutMinutes=${PIT_TIMEOUT_MINUTES:-80}")

start=$(date +%s)
timeout "${PIT_TIMEOUT:-90m}" "${cmd[@]}"
code=$?
end=$(date +%s)

python3 - "$runtime_json" "$((end - start))" "$code" "${cmd[*]}" kmp/build/reports/pitest/mutations.xml <<'PY'
import json, os, sys, xml.etree.ElementTree as ET
path, secs, code, cmd, report = sys.argv[1:6]
entry = {"wall_clock_seconds": int(secs), "exit_code": int(code), "command": cmd}
if os.path.isfile(report):
    try:
        statuses = [m.get("status") for m in ET.parse(report).getroot().iter("mutation")]
    except ET.ParseError:
        statuses = None
        entry["note"] = "mutations.xml empty or truncated: PIT was stopped before it finished"
    if statuses is not None:
        entry.update(mutations=len(statuses), survivors=statuses.count("SURVIVED"),
                     killed=statuses.count("KILLED"), no_coverage=statuses.count("NO_COVERAGE"),
                     timed_out=statuses.count("TIMED_OUT"))
else:
    entry["note"] = "no mutations.xml produced"
data = json.load(open(path))
data["pit_tier1"] = entry
json.dump(data, open(path, "w"), indent=2, sort_keys=True)
print(json.dumps(entry, indent=2))
PY
exit 0
