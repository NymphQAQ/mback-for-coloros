#!/usr/bin/env bash
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
OUT="app/build/local"
# Remove old build outputs, including legacy root-service bytecode.
rm -rf "$OUT/classes" "$OUT/dex" "$OUT/xposed-stubs"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/xposed-stubs"
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/resources.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" --manifest app/src/main/AndroidManifest.xml -A app/src/main/assets -I "$PLATFORM" "$OUT/resources.zip" --min-sdk-version 29 --target-sdk-version 35 --version-code 44 --version-name 0.8.4 --auto-add-overlay
find stubs -name '*.java' -print0 | xargs -0 javac -source 8 -target 8 -classpath "$PLATFORM" -d "$OUT/xposed-stubs"
find app/src/main/java -name '*.java' -print0 | xargs -0 javac -source 8 -target 8 -classpath "$PLATFORM:$OUT/xposed-stubs" -d "$OUT/classes"
"$BT/d8" --min-api 29 --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cd "$OUT/dex"
zip -q -u ../unsigned.apk classes.dex
cd ../../../..
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$OUT/mback-debug.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/mback-debug.apk"
echo "APK: $OUT/mback-debug.apk"
