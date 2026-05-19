# YTDownloader — Complete Build Instructions

A production-grade Android YouTube downloader using yt-dlp + FFmpeg, built with Kotlin and Material Design.

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| JDK | 17+ | `brew install openjdk@17` / [Adoptium](https://adoptium.net) |
| Android Studio | Hedgehog 2023.1+ | [developer.android.com](https://developer.android.com/studio) |
| Android SDK | API 34 | Via Android Studio SDK Manager |
| Android NDK | 26+ | Via Android Studio SDK Manager (optional, for custom FFmpeg builds) |
| Git | Any | Pre-installed on macOS/Linux |
| curl | Any | Pre-installed on macOS/Linux |

---

## Step 1 — Clone / Open the Project

```bash
cd ytdlp-android
```

Open Android Studio → **File → Open** → select the `ytdlp-android` folder.

Android Studio will automatically:
- Download the Gradle wrapper
- Sync dependencies
- Index the project

If the Gradle wrapper JAR is missing, run:
```bash
gradle wrapper --gradle-version 8.4
```

---

## Step 2 — Download yt-dlp and FFmpeg Binaries

The app requires pre-compiled ARM/x86 binaries placed in:
```
app/src/main/assets/bin/
├── arm64-v8a/
│   ├── yt-dlp     ← 64-bit ARM (most modern Android phones)
│   └── ffmpeg     ← 64-bit ARM
├── armeabi-v7a/
│   ├── yt-dlp     ← 32-bit ARM (older phones)
│   └── ffmpeg
└── x86_64/
    ├── yt-dlp     ← x86_64 (emulators, some Chromebooks)
    └── ffmpeg
```

### Option A — Automated (Recommended)

```bash
bash scripts/fetch_binaries.sh
```

This script downloads yt-dlp from the [official GitHub releases](https://github.com/yt-dlp/yt-dlp/releases) and static FFmpeg builds.

### Option B — Manual Download

#### yt-dlp

Go to https://github.com/yt-dlp/yt-dlp/releases/latest and download:

| File | Destination |
|------|-------------|
| `yt-dlp_linux_aarch64` | `assets/bin/arm64-v8a/yt-dlp` |
| `yt-dlp_linux_armv7l`  | `assets/bin/armeabi-v7a/yt-dlp` |
| `yt-dlp_linux`         | `assets/bin/x86_64/yt-dlp` |

```bash
# Example for arm64
curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64 \
     -o app/src/main/assets/bin/arm64-v8a/yt-dlp
```

#### FFmpeg

**Option 1 — Termux static builds** (easiest):
```bash
# arm64 static ffmpeg from Termux repository
curl -L "https://packages.termux.dev/apt/termux-main/pool/main/f/ffmpeg/ffmpeg_6.1_aarch64.deb" \
     -o /tmp/ffmpeg.deb
mkdir -p /tmp/ffmpeg-extract && cd /tmp/ffmpeg-extract
ar x /tmp/ffmpeg.deb
tar xf data.tar.xz
# binary is at ./data/data/com.termux/files/usr/bin/ffmpeg
cp ./data/data/com.termux/files/usr/bin/ffmpeg \
   /PATH/TO/PROJECT/app/src/main/assets/bin/arm64-v8a/ffmpeg
```

**Option 2 — FFmpeg-Kit** (full Android build):
Download from https://github.com/arthenica/ffmpeg-kit/releases
Extract the arm64 `ffmpeg` binary.

**Option 3 — Build from source** (advanced):
```bash
git clone https://github.com/FFmpeg/FFmpeg.git
cd FFmpeg
# Set up Android NDK cross-compilation toolchain
./configure \
  --target-os=android \
  --arch=aarch64 \
  --enable-cross-compile \
  --cross-prefix=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33- \
  --sysroot=$NDK/sysroot \
  --disable-shared \
  --enable-static \
  --disable-doc \
  --disable-programs \
  --enable-program=ffmpeg \
  --disable-everything \
  --enable-decoder=h264,aac,mp3,vp9,av1,opus \
  --enable-encoder=aac,mp3 \
  --enable-muxer=mp4,mp3,matroska \
  --enable-demuxer=mov,mp4,matroska,webm \
  --enable-protocol=file,http,https
make -j$(nproc)
cp ffmpeg /PATH/TO/PROJECT/app/src/main/assets/bin/arm64-v8a/
```

### Verify Binaries

After placing binaries, verify:
```bash
ls -lh app/src/main/assets/bin/arm64-v8a/
# Should show:
# -rwxr-xr-x  yt-dlp   ~40MB
# -rwxr-xr-x  ffmpeg   ~30MB
```

> **Important:** Binaries must be executable ELF files, not shell scripts or Windows EXEs.

---

## Step 3 — Configure SDK Path

Create `local.properties` in the project root (Android Studio usually does this automatically):
```properties
sdk.dir=/Users/YOUR_NAME/Library/Android/sdk
# On Linux: sdk.dir=/home/YOUR_NAME/Android/Sdk
# On Windows: sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\sdk
```

---

## Step 4 — Build Debug APK

### Via Android Studio
1. Connect a device or start an emulator (API 26+)
2. Click **Run ▶** or press `Shift+F10`

### Via Command Line
```bash
# Debug APK (faster, no signing needed)
./gradlew assembleDebug

# APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## Step 5 — Install on Device

```bash
# Install via adb
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or with Gradle
./gradlew installDebug
```

---

## Step 6 — Build Release APK

### 6a — Create a Signing Keystore

```bash
keytool -genkey -v \
  -keystore ytdownloader-release.jks \
  -alias ytdownloader \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Keep this file safe — it's required for all future updates!

### 6b — Configure Signing in Gradle

Add to `app/build.gradle` under `android {}`:
```groovy
signingConfigs {
    release {
        storeFile     file('../ytdownloader-release.jks')
        storePassword System.getenv("KEYSTORE_PASS") ?: "your_store_password"
        keyAlias      "ytdownloader"
        keyPassword   System.getenv("KEY_PASS") ?: "your_key_password"
    }
}

buildTypes {
    release {
        signingConfig signingConfigs.release
        // ... existing config
    }
}
```

### 6c — Build & Sign

```bash
# Via script (handles signing automatically)
KEYSTORE_PATH=ytdownloader-release.jks \
KEYSTORE_PASS=your_store_password \
KEY_ALIAS=ytdownloader \
bash scripts/build_release.sh

# Or manually:
./gradlew assembleRelease

# APK location:
# app/build/outputs/apk/release/app-release.apk
```

### 6d — Verify Signature

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify \
  --verbose app/build/outputs/apk/release/app-release.apk
```

---

## Project Structure

```
ytdlp-android/
├── app/
│   ├── build.gradle                          # App-level Gradle config
│   ├── proguard-rules.pro                    # R8 minification rules
│   └── src/main/
│       ├── AndroidManifest.xml               # Permissions, activities, services
│       ├── assets/
│       │   └── bin/
│       │       ├── arm64-v8a/{yt-dlp,ffmpeg} ← YOU MUST PLACE THESE
│       │       ├── armeabi-v7a/{yt-dlp,ffmpeg}
│       │       └── x86_64/{yt-dlp,ffmpeg}
│       ├── java/com/ytdownloader/app/
│       │   ├── YTDownloaderApp.kt            # Application class, Hilt, channels
│       │   ├── data/
│       │   │   ├── db/Database.kt            # Room database, DAO
│       │   │   ├── model/Models.kt           # VideoInfo, DownloadRecord, etc.
│       │   │   └── repository/DownloadRepository.kt
│       │   ├── di/AppModule.kt               # Hilt DI module
│       │   ├── service/DownloadService.kt    # Foreground download service
│       │   ├── ui/
│       │   │   ├── main/
│       │   │   │   ├── MainActivity.kt       # Main screen
│       │   │   │   ├── MainViewModel.kt      # URL fetch, download dispatch
│       │   │   │   ├── DownloadBottomSheet.kt # Quality picker
│       │   │   │   ├── ActiveDownloadAdapter.kt
│       │   │   │   └── FormatAdapter.kt
│       │   │   └── history/
│       │   │       ├── HistoryActivity.kt
│       │   │       ├── HistoryViewModel.kt
│       │   │       └── HistoryAdapter.kt
│       │   └── util/
│       │       ├── YtDlpManager.kt           # Binary extraction, process management
│       │       └── StorageHelper.kt          # Android 10+ scoped storage
│       └── res/
│           ├── layout/                       # XML layouts
│           ├── values/                       # Colors, strings, themes, dimens
│           ├── drawable/                     # Vector icons, backgrounds
│           ├── menu/                         # Menu XML
│           └── xml/                          # FileProvider paths, backup rules
├── build.gradle                              # Root Gradle config
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
└── scripts/
    ├── fetch_binaries.sh                     # Download yt-dlp + ffmpeg
    └── build_release.sh                      # Release build + signing
```

---

## Architecture

```
UI Layer          ViewModel Layer        Data Layer           System Layer
─────────         ───────────────        ──────────           ────────────
MainActivity  ←→  MainViewModel    ←→   DownloadRepository  ←→  Room DB
HistoryActivity←→ HistoryViewModel ←→   YtDlpManager        ←→  yt-dlp process
BottomSheet                             StorageHelper        ←→  MediaStore
                                                             ←→  DownloadService
                                                             ←→  FFmpeg process
```

- **MVVM** architecture with StateFlow/SharedFlow
- **Hilt** for dependency injection
- **Room** for download history persistence
- **Foreground Service** for reliable background downloads
- **BroadcastReceiver** for real-time progress updates to UI
- **Coroutines** for async work

---

## Permissions Explained

| Permission | Why | Android Version |
|-----------|-----|----------------|
| `INTERNET` | Fetch video info, download | All |
| `WRITE_EXTERNAL_STORAGE` | Save to Downloads | ≤ API 28 |
| `READ_MEDIA_VIDEO` | Access downloaded videos | API 33+ |
| `READ_MEDIA_AUDIO` | Access downloaded audio | API 33+ |
| `POST_NOTIFICATIONS` | Download progress notifications | API 33+ |
| `FOREGROUND_SERVICE` | Keep download alive in background | All |
| `WAKE_LOCK` | Prevent CPU sleep during download | All |

Android 10+ uses **MediaStore API** — files go to `Downloads/YTDownloader/` and appear in the Files app.

---

## How yt-dlp Integration Works

1. **Binary extraction**: On first run, `YtDlpManager` copies the yt-dlp and ffmpeg ELF binaries from `assets/bin/<abi>/` to `filesDir/bin/` and sets them executable.

2. **Info fetch**: Runs:
   ```
   yt-dlp --dump-json --no-playlist <URL>
   ```
   Parses the JSON output into a `VideoInfo` object with all available formats.

3. **Download**: Runs:
   ```
   yt-dlp -f <format_selector> --newline --progress \
          --ffmpeg-location <path> -o <output_template> <URL>
   ```
   The `--newline` flag makes progress output line-by-line. Each line matching `download:` is parsed for `percent|speed|eta|bytes`.

4. **Merging**: When downloading video+audio separately (e.g. 1080p), yt-dlp automatically calls ffmpeg to merge them into MP4.

5. **Storage**: On Android 10+, the file is first written to `externalCacheDir`, then copied to `MediaStore` (visible in Files app), then the cache copy is deleted.

---

## Troubleshooting

### "yt-dlp binary not found"
- Run `scripts/fetch_binaries.sh` first
- Check the binary is in the correct ABI folder
- Ensure the file is an ELF executable: `file assets/bin/arm64-v8a/yt-dlp`

### "Video unavailable"
- The video may be private, age-restricted, or region-blocked
- Try with a VPN or different URL

### "Failed to merge" / No sound
- Ensure ffmpeg binary is present alongside yt-dlp
- Check the ffmpeg binary matches your device ABI

### Large APK size
- The binaries (yt-dlp ~40MB, ffmpeg ~30MB × 3 ABIs) make the APK large (~200MB)
- Use **APK Splits by ABI** to reduce size:
  ```groovy
  android {
      splits {
          abi {
              enable true
              reset()
              include 'arm64-v8a', 'armeabi-v7a', 'x86_64'
              universalApk false
          }
      }
  }
  ```
  This produces separate APKs (~80MB each) per architecture.

### Build error: "Missing class..."
- Run `./gradlew clean` then `./gradlew assembleDebug`
- Ensure all Hilt annotations are processed: check `kapt` is in plugins

---

## Updating yt-dlp

YouTube frequently changes its extraction. To update yt-dlp:

```bash
# Edit YTDLP_VERSION in scripts/fetch_binaries.sh, then:
bash scripts/fetch_binaries.sh

# Or add self-update support in the app:
# yt-dlp -U   (updates the binary in place)
```

You can also implement in-app updates by downloading the latest yt-dlp binary at runtime from GitHub's releases API.

---

## Legal Notice

This application is intended for **personal use only**. Downloading copyrighted content without permission may violate:
- YouTube's Terms of Service
- The Digital Millennium Copyright Act (DMCA)
- Local copyright laws

Users are responsible for ensuring compliance with applicable laws. This tool should only be used to download content you have rights to download (Creative Commons, your own content, public domain, etc.).
