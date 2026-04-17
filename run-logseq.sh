#!/bin/bash
# Logseq Launcher Script
# Sets up environment and runs the application

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export LD_LIBRARY_PATH="$SCRIPT_DIR/lib:$LD_LIBRARY_PATH"
export SKIKO_RENDERER=OPENGL

cd "$SCRIPT_DIR"
exec java -cp "lib/*:classes/*" dev.stapler.stelekit.MainKt "$@"
