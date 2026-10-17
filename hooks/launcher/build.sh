#!/bin/bash
# Build the HnLauncher Motion Port Xposed module APK without gradle.
# Uses SDK build-tools (aapt2/d8/zipalign/apksigner) + local debug keystore.
set -eu

PROJ="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
OUT="$PROJ/out"
KEYSTORE="$PROJ/debug.keystore"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/apk"

# 1) compile (against android.jar as bootclasspath)
find "$PROJ/src" -name '*.java' > "$OUT/sources.txt"
if ! javac -source 8 -target 8 -bootclasspath "$PLATFORM" \
    -d "$OUT/classes" -encoding UTF-8 -nowarn \
    -Xlint:none \
    @"$OUT/sources.txt"; then
    echo "ERROR: javac failed" >&2
    exit 1
fi

# 2) dex (Xposed API stubs must NOT ship in the APK; the host provides them)
rm -rf "$OUT/classes/de"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" \
    --output "$OUT/dex" "$OUT/classes.jar"

# 3) resources + manifest
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/apk/base.apk" \
    -I "$PLATFORM" \
    -A "$PROJ/assets" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 1 --version-name 0.1.0 \
    "$OUT/res.zip"

# 4) pack dex
(cd "$OUT/dex" && zip -q "$OUT/apk/base.apk" classes.dex)

# 5) align + sign
"$BT/zipalign" -f 4 "$OUT/apk/base.apk" "$OUT/apk/unsigned.apk"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias launcherport -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=HnLauncherPort,O=wxz,C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/HnLauncherPort.apk" "$OUT/apk/unsigned.apk"

"$BT/apksigner" verify "$OUT/HnLauncherPort.apk" >/dev/null
echo "OK: $OUT/HnLauncherPort.apk"
ls -la "$OUT/HnLauncherPort.apk"
