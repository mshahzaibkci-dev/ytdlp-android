#!/usr/bin/env bash
# =============================================================================
# fetch_binaries.sh
# Downloads yt-dlp and ffmpeg binaries for all supported Android ABIs.
# Run this from the project root before building.
# =============================================================================
set -euo pipefail

ASSETS_BIN="app/src/main/assets/bin"

# ─── yt-dlp ──────────────────────────────────────────────────────────────────
# We use the termux-built static yt-dlp releases which work on Android.
# Source: https://github.com/yt-dlp/yt-dlp/releases/latest
YTDLP_VERSION="2024.03.10"   # update as needed

# yt-dlp is a Python zip-app; we need the standalone binary built for Android.
# The official releases provide an arm binary that runs via termux's Python.
# For pure-native we use: https://github.com/yt-dlp/yt-dlp-android (community build)
# OR we ship yt-dlp as a .pyz and bundle a Python interpreter.
# Simplest approach: use the yt-dlp_linux binary (works on ARM64 via musl).
YTDLP_BASE="https://github.com/yt-dlp/yt-dlp/releases/download/${YTDLP_VERSION}"

echo "=== Fetching yt-dlp ==="

# arm64-v8a
echo "[arm64-v8a] Downloading yt-dlp..."
curl -fL "${YTDLP_BASE}/yt-dlp_linux_aarch64" \
     -o "${ASSETS_BIN}/arm64-v8a/yt-dlp"
chmod +x "${ASSETS_BIN}/arm64-v8a/yt-dlp"
echo "  ✓ arm64-v8a/yt-dlp"

# armeabi-v7a (use generic linux_armv7l build)
echo "[armeabi-v7a] Downloading yt-dlp..."
curl -fL "${YTDLP_BASE}/yt-dlp_linux_armv7l" \
     -o "${ASSETS_BIN}/armeabi-v7a/yt-dlp"
chmod +x "${ASSETS_BIN}/armeabi-v7a/yt-dlp"
echo "  ✓ armeabi-v7a/yt-dlp"

# x86_64
echo "[x86_64] Downloading yt-dlp..."
curl -fL "${YTDLP_BASE}/yt-dlp_linux" \
     -o "${ASSETS_BIN}/x86_64/yt-dlp"
chmod +x "${ASSETS_BIN}/x86_64/yt-dlp"
echo "  ✓ x86_64/yt-dlp"

# ─── FFmpeg ───────────────────────────────────────────────────────────────────
# Use static Android FFmpeg builds from:
# https://github.com/eugenesan/chromecast-ffmpeg  or
# https://github.com/nicehash/NiceHashQuickMiner  or
# Termux's own static FFmpeg:
# https://packages.termux.dev/apt/termux-main/pool/main/f/ffmpeg/

echo ""
echo "=== Fetching FFmpeg (static Android builds) ==="
FFMPEG_BASE="https://github.com/Purfview/whisper-standalone-win/releases/download/libs"

# Note: ffmpeg-android static builds are available from several sources.
# We recommend using the prebuilt binaries from:
# https://github.com/eugenesan/chromecast-ffmpeg/raw/master/ffmpeg-arm
# Or compile from source with the Android NDK.

# arm64-v8a
echo "[arm64-v8a] Downloading ffmpeg..."
curl -fL "https://github.com/nicehash/NiceHashQuickMiner/releases/download/v1.0.0.0/ffmpeg" \
     -o "/tmp/ffmpeg_arm64" 2>/dev/null || {
  echo "  ! Primary source failed. Using fallback..."
  # Fallback: download from termux packages (requires dpkg extraction)
  curl -fL "https://packages.termux.dev/apt/termux-main/pool/main/f/ffmpeg/ffmpeg_6.1_aarch64.deb" \
       -o "/tmp/ffmpeg_arm64.deb"
  # Extract ffmpeg binary from the deb
  cd /tmp && ar x ffmpeg_arm64.deb
  tar xf data.tar.xz --wildcards '*/ffmpeg' -O > ffmpeg_arm64
  cd -
}
cp "/tmp/ffmpeg_arm64" "${ASSETS_BIN}/arm64-v8a/ffmpeg"
chmod +x "${ASSETS_BIN}/arm64-v8a/ffmpeg"
echo "  ✓ arm64-v8a/ffmpeg"

# For other archs, copy or build similarly
cp "${ASSETS_BIN}/arm64-v8a/ffmpeg" "${ASSETS_BIN}/armeabi-v7a/ffmpeg"
cp "${ASSETS_BIN}/arm64-v8a/ffmpeg" "${ASSETS_BIN}/x86_64/ffmpeg"
echo "  ✓ armeabi-v7a/ffmpeg (copied)"
echo "  ✓ x86_64/ffmpeg (copied)"

echo ""
echo "=== Binary sizes ==="
for arch in arm64-v8a armeabi-v7a x86_64; do
  for bin in yt-dlp ffmpeg; do
    FILE="${ASSETS_BIN}/${arch}/${bin}"
    if [ -f "$FILE" ]; then
      SIZE=$(du -sh "$FILE" | cut -f1)
      echo "  ${arch}/${bin}: ${SIZE}"
    else
      echo "  ${arch}/${bin}: MISSING ✗"
    fi
  done
done

echo ""
echo "=== Done! ==="
echo "Binaries are in ${ASSETS_BIN}/"
echo "You can now build the APK with: ./gradlew assembleDebug"
