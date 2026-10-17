#!/bin/bash
# Build clearctrl.jar (macOS host).
# Requires local Android SDK: build-tools/36.0.0 + platforms/android-34.
set -e
cd "$(dirname "$0")"
SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
rm -rf classes clearctrl.jar
mkdir -p classes
javac -source 8 -target 8 -bootclasspath "$PLATFORM" -d classes ClearCtrl.java 2>/dev/null
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output classes classes/ClearCtrl.class
jar cf clearctrl.jar -C classes classes.dex
echo "built: $(pwd)/clearctrl.jar"
