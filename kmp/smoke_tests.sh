#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${ADB_APK_PATH:-}" ]]; then
  echo "ERROR: ADB_APK_PATH must be set to the kmp instrumented test APK path." >&2
  echo "       Expected: kmp/build/outputs/apk/androidTest/debug/kmp-debug-androidTest.apk" >&2
  exit 1
fi

PACKAGE="dev.stapler.stelekit"
RUNNER="${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
TESTS="dev.stapler.stelekit.AppSmokeTest,dev.stapler.stelekit.SqliteCapabilityTest"
TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%S)

echo "Waiting for device..."
adb wait-for-device

echo "Installing APK: $ADB_APK_PATH"
adb install -r "$ADB_APK_PATH"

echo "Running smoke tests..."
set +e
result=$(adb shell am instrument -w -e class "$TESTS" "$RUNNER" 2>&1)
adb_exit=$?
set -e
echo "$result"

if echo "$result" | grep -qE "FAILURES|INSTRUMENTATION_ABORTED|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[12]"; then
  failed=true
  exit_code=1
elif [[ $adb_exit -ne 0 ]]; then
  echo "ERROR: adb shell am instrument exited with code $adb_exit (ADB/device error)" >&2
  failed=true
  exit_code=1
else
  failed=false
  exit_code=0
fi

if [[ -n "${XML_OUTPUT_FILE:-}" ]]; then
  if [[ "$failed" == "true" ]]; then
    failure_el_app='<failure message="smoke test failed">See adb instrument output</failure>'
    failure_el_sql='<failure message="smoke test failed">See adb instrument output</failure>'
  else
    failure_el_app=""
    failure_el_sql=""
  fi
  cat > "$XML_OUTPUT_FILE" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="AndroidSmokeTests" timestamp="${TIMESTAMP}" tests="2">
  <testcase classname="AppSmokeTest" name="smoke">${failure_el_app}</testcase>
  <testcase classname="SqliteCapabilityTest" name="smoke">${failure_el_sql}</testcase>
</testsuite>
EOF
fi

exit $exit_code
