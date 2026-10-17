#!/bin/bash
# Build the HnLauncher Motion Port Xposed module APK with gradle
# (AGP 9 + Kotlin + Jetpack Compose Material 3 settings UI).
# Output copied to out/HnLauncherPort.apk for compatibility with existing docs.
set -eu

PROJ="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
cd "$PROJ"

./gradlew assembleRelease

mkdir -p out
cp app/build/outputs/apk/release/app-release.apk out/HnLauncherPort.apk

echo "OK: $PROJ/out/HnLauncherPort.apk"
ls -la "$PROJ/out/HnLauncherPort.apk"
