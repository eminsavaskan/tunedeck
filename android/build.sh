#!/usr/bin/env bash
# YT Music Remote — Android APK build without Gradle (aapt2 + javac + d8 + apksigner)
# Works on Windows (git-bash) and Linux/macOS. No Android Studio, no AndroidX.
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"

# --- SDK / JDK -------------------------------------------------------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$ROOT/sdk}}"
if [ -z "${BT:-}" ]; then
  if [ -x "$SDK/build-tools/aapt2" ] || [ -x "$SDK/build-tools/aapt2.exe" ]; then
    BT="$SDK/build-tools"                                        # flattened SDK layout
  else
    BT="$(ls -d "$SDK"/build-tools/*.*/ 2>/dev/null | sort -V | tail -1)"; BT="${BT%/}"   # standard layout
  fi
fi
PLAT="${PLATFORM:-$(ls "$SDK"/platforms/android-*/android.jar "$SDK"/platform/android.jar 2>/dev/null | sort -V | tail -1)}"
if [ -n "${JAVA_HOME:-}" ]; then
  JDK="$JAVA_HOME"
elif [ -x "$SDK/jdk/bin/javac" ] || [ -x "$SDK/jdk/bin/javac.exe" ]; then
  JDK="$SDK/jdk"
else
  JDK="$(ls -d "$SDK"/jdk-*/ 2>/dev/null | sort -V | tail -1)"; JDK="${JDK%/}"
fi

[ -x "$BT/aapt2" ] || [ -x "$BT/aapt2.exe" ] || { echo "aapt2 bulunamadi (BT=$BT)"; exit 1; }
[ -f "$PLAT" ] || { echo "android.jar bulunamadi (PLAT=$PLAT)"; exit 1; }

EXE=""
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe";; esac
# native araclar (aapt2.exe, javac.exe) MSYS yollarini okuyamaz -> C:/... bicimine cevir
if [ -n "$EXE" ]; then
  ROOT="$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")"
  APP="$(cygpath -m "$APP" 2>/dev/null || echo "$APP")"
  OUT="$(cygpath -m "$OUT" 2>/dev/null || echo "$OUT")"
  BT="$(cygpath -m "$BT" 2>/dev/null || echo "$BT")"
  PLAT="$(cygpath -m "$PLAT" 2>/dev/null || echo "$PLAT")"
  JDK="$(cygpath -m "$JDK" 2>/dev/null || echo "$JDK")"
fi
D8="$BT/d8"; APKSIGN="$BT/apksigner"; KEYTOOL="keytool"
[ -n "$EXE" ] && { D8="$BT/d8.bat"; APKSIGN="$BT/apksigner.bat"; KEYTOOL="$JDK/bin/keytool.exe"; }
[ -x "$JDK/bin/javac" ] || [ -x "$JDK/bin/javac.exe" ] || { echo "javac bulunamadi (JDK=$JDK)"; exit 1; }
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
PY=""
for c in "${PYTHON:-}" python3 python; do
  [ -n "$c" ] || continue
  if "$c" -c "import sys" >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -n "$PY" ] || { echo "python bulunamadi (PYTHON=/yol/python ile ver)"; exit 1; }

echo "SDK : $SDK"
echo "BT  : $BT"
echo "PLAT: $PLAT"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# CI'da UPDATE_REPO verilirse APK'ya gomulen GitHub deposunu guncelle
if [ -n "${UPDATE_REPO:-}" ]; then
  API="$APP/src/com/emin/ytmremote/Api.java"
  sed -i "s|DEFAULT_UPDATE_REPO = \"[^\"]*\"|DEFAULT_UPDATE_REPO = \"$UPDATE_REPO\"|" "$API"
  echo "    guncelleme deposu: $UPDATE_REPO"
fi

echo "==> 1/6 kaynaklar (aapt2 compile)"
"$BT/aapt2$EXE" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "==> 2/6 baglama (aapt2 link)"
"$BT/aapt2$EXE" link \
  -o "$OUT/app-unsigned.apk" \
  -I "$PLAT" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --auto-add-overlay

echo "==> 3/6 java (javac)"
SRCS=$(find "$APP/src" -name "*.java")
"$JDK/bin/javac$EXE" -nowarn -source 17 -target 17 -encoding UTF-8 \
  -classpath "$PLAT" -d "$OUT/classes" \
  $SRCS "$OUT/gen/com/emin/ytmremote/R.java"

echo "==> 4/6 dex (d8)"
CLASSES=$(find "$OUT/classes" -name "*.class")
"$D8" --lib "$PLAT" --min-api 24 --output "$OUT/dex" $CLASSES

echo "==> 5/6 dex ekleme + hizalama"
cp "$OUT/app-unsigned.apk" "$OUT/app-dex.apk"
"$PY" - "$OUT/app-dex.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile, shutil
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
with zipfile.ZipFile(apk, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    zout.write(dex, "classes.dex")
shutil.move(tmp, apk)
print("   classes.dex eklendi")
PY
"$BT/zipalign$EXE" -f -p 4 "$OUT/app-dex.apk" "$OUT/app-aligned.apk"

echo "==> 6/6 imzalama (apksigner)"
KS="${KEYSTORE:-$ROOT/debug.keystore}"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias ytm -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=YT Music Remote, O=YT Music Remote, C=TR" >/dev/null 2>&1
  echo "   debug anahtari olusturuldu: $KS"
fi
APK="${APK_OUT:-$(dirname "$ROOT")/ytm-remote.apk}"
"$APKSIGN" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$APK" "$OUT/app-aligned.apk"
"$APKSIGN" verify --print-certs "$APK" | head -3

# --- surum bilgisi (telefondaki guncelleyici bunu okur)
MAN="$APP/AndroidManifest.xml"
VER="$(grep -o 'android:versionName="[^"]*"' "$MAN" | head -1 | sed 's/.*="//; s/"//')"
CODE="$(grep -o 'android:versionCode="[0-9]*"' "$MAN" | head -1 | sed 's/[^0-9]//g')"
SIZE="$(wc -c < "$APK" | tr -d ' ')"
SHA="$(sha256sum "$APK" | cut -c1-32)"
META="$(dirname "$ROOT")/version.json"
printf '{
  "version": "%s",
  "code": %s,
  "apk": "/app.apk",
  "size": %s,
  "sha256": "%s"
}
'   "$VER" "$CODE" "$SIZE" "$SHA" > "$META"
echo "surum dosyasi: $META  (v$VER / code $CODE)"

ls -la "$APK"
echo "TAMAM"
