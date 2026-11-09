#!/usr/bin/env bash
# Verifies that new/changed commonTest and businessTest classes actually run under
# `bazel test //kmp:business_tests` (the businessTest suite-gap guard, plan Story B.8).
#
#   scripts/check-bazel-test-listing.sh [ClassName...]
#
# With no arguments the class names come from the *Test.kt files changed against origin/main, so a
# forgotten registration cannot be hidden by omitting the argument. A class counts as listed when
# its name appears in the test log or in the JUnit XML that Bazel writes for the target.
set -euo pipefail

cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

if [ "$#" -gt 0 ]; then
  classes=("$@")
else
  mapfile -t classes < <(
    git diff --name-only --diff-filter=AM origin/main...HEAD -- '*Test.kt' \
      | grep -E '^kmp/src/(commonTest|businessTest)/' \
      | xargs -r -n1 basename \
      | sed 's/\.kt$//' | sort -u
  )
fi

if [ "${#classes[@]}" -eq 0 ]; then
  echo "check-bazel-test-listing: no new or changed commonTest/businessTest classes, nothing to verify"
  exit 0
fi

log="$(mktemp)"
trap 'rm -f "$log"' EXIT
timeout 30m bazel test //kmp:business_tests --test_output=all 2>&1 | tee "$log" >/dev/null || {
  tail -40 "$log" >&2
  echo "check-bazel-test-listing: bazel test //kmp:business_tests failed" >&2
  exit 1
}

xml="$(bazel info bazel-testlogs 2>/dev/null)/kmp/src/businessTest/kotlin/business_tests/test.xml"
missing=0
for c in "${classes[@]}"; do
  if grep -q "$c" "$log" || { [ -f "$xml" ] && grep -q "$c" "$xml"; }; then
    echo "listed:  $c"
  else
    echo "MISSING: $c (register it in AllBusinessTests, and in business_common_tests srcs if it is a commonTest class)" >&2
    missing=1
  fi
done
exit "$missing"
