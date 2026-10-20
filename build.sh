#!/usr/bin/env bash
# Builds PatchFox.apk without Gradle: javac + d8 + aapt2 + apksigner.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${SDK:-$(cd .. && pwd)/sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/stubs"

echo "[1/6] Compiling Xposed API stubs..."
find stubs -name '*.java' > "$OUT/stubs.txt"
javac -source 17 -target 17 -cp "$PLATFORM" -d "$OUT/stubs" @"$OUT/stubs.txt" 2>/dev/null
jar cf "$OUT/xposed-stubs.jar" -C "$OUT/stubs" .

echo "[2/6] Compiling module sources..."
find src -name '*.java' > "$OUT/srcs.txt"
javac -source 17 -target 17 \
    -cp "$OUT/xposed-stubs.jar:$PLATFORM" \
    -d "$OUT/classes" @"$OUT/srcs.txt"

echo "[3/6] Dexing..."
"$BT/d8" --release \
    --lib "$PLATFORM" \
    --lib "$OUT/xposed-stubs.jar" \
    --output "$OUT" \
    $(find "$OUT/classes" -name '*.class')

echo "[4/6] Packaging resources..."
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link \
    -o "$OUT/unsigned.apk" \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 31 \
    --target-sdk-version 35 \
    --version-code 13 \
    --version-name 0.5.7 \
    -A assets \
    "$OUT/res.zip"

python3 -c "
import zipfile
with zipfile.ZipFile('$OUT/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('$OUT/classes.dex', 'classes.dex')
"

echo "[5/6] Aligning + signing..."
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f keystore.jks ]; then
    keytool -genkeypair -keystore keystore.jks -alias patchfox \
        -keyalg RSA -keysize 2048 -validity 3650 \
        -storepass android -keypass android \
        -dname "CN=PatchFox" >/dev/null 2>&1
fi

"$BT/apksigner" sign \
    --ks keystore.jks --ks-pass pass:android --key-pass pass:android \
    --out PatchFox.apk "$OUT/aligned.apk"

echo "[6/6] Done: $(pwd)/PatchFox.apk"
