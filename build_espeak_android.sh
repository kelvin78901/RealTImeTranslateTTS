#!/usr/bin/env bash
set -euo pipefail

# ========= config =========
API=26
REPO_URL="https://github.com/espeak-ng/espeak-ng.git"
ESPEAK_DIR="third_party/espeak-ng"
BUILD_DIR_BASE="build-espeak"
APP_CPP_DIR="app/src/main/cpp"
INCLUDE_DST="$APP_CPP_DIR/include/espeak-ng"
LIB_DST_BASE="$APP_CPP_DIR/lib"

# 默认同时构建 arm64-v8a 和 armeabi-v7a；如只想 arm64，在下方改 ABIS=("arm64-v8a")
ABIS=("arm64-v8a" "armeabi-v7a")
# =========================

echo "==> Detect NDK"
: "${ANDROID_NDK_HOME:=${ANDROID_NDK:-}}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  # 常见默认路径（Android Studio）
  if [[ -d "$HOME/Library/Android/sdk/ndk" ]]; then
    # 取最新版本目录
    ANDROID_NDK_HOME="$(ls -td "$HOME/Library/Android/sdk/ndk"/* | head -n1)"
  fi
fi
if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "❌ 未找到 ANDROID_NDK_HOME，请先安装 NDK 并设置环境变量"
  exit 1
fi
echo "✅ NDK: $ANDROID_NDK_HOME"

echo "==> Check Ninja"
GEN="-G Ninja"
if ! command -v ninja >/dev/null 2>&1; then
  echo "⚠️ 未找到 ninja，将改用 Unix Makefiles（速度稍慢）"
  GEN="-G Unix Makefiles"
fi

echo "==> Prepare espeak-ng repo"
if [[ ! -d "$ESPEAK_DIR/.git" ]]; then
  mkdir -p "$(dirname "$ESPEAK_DIR")"
  git clone --depth=1 "$REPO_URL" "$ESPEAK_DIR"
else
  (cd "$ESPEAK_DIR" && git fetch --depth=1 && git reset --hard origin/master)
fi

echo "==> Prepare include"
# espeak-ng 的头文件在 src/include/espeak-ng/*
mkdir -p "$INCLUDE_DST"
rsync -av --delete "$ESPEAK_DIR/src/include/espeak-ng/" "$INCLUDE_DST/" >/dev/null
echo "✅ 头文件已同步到 $INCLUDE_DST"

build_one_abi () {
  local ABI="$1"
  local BDIR="${BUILD_DIR_BASE}-${ABI}"
  local LIB_DST="$LIB_DST_BASE/$ABI"

  echo "==> Configure [$ABI]"
  cmake -S "$ESPEAK_DIR" -B "$BDIR" \
    $GEN \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=$API \
    -DANDROID_STL=c++_static \
    -DANDROID_NDK="$ANDROID_NDK_HOME" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF

  echo "==> Build [$ABI]"
  cmake --build "$BDIR" -j"$(/usr/sbin/sysctl -n hw.ncpu 2>/dev/null || echo 4)"

  echo "==> Copy libs [$ABI]"
  mkdir -p "$LIB_DST"
  # 这些路径是 espeak-ng 官方 CMake 的默认产物位置
  cp "$BDIR/src/libespeak-ng/libespeak-ng.a" "$LIB_DST/"
  cp "$BDIR/src/speechPlayer/libspeechPlayer.a" "$LIB_DST/"
  cp "$BDIR/src/ucd-tools/libucd.a" "$LIB_DST/"

  echo "✅ libraries copied to $LIB_DST"
}

for abi in "${ABIS[@]}"; do
  build_one_abi "$abi"
done

echo "🎉 All done!"
echo "👉 现在可以直接在 Android Studio/Gradle 构建（或只保留需要的 ABI）"
