#!/usr/bin/env bash
set -euo pipefail
exec "${BUILD_WORKSPACE_DIRECTORY}/gradlew" :kmp:jvmTest \
    -Proborazzi.test.record=true \
    --no-daemon --build-cache \
    --project-dir "${BUILD_WORKSPACE_DIRECTORY}"
