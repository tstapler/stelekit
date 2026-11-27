#!/usr/bin/env bash
# Fails when any Maven repo in the module graph is unpinned: Coursier would resolve it from Maven
# Central at fetch time, bypassing --downloader_config, and CI gets HTTP 429.
set -euo pipefail
cd "$(dirname "$0")/../.."

ext=$(bazel mod show_extension @rules_jvm_external//:extensions.bzl%maven)
repos=$(sed -n 's/^  - \([A-Za-z0-9_.-]*\) (imported by .*/\1/p' <<<"$ext" | grep -v '^unpinned_' || true)
[ -n "$repos" ] || { echo "No Maven repositories found in the module graph"; exit 1; }

unpinned=()
for repo in $repos; do
  rule=$(bazel mod show_repo "@@rules_jvm_external++maven+$repo")
  grep -q 'pinned_coursier_fetch(' <<<"$rule" || unpinned+=("$repo")
done

if [ ${#unpinned[@]} -ne 0 ]; then
  echo "Unpinned Maven repositories: ${unpinned[*]}"
  echo "For each, add maven.install(name, lock_file, repositories) and use_repo in MODULE.bazel,"
  echo "then run: REPIN=1 bazel run @<name>//:pin"
  exit 1
fi
echo "All Maven repositories are pinned: $(echo $repos)"
