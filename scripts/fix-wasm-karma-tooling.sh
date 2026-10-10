#!/usr/bin/env bash
# Fixes `./gradlew :kmp:wasmJsBrowserTest` failing with Karma plugin-resolution errors
# ("No provider for framework:mocha", "Cannot load webpack") on a machine that has real
# pnpm on PATH.
#
# Root cause: Kotlin Gradle Plugin's shared web-tooling installer
# (~/.kotlin/kotlin-npm-tooling/yarn/<hash>/) uses Yarn Berry with `nodeLinker: pnpm`,
# which produces an isolated, per-package node_modules store. Karma's own plugin
# auto-discovery (the 'karma-*' glob in its default config) scans relative to karma's
# own install location — under that isolated layout, karma's private node_modules
# contains only karma itself, so sibling packages like karma-mocha/karma-webpack are
# invisible to it even though they're correctly installed and resolvable from the
# project root. This is a known Karma / pnpm-style-linker incompatibility (Karma
# predates strict/isolated node_modules layouts).
#
# The fix switches Yarn's linker to the classic hoisted layout globally (via
# ~/.yarnrc.yml, Yarn Berry's documented mechanism for configuring installs that run
# outside any project — which is exactly what this shared tooling cache is), then
# forces Gradle to regenerate the tooling cache under the new linker.
#
# This is a machine-level fix, not something a project build script can safely apply
# automatically: ~/.yarnrc.yml is global, so silently mutating it from a Gradle task
# would also change the linker for any *other* Yarn Berry project on the same machine.
# Run this once per machine when you hit the Karma error above; it's idempotent.
#
# Usage: ./scripts/fix-wasm-karma-tooling.sh
set -euo pipefail

YARNRC="$HOME/.yarnrc.yml"

if [[ -f "$YARNRC" ]] && grep -q '^nodeLinker:' "$YARNRC"; then
  current=$(grep '^nodeLinker:' "$YARNRC" | head -1)
  if [[ "$current" == "nodeLinker: node-modules" ]]; then
    echo "==> ~/.yarnrc.yml already has nodeLinker: node-modules"
  else
    echo "WARNING: ~/.yarnrc.yml already sets '$current' — not overwriting." >&2
    echo "         Karma will keep failing under a pnpm/pnp linker. Edit $YARNRC" >&2
    echo "         to 'nodeLinker: node-modules' manually if you want this fix." >&2
    exit 1
  fi
else
  echo "==> Writing nodeLinker: node-modules to $YARNRC"
  { [[ -f "$YARNRC" ]] && cat "$YARNRC"; echo "nodeLinker: node-modules"; } > "$YARNRC.tmp"
  mv "$YARNRC.tmp" "$YARNRC"
fi

TOOLING_DIR="$HOME/.kotlin/kotlin-npm-tooling"
if [[ -d "$TOOLING_DIR" ]]; then
  echo "==> Removing stale pnpm-linked tooling cache: $TOOLING_DIR"
  rm -rf "$TOOLING_DIR"
else
  echo "==> No existing tooling cache at $TOOLING_DIR (nothing to remove)"
fi

echo "==> Done. Next ./gradlew :kmp:wasmJsBrowserTest run will regenerate the cache under the hoisted linker."
