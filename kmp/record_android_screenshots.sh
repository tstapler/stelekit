#!/usr/bin/env bash
set -euo pipefail
exec "${BUILD_WORKSPACE_DIRECTORY}/gradlew" :kmp:recordRoborazziDebug \
    --no-daemon --build-cache --project-dir "${BUILD_WORKSPACE_DIRECTORY}"
