#!/usr/bin/env bash
# Gradle/Android Studio 없이 APK 를 만드는 스크립트.
# 필요 패키지 (Ubuntu/Debian):
#   apt-get install android-sdk-platform-23 android-sdk-build-tools apksigner zipalign
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
ANDROID_JAR=$SDK/platforms/android-23/android.jar
DX=${DX:-$SDK/build-tools/debian/dx}
OUT=build
APK=dist/chm-gallery.apk

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" dist

echo "[1/5] 리소스 컴파일 (R.java)"
aapt package -f -m -J "$OUT/gen" -M app/AndroidManifest.xml -S app/res -I "$ANDROID_JAR"

echo "[2/5] Java 컴파일"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find app/src "$OUT/gen" -name '*.java')

echo "[3/5] DEX 변환"
"$DX" --dex --min-sdk-version=23 --output="$OUT/classes.dex" "$OUT/classes"

echo "[4/5] APK 패키징"
aapt package -f -M app/AndroidManifest.xml -S app/res -I "$ANDROID_JAR" -F "$OUT/app.unaligned.apk"
(cd "$OUT" && aapt add -f app.unaligned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/app.unaligned.apk" "$OUT/app.aligned.apk"

echo "[5/5] 서명"
apksigner sign --ks keystore/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$APK" "$OUT/app.aligned.apk"
apksigner verify "$APK"
echo "완료: $APK ($(du -h "$APK" | cut -f1))"
