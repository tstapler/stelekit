#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "Building wasmJs distribution..."
./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true

DIST="$ROOT/kmp/build/dist/wasmJs/productionExecutable"
echo ""
echo "Starting local server at http://localhost:8787"
echo "Serving: $DIST"
DEMO_DIST="$DIST" node e2e/server.mjs
