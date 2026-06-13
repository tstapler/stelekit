#!/usr/bin/env bash
# Install a local dev build of SteleKit as stelekit-dev.app alongside the
# release version. Builds with -PbuildVariant=dev so the .app and bundle ID
# differ from the release and the two coexist in ~/Applications.
#
# Usage: ./scripts/install-dev.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# jpackage bundles whichever JVM runs Gradle. The build targets Java 21, so we
# need Java 21+ as the host JVM or the bundled runtime won't support the classes.
find_java21_home() {
  # Check Gradle's toolchain cache first (no sudo needed)
  local cached
  cached=$(find ~/.gradle/jdks -name "java" -path "*/bin/java" 2>/dev/null \
    | while read -r bin; do
        v=$("$bin" -version 2>&1 | awk -F '"' 'NR==1{print $2}' | cut -d. -f1)
        [[ "$v" -ge 21 ]] 2>/dev/null && echo "$bin" && break
      done)
  if [[ -n "$cached" ]]; then
    dirname "$(dirname "$cached")"
    return
  fi
  # Fall back to system JVMs
  /usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 22 2>/dev/null \
    || /usr/libexec/java_home -v 23 2>/dev/null || /usr/libexec/java_home -v 24 2>/dev/null
}

JAVA21_HOME=$(find_java21_home)
if [[ -z "$JAVA21_HOME" ]]; then
  echo "ERROR: Java 21+ not found. Install with: sudo brew install --cask corretto@21" >&2
  exit 1
fi
echo "==> Using JDK: $JAVA21_HOME"
export JAVA_HOME="$JAVA21_HOME"

# Stop any running daemon so the new JAVA_HOME takes effect (jpackage bundles
# the JVM of whichever daemon runs it; a Corretto-20 daemon would bundle 20).
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" --stop 2>/dev/null || true

echo "==> Building dev DMG..."
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" :kmp:packageDmg -PbuildVariant=dev --no-daemon

DMG=$(find "$REPO_ROOT/kmp/build/compose/binaries/main/dmg" -name "*-dev-*.dmg" | sort | tail -1)
if [[ -z "$DMG" ]]; then
  echo "ERROR: dev DMG not found after build" >&2
  exit 1
fi
echo "==> Built: $DMG"

MOUNT=/tmp/stelekit-dev-install
hdiutil attach "$DMG" -mountpoint "$MOUNT" -nobrowse -quiet

mkdir -p ~/Applications
rm -rf ~/Applications/stelekit-dev.app
cp -R "$MOUNT/stelekit-dev.app" ~/Applications/

hdiutil detach "$MOUNT" -quiet

xattr -rd com.apple.quarantine ~/Applications/stelekit-dev.app 2>/dev/null || true

echo "==> Installed: ~/Applications/stelekit-dev.app"
echo "    Launch: open ~/Applications/stelekit-dev.app"
