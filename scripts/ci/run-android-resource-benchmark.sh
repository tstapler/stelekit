#!/usr/bin/env bash
# Runs the annotation resource benchmark classes in ONE batched instrumentation run, inside an
# already-booted emulator (android-emulator-runner executes each script line separately, so the
# conditional logic lives here). Appends the tagged logcat to /tmp/android-bench-logcat.txt for
# scripts/android-benchmark-summarize.py.
#
#   RUN_RESOURCE=true|false  skip everything when false (PR touched nothing relevant)
#   EXPECTED_RAM_MB          the AVD ram-size, so the probe can tag an invalid device
#   ANNOTATE_MUTATION        optional mutation flag name (workflow_dispatch proof runs)
#
# Gradle installs the test APK but `am instrument` starts it: `-Pandroid.testInstrumentationRunnerArguments.<custom>`
# was observed to drop custom keys (only `class` arrived on the device), which would turn a mutation
# proof into a silent no-op. Arguments passed with `-e` do arrive (verified on Pixel_Fold, 2026-11-09).
set -euo pipefail

cd "$(dirname "$0")/../.."

if [ "${RUN_RESOURCE:-true}" != "true" ]; then
  echo "run-android-resource-benchmark: out of scope for this change, skipping"
  exit 0
fi

classes=(
  dev.stapler.stelekit.performance.annotation.LibraryScrollSwitchResourceTest
  dev.stapler.stelekit.performance.annotation.AnnotationLifecycleResourceTest
  dev.stapler.stelekit.performance.sketch.SketchCanvasBenchmarkTest
  dev.stapler.stelekit.performance.probe.ResourceProbeSelfTest
  dev.stapler.stelekit.performance.probe.FixtureGraphSmokeTest
  dev.stapler.stelekit.LoupeAlignmentE2ETest
)
class_list=$(IFS=,; echo "${classes[*]}")

./gradlew :kmp:installDebugAndroidTest --no-daemon

args=(-w -r -e class "$class_list")
if [ -n "${EXPECTED_RAM_MB:-}" ]; then args+=(-e expectedRamMb "$EXPECTED_RAM_MB"); fi
if [ -n "${ANNOTATE_MUTATION:-}" ]; then args+=(-e annotateMutation "$ANNOTATE_MUTATION"); fi

out="$(mktemp)"
trap 'rm -f "$out"' EXIT
adb logcat -c || true
adb shell am instrument "${args[@]}" dev.stapler.stelekit.test/androidx.test.runner.AndroidJUnitRunner | tee "$out"
adb logcat -d -s ANDROID_RESOURCE:I >> /tmp/android-bench-logcat.txt || true

# `am instrument` exits 0 whatever the tests did; -1 is an error and -2 a failure in the status stream.
if grep -qE 'INSTRUMENTATION_STATUS_CODE: -[12]$' "$out" || ! grep -q '^INSTRUMENTATION_CODE: -1' "$out"; then
  echo "run-android-resource-benchmark: instrumentation reported failures or did not finish" >&2
  exit 1
fi
