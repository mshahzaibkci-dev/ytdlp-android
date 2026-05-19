#!/usr/bin/env bash
# =============================================================================
# fetch_binaries.sh
# Downloads yt-dlp and ffmpeg binaries for all supported Android ABIs.
# Run this from the project root before building.
# =============================================================================
set -euo pipefail

ASSETS_BIN="app/src/main/assets/bin"

# ─── yt-dlp ──────────────────────────────────────────────────────────────────
echo "=== Fetching yt-dlp ==="

YTDLP_BASE="https://github.com/yt-dlp/yt-dlp/releases/latest/download"

curl -fL "${YTDLP_BASE}/yt-dlp_linux_aarch64" -o "${ASSETS_BIN}/arm64-v8a/yt-dlp"
chmod +x "${ASSETS_BIN}/arm64-v8a/yt-dlp"
echo "  ✓ arm64-v8a/yt-dlp"

curl -fL "${YTDLP_BASE}/yt-dlp_linux_armv7l" -o "${ASSETS_BIN}/armeabi-v7a/yt-dlp"
chmod +x "${ASSETS_BIN}/armeabi-v7a/yt-dlp"
echo "  ✓ armeabi-v7a/yt-dlp"

curl -fL "${YTDLP_BASE}/yt-dlp_linux" -o "${ASSETS_BIN}/x86_64/yt-dlp"
chmod +x "${ASSETS_BIN}/x86_64/yt-dlp"
echo "  ✓ x86_64/yt-dlp"

# ─── FFmpeg ───────────────────────────────────────────────────────────────────
# Source: ffmpeg-kit GitHub releases (maintained, reliable)
# https://github.com/arthenica/ffmpeg-kit/releases
echo ""
echo "=== Fetching FFmpeg ==="

FFKIT_VERSION="6.0"
FFKIT_BASE="https://github.com/arthenica/ffmpeg-kit/releases/download/v${FFKIT_VERSION}"

fetch_ffmpeg_from_ffkit() {
    local ABI="$1"
    local ABI_DIR="$2"
    local ZIP_FILE="/tmp/ffmpeg-kit-${ABI}.zip"
    local EXTRACT_DIR="/tmp/ffmpeg-kit-${ABI}"

    echo "[${ABI_DIR}] Downloading ffmpeg-kit zip (~30MB)..."
    curl -fL "${FFKIT_BASE}/ffmpeg-kit-min-${FFKIT_VERSION}-android-${ABI}.zip" \
         -o "${ZIP_FILE}"

    echo "[${ABI_DIR}] Extracting ffmpeg binary..."
    rm -rf "${EXTRACT_DIR}"
    mkdir -p "${EXTRACT_DIR}"
    unzip -q "${ZIP_FILE}" -d "${EXTRACT_DIR}"

    # The binary lives at: lib/<abi>/ffmpeg  OR  jni/<abi>/ffmpeg
    local FFMPEG_BIN
    FFMPEG_BIN=$(find "${EXTRACT_DIR}" -name "ffmpeg" -type f | head -1)

    if [ -z "${FFMPEG_BIN}" ]; then
        echo "  ! ffmpeg binary not found in zip, trying alternate path..."
        # Some releases use libffmpeg.so — we look for it
        FFMPEG_BIN=$(find "${EXTRACT_DIR}" -name "libffmpeg.so" -type f | head -1)
    fi

    if [ -n "${FFMPEG_BIN}" ]; then
        cp "${FFMPEG_BIN}" "${ASSETS_BIN}/${ABI_DIR}/ffmpeg"
        chmod +x "${ASSETS_BIN}/${ABI_DIR}/ffmpeg"
        echo "  ✓ ${ABI_DIR}/ffmpeg (from ffmpeg-kit)"
    else
        echo "  ! Could not extract ffmpeg from zip"
        return 1
    fi

    rm -f "${ZIP_FILE}"
    rm -rf "${EXTRACT_DIR}"
}

