#!/usr/bin/env bash
# Jules environment bootstrap for SteleKit (Kotlin Multiplatform).
#
# Paste the contents of this file into the Jules "Initial Setup" panel
# (Repo → Configuration → Initial Setup) and click "Run and Snapshot".
# The snapshot is reused for every Jules task on this repo.
#
# This script is also runnable locally to reproduce the Jules environment:
#   bash scripts/jules-setup.sh
#
# It is intentionally idempotent: re-running it on top of an existing snapshot
# should be a no-op except for the smoke tests at the end.
set -euo pipefail

log() { printf '\n\033[1;34m[jules-setup]\033[0m %s\n' "$*"; }

# ──────────────────────────────────────────────────────────────────────────────
# 1. JDK 21
# ──────────────────────────────────────────────────────────────────────────────
log "Ensuring JDK 21 is the active toolchain"

NEED_JDK_INSTALL=1
if command -v java >/dev/null 2>&1; then
  CURRENT_MAJOR=$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1)
  if [[ "${CURRENT_MAJOR:-0}" -ge 21 ]]; then
    NEED_JDK_INSTALL=0
  fi
fi

if [[ "$NEED_JDK_INSTALL" -eq 1 ]]; then
  if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update -y
    sudo apt-get install -y temurin-21-jdk || sudo apt-get install -y openjdk-21-jdk
  else
    echo "No supported package manager found; install Temurin 21 manually." >&2
    exit 1
  fi
fi

JAVA_HOME_CANDIDATE=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_CANDIDATE}"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

# ──────────────────────────────────────────────────────────────────────────────
# 2. Headless desktop deps (Compose Desktop tests render via Skia + AWT)
# ──────────────────────────────────────────────────────────────────────────────
log "Installing Xvfb for headless desktop tests"
if ! command -v xvfb-run >/dev/null 2>&1; then
  sudo apt-get install -y xvfb
fi

# ──────────────────────────────────────────────────────────────────────────────
# 3. Android SDK
# ──────────────────────────────────────────────────────────────────────────────
log "Setting up Android SDK"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/.android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

if [[ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  curl -fsSL -o "$TMP/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  (cd "$TMP" && unzip -q cmdline-tools.zip)
  mv "$TMP/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" >/dev/null

# ──────────────────────────────────────────────────────────────────────────────
# 4. Gradle warm-up + dependency pre-fetch
# ──────────────────────────────────────────────────────────────────────────────
log "Warming up Gradle (this populates ~/.gradle and is the slowest step)"
./gradlew --version
./gradlew help --no-daemon -q

# ──────────────────────────────────────────────────────────────────────────────
# 5. Smoke tests — mirror what `./gradlew ciCheck` runs in CI
# ──────────────────────────────────────────────────────────────────────────────
log "Running smoke tests (detekt + jvmTest + Android unit tests + assembleDebug)"
xvfb-run --auto-servernum ./gradlew \
  :kmp:detekt \
  :kmp:jvmTest \
  :kmp:testDebugUnitTest \
  :androidApp:assembleDebug \
  --no-daemon --build-cache

log "Jules environment ready. Click 'Run and Snapshot' to save."
