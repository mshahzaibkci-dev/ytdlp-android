package com.ytdownloader.app.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ytdownloader.app.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YtDlpManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    private val TAG = "YtDlpManager"
    private val binDir get() = File(context.filesDir, "bin").also { it.mkdirs() }
    private val ytDlpBinary get() = File(binDir, "yt-dlp")
    private val ffmpegBinary get() = File(binDir, "ffmpeg")

    // ─── Binary Setup ─────────────────────────────────────────────────────────

    suspend fun ensureBinariesReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            var ok = true

            // Try assets first (works when bundled in CI APK)
            if (!ytDlpBinary.exists() || ytDlpBinary.length() < 1_000_000L) {
                ok = tryExtractFromAssets("yt-dlp", ytDlpBinary)
                    || downloadBinary("yt-dlp", ytDlpBinary)
            }
            if (!ffmpegBinary.exists() || ffmpegBinary.length() < 1_000_000L) {
                // ffmpeg is optional - yt-dlp can work without it for non-merged formats
                tryExtractFromAssets("ffmpeg", ffmpegBinary)
                    || downloadBinary("ffmpeg", ffmpegBinary)
            }

            if (!ytDlpBinary.exists()) {
                Log.e(TAG, "yt-dlp binary missing after all attempts")
                return@withContext false
            }

            ytDlpBinary.setExecutable(true, false)
            if (ffmpegBinary.exists()) ffmpegBinary.setExecutable(true, false)

            Log.d(TAG, "yt-dlp ready: ${ytDlpBinary.absolutePath} (${ytDlpBinary.length()} bytes)")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "ensureBinariesReady failed", e)
            false
        }
    }

    private fun tryExtractFromAssets(name: String, dest: File): Boolean {
        return try {
            val arch = getDeviceArch()
            val assetPath = "bin/$arch/$name"
            context.assets.open(assetPath).use { input ->
                // Check it's a real binary (> 1MB), not a placeholder
                val available = input.available()
                if (available < 1_000_000) {
                    Log.d(TAG, "Asset $assetPath too small ($available bytes) — skipping")
                    return false
                }
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true, false)
            Log.d(TAG, "Extracted $name from assets (${dest.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.d(TAG, "Asset extraction failed for $name: ${e.message}")
            false
        }
    }

    private fun downloadBinary(name: String, dest: File): Boolean {
        val arch = getDeviceArch()
        val url = when (name) {
            "yt-dlp" -> when (arch) {
                "x86_64"      -> "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
                else          -> "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64"
            }
            "ffmpeg" -> when (arch) {
                "x86_64"      -> "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
                else          -> "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz"
            }
            else -> return false
        }

        return try {
            Log.d(TAG, "Downloading $name from $url")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed: HTTP ${conn.responseCode}")
                return false
            }

            if (name == "ffmpeg" && url.endsWith(".tar.xz")) {
                // Download tar then extract ffmpeg binary from it
                val tmpTar = File(binDir, "ffmpeg.tar.xz")
                FileOutputStream(tmpTar).use { out ->
                    conn.inputStream.copyTo(out)
                }
                conn.disconnect()
                extractFfmpegFromTar(tmpTar, dest)
                tmpTar.delete()
            } else {
                FileOutputStream(dest).use { out ->
                    conn.inputStream.copyTo(out)
                }
                conn.disconnect()
            }

            dest.setExecutable(true, false)
            Log.d(TAG, "Downloaded $name: ${dest.length()} bytes")
            dest.exists() && dest.length() > 1_000_000L
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $name", e)
            false
        }
    }

    private fun extractFfmpegFromTar(tarFile: File, dest: File): Boolean {
        return try {
            // Use system tar to extract
            val extractDir = File(binDir, "ffmpeg_extract").also { it.mkdirs() }
            val proc = ProcessBuilder(
                "tar", "-xf", tarFile.absolutePath,
                "-C", extractDir.absolutePath,
                "--strip-components=1",
                "--wildcards", "*/ffmpeg"
            ).start()
            proc.waitFor()

            val extracted = extractDir.walkTopDown()
                .firstOrNull { it.name == "ffmpeg" && it.isFile }
            if (extracted != null) {
                extracted.copyTo(dest, overwrite = true)
                extractDir.deleteRecursively()
                true
            } else {
                Log.e(TAG, "ffmpeg not found in tar archive")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tar extraction failed", e)
            false
        }
    }

    private fun getDeviceArch(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64")   -> "arm64-v8a"
            abi.startsWith("armeabi") -> "armeabi-v7a"
            abi.startsWith("x86_64")  -> "x86_64"
            abi.startsWith("x86")     -> "x86"
            else                      -> "arm64-v8a"
        }
    }

    fun areBinariesReady(): Boolean =
        ytDlpBinary.exists() && ytDlpBinary.canExecute() && ytDlpBinary.length() > 1_000_000L

    // ─── Video Info Fetching ──────────────────────────────────────────────────

    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                ytDlpBinary.absolutePath,
                "--dump-json",
                "--no-playlist",
                "--no-warnings",
                "--no-check-certificate",
                "--user-agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                url
            )
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                return@withContext Result.failure(Exception(parseError(stderr)))
            }

            Result.success(parseVideoInfo(stdout, url))
        } catch (e: Exception) {
            Log.e(TAG, "fetchVideoInfo failed", e)
            Result.failure(e)
        }
    }

    private fun parseVideoInfo(json: String, originalUrl: String): VideoInfo {
        val obj = gson.fromJson(json, JsonObject::class.java)

        val formats = mutableListOf<VideoFormat>()
        obj.getAsJsonArray("formats")?.forEach { el ->
            val fmt = el.asJsonObject
            val vcodec = fmt.get("vcodec")?.asString ?: "none"
            val acodec = fmt.get("acodec")?.asString ?: "none"
            val hasVideo = vcodec != "none" && vcodec.isNotBlank()
            val hasAudio = acodec != "none" && acodec.isNotBlank()
            val height = fmt.get("height")?.takeIf { !it.isJsonNull }?.asInt
            val width  = fmt.get("width")?.takeIf  { !it.isJsonNull }?.asInt
            val resolution = if (height != null && width != null) "${width}x${height}"
                             else if (height != null) "${height}p" else null

            formats.add(VideoFormat(
                formatId   = fmt.get("format_id")?.asString ?: "",
                formatNote = fmt.get("format_note")?.takeIf { !it.isJsonNull }?.asString,
                ext        = fmt.get("ext")?.asString ?: "mp4",
                resolution = resolution,
                width      = width,
                height     = height,
                filesize   = fmt.get("filesize")?.takeIf { !it.isJsonNull }?.asLong
                             ?: fmt.get("filesize_approx")?.takeIf { !it.isJsonNull }?.asLong,
                tbr        = fmt.get("tbr")?.takeIf { !it.isJsonNull }?.asDouble,
                vcodec     = if (hasVideo) vcodec else null,
                acodec     = if (hasAudio) acodec else null,
                hasVideo   = hasVideo,
                hasAudio   = hasAudio
            ))
        }

        return VideoInfo(
            id          = obj.get("id")?.asString ?: "",
            title       = obj.get("title")?.asString ?: "Unknown Title",
            description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString,
            duration    = obj.get("duration")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
            thumbnail   = obj.get("thumbnail")?.takeIf { !it.isJsonNull }?.asString,
            uploader    = obj.get("uploader")?.takeIf { !it.isJsonNull }?.asString,
            uploadDate  = obj.get("upload_date")?.takeIf { !it.isJsonNull }?.asString,
            viewCount   = obj.get("view_count")?.takeIf { !it.isJsonNull }?.asLong,
            formats     = buildQualityOptions(formats),
            url         = originalUrl
        )
    }

    private fun buildQualityOptions(raw: List<VideoFormat>): List<VideoFormat> {
        val presets = mutableListOf<VideoFormat>()
        val availableHeights = raw.filter { it.hasVideo }.mapNotNull { it.height }.toSet()

        listOf(2160, 1440, 1080, 720, 480, 360, 240).forEach { h ->
            if (availableHeights.any { it >= h } || h <= 720) {
                presets.add(VideoFormat(
                    formatId   = "bestvideo[height<=${h}]+bestaudio/best[height<=${h}]",
                    formatNote = "${h}p",
                    ext        = "mp4",
                    resolution = "${h}p",
                    width      = null, height = h, filesize = null, tbr = null,
                    vcodec     = "h264", acodec = "aac",
                    hasVideo   = true, hasAudio = true
                ))
            }
        }

        presets.add(VideoFormat(
            formatId = "bestaudio/best", formatNote = "Best Audio",
            ext = "mp3", resolution = null, width = null, height = null,
            filesize = null, tbr = null, vcodec = null, acodec = "mp3",
            hasVideo = false, hasAudio = true
        ))
        presets.add(VideoFormat(
            formatId = "bestaudio[ext=m4a]/bestaudio", formatNote = "M4A Audio",
            ext = "m4a", resolution = null, width = null, height = null,
            filesize = null, tbr = null, vcodec = null, acodec = "aac",
            hasVideo = false, hasAudio = true
        ))
        return presets
    }

    // ─── Download Args ────────────────────────────────────────────────────────

    fun buildDownloadArgs(
        url: String, formatSelector: String,
        outputPath: String, isAudioOnly: Boolean
    ): List<String> {
        val args = mutableListOf(
            ytDlpBinary.absolutePath,
            "--no-warnings", "--no-check-certificate",
            "--user-agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
            "--newline", "--progress",
            "--progress-template",
            "download:%(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s",
            "-f", formatSelector,
            "--retries", "3", "--fragment-retries", "3"
        )

        if (ffmpegBinary.exists() && ffmpegBinary.canExecute()) {
            args.addAll(listOf("--ffmpeg-location", ffmpegBinary.parent!!))
        }

        args.addAll(listOf("-o", outputPath))

        if (isAudioOnly) {
            args.addAll(listOf("-x", "--audio-format", "mp3", "--audio-quality", "0"))
        } else {
            args.addAll(listOf("--merge-output-format", "mp4"))
        }

        args.add(url)
        return args
    }

    fun parseProgressLine(line: String): DownloadProgressData? {
        if (!line.startsWith("download:")) return null
        val parts = line.removePrefix("download:").split("|")
        if (parts.size < 5) return null
        return try {
            val percent = parts[0].trim().removeSuffix("%").toFloatOrNull()?.toInt() ?: return null
            DownloadProgressData(
                percent          = percent,
                speedBytesPerSec = parseSpeed(parts[1].trim()),
                etaSeconds       = parseEta(parts[2].trim()),
                downloadedBytes  = parts[3].trim().toLongOrNull() ?: 0L,
                totalBytes       = parts[4].trim().toLongOrNull() ?: 0L
            )
        } catch (e: Exception) { null }
    }

    data class DownloadProgressData(
        val percent: Int, val speedBytesPerSec: Long,
        val etaSeconds: Long?, val downloadedBytes: Long, val totalBytes: Long
    )

    private fun parseSpeed(s: String): Long {
        if (s == "N/A" || s.isBlank()) return 0L
        return try {
            when {
                s.endsWith("GiB/s") -> (s.dropLast(5).trim().toDouble() * 1_073_741_824).toLong()
                s.endsWith("MiB/s") -> (s.dropLast(5).trim().toDouble() * 1_048_576).toLong()
                s.endsWith("KiB/s") -> (s.dropLast(5).trim().toDouble() * 1024).toLong()
                s.endsWith("B/s")   -> s.dropLast(3).trim().toLong()
                else -> 0L
            }
        } catch (e: Exception) { 0L }
    }

    private fun parseEta(s: String): Long? {
        if (s == "N/A" || s.isBlank() || s == "Unknown") return null
        return try {
            val p = s.split(":").map { it.toInt() }
            when (p.size) {
                1 -> p[0].toLong()
                2 -> (p[0] * 60 + p[1]).toLong()
                3 -> (p[0] * 3600 + p[1] * 60 + p[2]).toLong()
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun parseError(stderr: String): String = when {
        stderr.contains("Video unavailable")         -> "Video is unavailable or private"
        stderr.contains("age-restricted")            -> "Video is age-restricted"
        stderr.contains("not available in your country") -> "Video is geo-restricted"
        stderr.contains("copyright")                 -> "Video blocked due to copyright"
        stderr.contains("Invalid URL")               -> "Invalid YouTube URL"
        stderr.contains("Unable to extract")         -> "Unable to extract video. Try again."
        stderr.contains("HTTP Error 429")            -> "Too many requests. Please wait."
        stderr.contains("network") || stderr.contains("connection") -> "Network error. Check connection."
        stderr.isNotBlank() -> stderr.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "Unknown error"
        else -> "Failed to fetch video info"
    }

    fun getBinaryPath(): String = ytDlpBinary.absolutePath
    fun getFfmpegPath(): String = ffmpegBinary.absolutePath
}
