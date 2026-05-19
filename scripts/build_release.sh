#!/usr/bin/env bash
# =============================================================================
# build_release.sh
# Builds a signed release APK.
# Prerequisites: JDK 17+, Android SDK, binaries fetched via fetch_binaries.sh
# =============================================================================
set -euo pipefail

echo "=== YTDownloader Release Build ==="
echo ""

# Check binaries are present
ASSETS="app/src/main/assets/bin"
for arch in arm64-v8a armeabi-v7a x86_64; do
  for bin in yt-dlp ffmpeg; do
    if [ ! -f "${ASSETS}/${arch}/${bin}" ]; then
      echo "ERROR: Missing ${ASSETS}/${arch}/${bin}"
      echo "Run: bash scripts/fetch_binaries.sh"
      exit 1
    fi
  done
done
echo "✓ Binaries present"

# Clean
echo "Cleaning..."
./gradlew clean

# Build release APK
echo "Building release APK..."
./gradlew assembleRelease

# Sign (requires keystore)
if [ -n "${KEYSTORE_PATH:-}" ]; then
  echo "Signing APK..."
  APK_UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
  APK_SIGNED="app/build/outputs/apk/release/YTDownloader-release.apk"

  "${ANDROID_HOME}/build-tools/34.0.0/apksigner" sign \
    --ks "${KEYSTORE_PATH}" \
    --ks-key-alias "${KEY_ALIAS:-key0}" \
    --ks-pass "pass:${KEYSTORE_PASS}" \
    --key-pass "pass:${KEY_PASS:-$KEYSTORE_PASS}" \
    --out "${APK_SIGNED}" \
    "${APK_UNSIGNED}"

  echo "✓ Signed APK: ${APK_SIGNED}"
else
  echo "Note: KEYSTORE_PATH not set — APK is unsigned (debug signing only)"
  echo "      For Play Store, set KEYSTORE_PATH, KEY_ALIAS, KEYSTORE_PASS, KEY_PASS"
fi

echo ""
echo "=== Build complete ==="
find app/build/outputs/apk -name "*.apk" -exec du -sh {} \;
