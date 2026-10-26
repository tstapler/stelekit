#!/usr/bin/env bash
# Run a command while sampling runner memory, then publish peak/min-free as benchdiff metrics.
#
# Usage: with-mem-sampler.sh <metrics.json> <label> -- <command...>
#
# Samples go to stdout, i.e. the job log, which survives a killed runner (a file or the step
# summary does not). The command's exit status is returned unchanged.
#   [mem]     whole-machine used/available MB
#   [mem-top] the largest processes by RSS, with their -Xmx and a recognisable name
# Env: MEM_SAMPLE_SECS (default 30), MEM_WARN_AVAIL_MB (default 2000: emit a ::warning below it).
set -o pipefail

out=$1 label=$2
[ "$3" = "--" ] || { echo "usage: $0 <metrics.json> <label> -- <command...>" >&2; exit 2; }
shift 3

interval=${MEM_SAMPLE_SECS:-30}
warn_avail_mb=${MEM_WARN_AVAIL_MB:-2000}
samples=$(mktemp)

take_sample() {
    free -m | awk '/Mem:/{print $3, $7}' | tee -a "$samples" | awk '{print "[mem] used=" $1 "M avail=" $2 "M"}'
    ps -eo rss,args --sort=-rss --no-headers | head -6 | awk '{
      rss = $1 / 1024; $1 = ""; xmx = ""; name = ""
      if (match($0, /-Xmx[0-9]+[kKmMgG]?/)) xmx = substr($0, RSTART, RLENGTH)
      if (match($0, /desugar_java8|d8_[a-z_]+|rules_kotlin|bazel\(|remotejdk[0-9]*|Runner\.Worker/)) name = substr($0, RSTART, RLENGTH)
      else name = substr($0, 1, 60)
      printf "[mem-top] %dM %s %s\n", rss, xmx, name
    }'
}

sample_forever() {
  while :; do
    take_sample
    sleep "$interval"
  done
}

sample_forever &
sampler=$!
trap 'kill "$sampler" 2>/dev/null' EXIT

"$@"
status=$?

take_sample
kill "$sampler" 2>/dev/null
wait "$sampler" 2>/dev/null

read -r peak_used min_avail < <(awk 'NR==1{mn=$2} {if($1>mu)mu=$1; if($2<mn)mn=$2} END{print mu+0, mn+0}' "$samples")
if [ -s "$samples" ]; then
  mkdir -p "$(dirname "$out")"
  printf '{"schema":1,"env":{"sha":"%s"},"metrics":[{"group":"ci-memory/%s","name":"peak_used","measure":"max","unit":"MB","better":"lower","value":%s},{"group":"ci-memory/%s","name":"min_available","measure":"min","unit":"MB","better":"higher","value":%s}]}\n' \
    "${GITHUB_SHA:-unknown}" "$label" "$peak_used" "$label" "$min_avail" > "$out"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "### Memory: $label — peak used ${peak_used}M, min available ${min_avail}M" >> "$GITHUB_STEP_SUMMARY"
  fi
  if [ "$min_avail" -lt "$warn_avail_mb" ]; then
    echo "::warning title=Low memory headroom ($label)::available memory dropped to ${min_avail}M (threshold ${warn_avail_mb}M); an OOM kill is likely if this regresses"
  fi
fi
exit $status
