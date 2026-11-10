#!/usr/bin/env bash
# Fails if a benchmark mutation flag name or the Mutations object appears in any production source
# set (pre-mortem failure 5, plan B.5d): mutations live in androidInstrumentedTest only, so no
# release build can contain one. Source-level stand-in for a dex-grep of the release APK.
set -euo pipefail

cd "$(dirname "$0")/.."

flags='annotateMutation|object Mutations|loaderPerPress|perDrawMeasure|busyWaitMove|backgroundBusyLoop|leakStrongRef|exitOnBackground|loupeFullDecode|cacheNoLimit|undoUnbounded|oomInjection'
roots=()
for d in kmp/src/commonMain kmp/src/androidMain kmp/src/jvmMain kmp/src/jvmCommonMain kmp/src/iosMain kmp/src/wasmJsMain androidApp/src/main; do
  [ -d "$d" ] && roots+=("$d")
done

if hits=$(grep -rnE --include='*.kt' --include='*.java' --include='*.xml' --include='*.pro' "$flags" "${roots[@]}"); then
  echo "mutation flag in production sources:" >&2
  echo "$hits" >&2
  exit 1
fi
echo "no mutation flags in production sources (${#roots[@]} roots scanned)"
