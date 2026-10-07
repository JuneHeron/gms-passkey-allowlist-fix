#!/usr/bin/env bash
# 构建 GMS 通行密钥白名单修复模块（libxposed API 102）
#
# 依赖：JDK 17+（javac/jar/keytool）、bash、python3（或 python）
# 其余工具（build-tools / android.jar / libxposed API）会自动下载到 tools/ 目录。
#
# 用法：bash build.sh      # 产物：gms-allowlist-fix.apk
set -e
cd "$(dirname "$0")"

API_VERSION=102.0.0
PLAT_ZIP=platform-36-ext19_r01.zip
BASE=https://dl.google.com/android/repository

EXE=""
CPSEP=":"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) OS=windows; EXE=".exe"; CPSEP=";" ;;
  Darwin)               OS=macosx ;;
  *)                    OS=linux ;;
esac
BT_ZIP="build-tools_r34-$OS.zip"

PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "需要 python3 或 python" >&2; exit 1; }

TOOLS=tools
mkdir -p "$TOOLS"

fetch() { # url outfile
  [ -f "$2" ] && return 0
  echo "下载 $1"
  if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
  else echo "需要 curl 或 wget" >&2; exit 1; fi
}

extract() { # archive destdir
  "$PY" -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$1" "$2"
}

fetch "$BASE/$BT_ZIP"    "$TOOLS/$BT_ZIP"
fetch "$BASE/$PLAT_ZIP"  "$TOOLS/$PLAT_ZIP"
fetch "https://repo1.maven.org/maven2/io/github/libxposed/api/$API_VERSION/api-$API_VERSION.aar" "$TOOLS/api.aar"

# 解压后目录名随版本变化，这里自动定位
find_dir() { # 判定条件文件名
  local d
  for d in "$TOOLS"/*/; do
    [ -e "$d$1" ] && { echo "${d%/}"; return 0; }
  done
  return 1
}
BT_DIR="$(find_dir "aapt2$EXE" || true)"
[ -n "$BT_DIR" ] || { extract "$TOOLS/$BT_ZIP" "$TOOLS"; BT_DIR="$(find_dir "aapt2$EXE")"; }
PLAT_DIR="$(find_dir "android.jar" || true)"
[ -n "$PLAT_DIR" ] || { extract "$TOOLS/$PLAT_ZIP" "$TOOLS"; PLAT_DIR="$(find_dir "android.jar")"; }
[ -f "$TOOLS/classes.jar" ] || extract "$TOOLS/api.aar" "$TOOLS"

AAPT2="$BT_DIR/aapt2$EXE"
ZIPALIGN="$BT_DIR/zipalign$EXE"
D8="$BT_DIR/d8.bat"; APKSIGNER="$BT_DIR/apksigner.bat"
[ -n "$EXE" ] || { D8="$BT_DIR/d8"; APKSIGNER="$BT_DIR/apksigner"; }
ANDROID_JAR="$PLAT_DIR/android.jar"
API_JAR="$TOOLS/classes.jar"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== 编译 =="
find src -name '*.java' > "$OUT/src.txt"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$API_JAR$CPSEP$ANDROID_JAR" -d "$OUT/classes" @"$OUT/src.txt"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .

echo "== 生成 dex =="
"$D8" --lib "$ANDROID_JAR" --lib "$API_JAR" --min-api 26 --output "$OUT/dex" "$OUT/classes.jar"

echo "== 打包 =="
"$AAPT2" link -o "$OUT/unsigned.apk" --manifest AndroidManifest.xml \
  -I "$ANDROID_JAR" --min-sdk-version 26 --target-sdk-version 34

"$PY" - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" "$OUT/withdex.apk" <<'PY'
import sys, zipfile
apk, dex, out = sys.argv[1:4]
with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        if it.filename == "classes.dex" or it.filename.startswith("META-INF/xposed/"):
            continue
        zout.writestr(it, zin.read(it.filename))
    with open(dex, "rb") as f:
        zout.writestr("classes.dex", f.read())
    for name in ("module.prop", "scope.list", "java_init.list"):
        with open("META-INF/xposed/" + name, "rb") as f:
            zout.writestr("META-INF/xposed/" + name, f.read())
PY

if [ ! -f "$TOOLS/keystore.jks" ]; then
  keytool -genkeypair -keystore "$TOOLS/keystore.jks" -alias module -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass passkeyfix -keypass passkeyfix -dname "CN=GMSAllowlistFix" >/dev/null
fi

"$ZIPALIGN" -f -p 4 "$OUT/withdex.apk" "$OUT/aligned.apk"
"$APKSIGNER" sign --ks "$TOOLS/keystore.jks" --ks-pass pass:passkeyfix --key-pass pass:passkeyfix \
  --min-sdk-version 26 --out gms-allowlist-fix.apk "$OUT/aligned.apk"

ls -la gms-allowlist-fix.apk
echo "构建完成：$(pwd)/gms-allowlist-fix.apk"