fetch_ffmpeg_termux() {
    local ABI_DIR="$1"
    local DEB_ARCH="$2"
    local TMP_DIR="/tmp/ffmpeg-termux-${DEB_ARCH}"

    echo "[${ABI_DIR}] Trying Termux package..."
    mkdir -p "${TMP_DIR}"

    # Termux package index
    local DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/f/ffmpeg"
    # Find latest deb filename
    local DEB_NAME
    DEB_NAME=$(curl -fsSL "${DEB_URL}/" 2>/dev/null | \
               grep -o "ffmpeg_[^\"]*_${DEB_ARCH}\.deb" | tail -1)

    if [ -z "${DEB_NAME}" ]; then
        echo "  ! Could not find Termux ffmpeg deb for ${DEB_ARCH}"
        return 1
    fi

    echo "  Downloading ${DEB_NAME}..."
    curl -fL "${DEB_URL}/${DEB_NAME}" -o "${TMP_DIR}/ffmpeg.deb"

    cd "${TMP_DIR}"
    ar x ffmpeg.deb
    # data.tar may be xz, gz, or zst
    if [ -f data.tar.xz ]; then
        tar xf data.tar.xz
    elif [ -f data.tar.gz ]; then
        tar xf data.tar.gz
    elif [ -f data.tar.zst ]; then
        zstd -d data.tar.zst -o data.tar && tar xf data.tar
    fi
    cd -

    local FFMPEG_BIN
    FFMPEG_BIN=$(find "${TMP_DIR}" -name "ffmpeg" -type f | head -1)

    if [ -n "${FFMPEG_BIN}" ]; then
        cp "${FFMPEG_BIN}" "${ASSETS_BIN}/${ABI_DIR}/ffmpeg"
        chmod +x "${ASSETS_BIN}/${ABI_DIR}/ffmpeg"
        echo "  ✓ ${ABI_DIR}/ffmpeg (from Termux)"
        rm -rf "${TMP_DIR}"
        return 0
    else
        rm -rf "${TMP_DIR}"
        return 1
    fi
}

fetch_ffmpeg_android_tools() {
    # Last-resort: static ffmpeg from fdossena's android-ffmpeg project
    local ABI_DIR="$1"
    local ARCH_SUFFIX="$2"

    echo "[${ABI_DIR}] Trying android-ffmpeg-static..."
    local URL="https://github.com/Netherdrake/android-ffmpeg-static/releases/latest/download/ffmpeg-${ARCH_SUFFIX}"

    if curl -fL "${URL}" -o "${ASSETS_BIN}/${ABI_DIR}/ffmpeg" 2>/dev/null; then
        chmod +x "${ASSETS_BIN}/${ABI_DIR}/ffmpeg"
        echo "  ✓ ${ABI_DIR}/ffmpeg (from android-ffmpeg-static)"
        return 0
    fi
    return 1
}

fetch_ffmpeg_for_abi() {
    local ABI_DIR="$1"
    local FFKIT_ABI="$2"
    local TERMUX_ARCH="$3"
    local STATIC_SUFFIX="$4"

    # Try ffmpeg-kit first (best quality, full-featured)
    if fetch_ffmpeg_from_ffkit "${FFKIT_ABI}" "${ABI_DIR}" 2>/dev/null; then
        return 0
    fi

    # Try Termux packages
    if fetch_ffmpeg_termux "${ABI_DIR}" "${TERMUX_ARCH}" 2>/dev/null; then
        return 0
    fi

    # Try static builds
    if fetch_ffmpeg_android_tools "${ABI_DIR}" "${STATIC_SUFFIX}" 2>/dev/null; then
        return 0
    fi

    echo "  ✗ All sources failed for ${ABI_DIR}/ffmpeg"
    echo "    Manual install: copy an ARM64 ffmpeg ELF binary to ${ASSETS_BIN}/${ABI_DIR}/ffmpeg"
    return 1
}

# arm64-v8a
fetch_ffmpeg_for_abi "arm64-v8a" "arm64-v8a" "aarch64" "arm64"

# armeabi-v7a — just copy arm64 binary; 64-bit binary won't run on 32-bit,
# but almost all devices since 2015 are 64-bit. For true 32-bit support,
# use a dedicated build.
if [ -f "${ASSETS_BIN}/arm64-v8a/ffmpeg" ]; then
    cp "${ASSETS_BIN}/arm64-v8a/ffmpeg" "${ASSETS_BIN}/armeabi-v7a/ffmpeg"
    echo "  ✓ armeabi-v7a/ffmpeg (copied from arm64)"
fi

# x86_64
if [ -f "${ASSETS_BIN}/arm64-v8a/ffmpeg" ]; then
    cp "${ASSETS_BIN}/arm64-v8a/ffmpeg" "${ASSETS_BIN}/x86_64/ffmpeg"
    echo "  ✓ x86_64/ffmpeg (copied from arm64 — for emulator use only)"
fi

# ─── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "=== Binary sizes ==="
for arch in arm64-v8a armeabi-v7a x86_64; do
    for bin in yt-dlp ffmpeg; do
        FILE="${ASSETS_BIN}/${arch}/${bin}"
        if [ -f "$FILE" ]; then
            SIZE=$(du -sh "$FILE" | cut -f1)
            echo "  ${arch}/${bin}: ${SIZE} ✓"
        else
            echo "  ${arch}/${bin}: MISSING ✗"
        fi
    done
done

echo ""
echo "=== Done! Build with: ./gradlew assembleDebug ==="
