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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YtDlpManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    private val TAG = "YtDlpManager"
    private val ytDlpBinary: File by lazy { File(context.filesDir, "bin/yt-dlp") }
    private val ffmpegBinary: File by lazy { File(context.filesDir, "bin/ffmpeg") }

    // ─── Binary Setup ─────────────────────────────────────────────────────────

    suspend fun ensureBinariesReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            val binDir = File(context.filesDir, "bin")
            if (!binDir.exists()) binDir.mkdirs()

            if (!ytDlpBinary.exists() || !ytDlpBinary.canExecute()) {
                extractBinary("yt-dlp", ytDlpBinary)
            }
            if (!ffmpegBinary.exists() || !ffmpegBinary.canExecute()) {
                extractBinary("ffmpeg", ffmpegBinary)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract binaries", e)
            false
        }
    }

    private fun extractBinary(assetName: String, dest: File) {
        val arch = getDeviceArch()
        val assetPath = "bin/$arch/$assetName"
        Log.d(TAG, "Extracting $assetPath → ${dest.absolutePath}")

        context.assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
        dest.setExecutable(true, false)
        Log.d(TAG, "Extracted $assetName successfully")
    }

    private fun getDeviceArch(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a"
        return when {
            abi.startsWith("arm64") -> "arm64-v8a"
            abi.startsWith("armeabi") -> "armeabi-v7a"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }

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
                val errorMsg = parseError(stderr)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val videoInfo = parseVideoInfo(stdout, url)
            Result.success(videoInfo)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch video info", e)
            Result.failure(e)
        }
    }

    private fun parseVideoInfo(json: String, originalUrl: String): VideoInfo {
        val obj = gson.fromJson(json, JsonObject::class.java)

        val formats = mutableListOf<VideoFormat>()

        obj.getAsJsonArray("formats")?.forEach { formatEl ->
            val fmt = formatEl.asJsonObject
            val vcodec = fmt.get("vcodec")?.asString ?: "none"
            val acodec = fmt.get("acodec")?.asString ?: "none"
            val hasVideo = vcodec != "none" && vcodec.isNotBlank()
            val hasAudio = acodec != "none" && acodec.isNotBlank()

            // Skip video-only formats with no audio unless they're the only option
            val height = fmt.get("height")?.takeIf { !it.isJsonNull }?.asInt
            val width = fmt.get("width")?.takeIf { !it.isJsonNull }?.asInt

            val resolution = if (height != null && width != null) "${width}x${height}"
            else if (height != null) "${height}p"
            else null

            formats.add(
                VideoFormat(
                    formatId = fmt.get("format_id")?.asString ?: "",
                    formatNote = fmt.get("format_note")?.takeIf { !it.isJsonNull }?.asString,
                    ext = fmt.get("ext")?.asString ?: "mp4",
                    resolution = resolution,
                    width = width,
                    height = height,
                    filesize = fmt.get("filesize")?.takeIf { !it.isJsonNull }?.asLong
                        ?: fmt.get("filesize_approx")?.takeIf { !it.isJsonNull }?.asLong,
                    tbr = fmt.get("tbr")?.takeIf { !it.isJsonNull }?.asDouble,
                    vcodec = if (hasVideo) vcodec else null,
                    acodec = if (hasAudio) acodec else null,
                    hasVideo = hasVideo,
                    hasAudio = hasAudio
                )
            )
        }

        // Build combined quality options sorted by quality
        val combinedFormats = buildCombinedFormats(formats)

        return VideoInfo(
            id = obj.get("id")?.asString ?: "",
            title = obj.get("title")?.asString ?: "Unknown Title",
            description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString,
            duration = obj.get("duration")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
            thumbnail = obj.get("thumbnail")?.takeIf { !it.isJsonNull }?.asString
                ?: obj.getAsJsonArray("thumbnails")?.lastOrNull()?.asJsonObject?.get("url")?.asString,
            uploader = obj.get("uploader")?.takeIf { !it.isJsonNull }?.asString,
            uploadDate = obj.get("upload_date")?.takeIf { !it.isJsonNull }?.asString,
            viewCount = obj.get("view_count")?.takeIf { !it.isJsonNull }?.asLong,
            formats = combinedFormats,
            url = originalUrl
        )
    }

    private fun buildCombinedFormats(raw: List<VideoFormat>): List<VideoFormat> {
        // Add preset quality options as virtual formats
        val presets = mutableListOf<VideoFormat>()

        // Check available heights
        val videoHeights = raw.filter { it.hasVideo }.mapNotNull { it.height }.toSet()

        val heightOptions = listOf(2160, 1440, 1080, 720, 480, 360, 240)
        for (h in heightOptions) {
            if (videoHeights.any { it >= h } || h <= 720) {
                presets.add(
                    VideoFormat(
                        formatId = "bestvideo[height<=${h}]+bestaudio/best[height<=${h}]",
                        formatNote = "${h}p",
                        ext = "mp4",
                        resolution = "${h}p",
                        width = null,
                        height = h,
                        filesize = null,
                        tbr = null,
                        vcodec = "h264",
                        acodec = "aac",
                        hasVideo = true,
                        hasAudio = true
                    )
                )
            }
        }

        // Audio only options
        presets.add(
            VideoFormat(
                formatId = "bestaudio/best",
                formatNote = "Best Audio",
                ext = "mp3",
                resolution = null,
                width = null,
                height = null,
                filesize = null,
                tbr = null,
                vcodec = null,
                acodec = "mp3",
                hasVideo = false,
                hasAudio = true
            )
        )
        presets.add(
            VideoFormat(
                formatId = "bestaudio[ext=m4a]/bestaudio",
                formatNote = "M4A Audio",
                ext = "m4a",
                resolution = null,
                width = null,
                height = null,
                filesize = null,
                tbr = null,
                vcodec = null,
                acodec = "aac",
                hasVideo = false,
                hasAudio = true
            )
        )

        return presets
    }

    // ─── Download ─────────────────────────────────────────────────────────────

    fun buildDownloadArgs(
        url: String,
        formatSelector: String,
        outputPath: String,
        isAudioOnly: Boolean
    ): List<String> {
        val args = mutableListOf(
            ytDlpBinary.absolutePath,
            "--no-warnings",
            "--no-check-certificate",
            "--user-agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
            "--newline",                    // progress on new lines
            "--progress",
            "--progress-template", "download:%(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s",
            "-f", formatSelector,
            "--ffmpeg-location", ffmpegBinary.parent ?: context.filesDir.absolutePath,
            "-o", outputPath
        )

        if (isAudioOnly) {
            args.addAll(listOf("-x", "--audio-format", "mp3", "--audio-quality", "0"))
        } else {
            args.addAll(listOf("--merge-output-format", "mp4"))
        }

        // Retries
        args.addAll(listOf("--retries", "3", "--fragment-retries", "3"))

        args.add(url)
        return args
    }

    fun parseProgressLine(line: String): DownloadProgressData? {
        if (!line.startsWith("download:")) return null
        val parts = line.removePrefix("download:").split("|")
        if (parts.size < 5) return null

        return try {
            val percentStr = parts[0].trim().removeSuffix("%")
            val percent = percentStr.toFloatOrNull()?.toInt() ?: return null

            val speedStr = parts[1].trim()
            val speedBytes = parseSpeed(speedStr)

            val etaStr = parts[2].trim()
            val etaSecs = parseEta(etaStr)

            val downloadedBytes = parts[3].trim().toLongOrNull() ?: 0L
            val totalBytes = parts[4].trim().toLongOrNull() ?: 0L

            DownloadProgressData(percent, speedBytes, etaSecs, downloadedBytes, totalBytes)
        } catch (e: Exception) {
            null
        }
    }

    data class DownloadProgressData(
        val percent: Int,
        val speedBytesPerSec: Long,
        val etaSeconds: Long?,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    private fun parseSpeed(speedStr: String): Long {
        if (speedStr == "N/A" || speedStr.isBlank()) return 0L
        return try {
            when {
                speedStr.endsWith("GiB/s") -> (speedStr.dropLast(5).trim().toDouble() * 1_073_741_824).toLong()
                speedStr.endsWith("MiB/s") -> (speedStr.dropLast(5).trim().toDouble() * 1_048_576).toLong()
                speedStr.endsWith("KiB/s") -> (speedStr.dropLast(5).trim().toDouble() * 1024).toLong()
                speedStr.endsWith("B/s") -> speedStr.dropLast(3).trim().toLong()
                else -> 0L
            }
        } catch (e: Exception) { 0L }
    }

    private fun parseEta(etaStr: String): Long? {
        if (etaStr == "N/A" || etaStr.isBlank() || etaStr == "Unknown") return null
        return try {
            val parts = etaStr.split(":").map { it.toInt() }
            when (parts.size) {
                1 -> parts[0].toLong()
                2 -> (parts[0] * 60 + parts[1]).toLong()
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toLong()
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun parseError(stderr: String): String {
        return when {
            stderr.contains("Video unavailable") -> "Video is unavailable or private"
            stderr.contains("age-restricted") -> "Video is age-restricted"
            stderr.contains("geo-restricted") || stderr.contains("not available in your country") ->
                "Video is geo-restricted"
            stderr.contains("copyright") -> "Video is blocked due to copyright"
            stderr.contains("Invalid URL") -> "Invalid YouTube URL"
            stderr.contains("Unable to extract") -> "Unable to extract video info. Try again later."
            stderr.contains("HTTP Error 429") -> "Too many requests. Please wait and try again."
            stderr.contains("network") || stderr.contains("connection") ->
                "Network error. Check your connection."
            stderr.isNotBlank() -> stderr.lines().lastOrNull { it.isNotBlank() }
                ?.take(200) ?: "Unknown error occurred"
            else -> "Failed to fetch video info"
        }
    }

    fun getBinaryPath(): String = ytDlpBinary.absolutePath
    fun getFfmpegPath(): String = ffmpegBinary.absolutePath
    fun areBinariesReady(): Boolean = ytDlpBinary.exists() && ytDlpBinary.canExecute()
}
