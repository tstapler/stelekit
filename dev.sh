#!/bin/bash
echo "Starting SteleKit in Continuous Build Mode..."
echo "The application will automatically restart whenever you save a source file."
echo "Press Ctrl+C to stop."
echo ""
echo "NOTE: While this is running, the Gradle daemon is permanently busy."
echo "      Test runs in another terminal will each spawn a second daemon."
echo "      Stop dev.sh first if you want single-daemon operation."
echo ""

# Trap Ctrl+C to stop the daemon cleanly rather than killing it mid-build.
# An abrupt kill corrupts Kotlin incremental caches (.tab files), which
# prevents daemon reuse and forces a new daemon on every subsequent build.
trap 'echo ""; echo "Stopping..."; ./gradlew --stop 2>/dev/null; exit 0' INT TERM

./gradlew -t :kmp:runApp