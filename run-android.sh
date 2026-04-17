#!/bin/bash
set -e

# Ensure we are in the project root
if [ ! -d "android" ]; then
    echo "Error: Please run this script from the project root."
    exit 1
fi

echo "Building and installing Logseq Android App..."

# Build and install
cd android
./gradlew :app:installDebug

# Launch the app
echo "Launching app..."
# If ADB_DEVICE_ID is set, use it. Otherwise, let adb decide (which might fail if multiple devices are connected)
if [ -z "$ADB_DEVICE_ID" ]; then
    adb shell monkey -p dev.stapler.logseq.app -c android.intent.category.LAUNCHER 1
else
    adb -s "$ADB_DEVICE_ID" shell monkey -p dev.stapler.logseq.app -c android.intent.category.LAUNCHER 1
fi

echo "Done! App should be running on your connected device/emulator."
