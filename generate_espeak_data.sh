#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/app/src/main/assets/espeak-ng-data"
TMP="$ROOT/.tmp_espeak_data"
VERSION="1.52.0"

echo "=> 输出目录: $OUT"
mkdir -p "$OUT"
rm -rf "$TMP"
mkdir -p "$TMP"

# 1) 优先从本机（Homebrew）拷贝
CANDIDATES=(
  "/opt/homebrew/share/espeak-ng-data"
  "/usr/local/share/espeak-ng-data"
  "/usr/share/espeak-ng-data"
)
for P in "${CANDIDATES[@]}"; do
  if [[ -d "$P" ]]; then
    echo "=> 从本机拷贝: $P"
    rsync -a --delete "$P"/ "$OUT"/
    FOUND_LOCAL=1
    break
  fi
done

# 2) 若没有本机数据，尝试从 GitHub Releases 下载数据包
if [[ -z "${FOUND_LOCAL:-}" ]]; then
  echo "=> 本机未找到 espeak-ng-data，尝试下载预编译数据..."
  TAR_URL="https://github.com/espeak-ng/espeak-ng/releases/download/$VERSION/espeak-ng-data-$VERSION.tar.gz"
  ZIP_URL="https://github.com/espeak-ng/espeak-ng/releases/download/$VERSION/espeak-ng-data-$VERSION.zip"

  DOWNLOAD_OK=0
  if command -v curl >/dev/null 2>&1; then
    echo "   - 使用 curl 下载 tar.gz"
    if curl -fL "$TAR_URL" -o "$TMP/espeak-ng-data.tar.gz"; then
      tar -xzf "$TMP/espeak-ng-data.tar.gz" -C "$TMP"
      DOWNLOAD_OK=1
    fi
  fi

  if [[ $DOWNLOAD_OK -eq 0 ]] && command -v curl >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
    echo "   - 使用 curl+unzip 下载 zip"
    if curl -fL "$ZIP_URL" -o "$TMP/espeak-ng-data.zip"; then
      unzip -q "$TMP/espeak-ng-data.zip" -d "$TMP"
      DOWNLOAD_OK=1
    fi
  fi

  if [[ $DOWNLOAD_OK -eq 0 ]]; then
    echo "✖ 下载 espeak-ng-data 失败。请手动安装 espeak-ng 或将数据放入 $OUT"
    exit 1
  fi

  # 寻找解压后的数据目录
  SRC="$(find "$TMP" -maxdepth 2 -type d -name 'espeak-ng-data' | head -n 1 || true)"
  if [[ -z "$SRC" ]]; then
    # 某些包直接就是内容，无外层目录
    SRC="$TMP"
  fi

  echo "=> 同步数据到 $OUT"
  rsync -a --delete "$SRC"/ "$OUT"/
fi

# 3) 校验关键文件
if [[ ! -f "$OUT/phondata" ]]; then
  echo "✖ 缺少文件: $OUT/phondata"
  exit 2
fi
if [[ ! -d "$OUT/voices" ]]; then
  echo "✖ 缺少目录: $OUT/voices"
  exit 3
fi

VOICES_COUNT=$(find "$OUT/voices" -type f | wc -l | tr -d ' ')
PHON_SZ=$(stat -f%z "$OUT/phondata" 2>/dev/null || stat -c%s "$OUT/phondata" 2>/dev/null || echo "unknown")

echo "✅ espeak-ng-data 就绪"
echo "   phondata 大小: $PHON_SZ bytes"
echo "   voices 文件数: $VOICES_COUNT"

# 4) 清理临时目录
rm -rf "$TMP"

echo "🎉 完成。现在可以构建项目或同步 Gradle（preBuild 会验证该目录）。"
