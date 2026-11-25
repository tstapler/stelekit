#!/usr/bin/env bash
# Parses the js("...") snippets Kotlin/Wasm generates (stelekit-kmp.import-object.mjs) with node, so a snippet that
# is not a valid JavaScript expression (for example `js("if (x) y()")`, which becomes `(x) => if (x) y()`) fails here
# in seconds instead of in the production webpack step (`:kmp:wasmJsBrowserProductionWebpack`, ~5 min, CI only).
# Wrap statements in braces or write one expression. Usage: scripts/wasm-import-object-check.sh [--no-build]
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "${1:-}" != "--no-build" ]]; then
  ./gradlew :kmp:compileProductionExecutableKotlinWasmJsOptimize :kmp:wasmJsProductionExecutableCompileSync -PenableJs=true -q
fi
shopt -s nullglob
files=(kmp/build/compileSync/wasmJs/*/*Executable/{kotlin,optimized}/*.import-object.mjs)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "no import-object.mjs found; run without --no-build" >&2
  exit 2
fi
for f in "${files[@]}"; do
  node --check "$f"
  echo "ok: $f"
done
