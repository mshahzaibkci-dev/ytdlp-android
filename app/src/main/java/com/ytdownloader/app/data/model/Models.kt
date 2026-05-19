package com.ytdownloader.app.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

// ─── Video Format ─────────────────────────────────────────────────────────────

@Parcelize
data class VideoFormat(
    val formatId: String,
    val formatNote: String?,
    val ext: String,
    val resolution: String?,
    val width: Int?,
    val height: Int?,
    val filesize: Long?,
    val tbr: Double?,
    val vcodec: String?,
    val acodec: String?,
    val hasVideo: Boolean,
    val hasAudio: Boolean
) : Parcelable {
    fun getDisplayName(): String = when {
        !hasVideo && hasAudio -> "Audio Only (${ext.uppercase()})"
        resolution != null    -> "$resolution (${ext.uppercase()})"
        height != null        -> "${height}p (${ext.uppercase()})"
        else                  -> formatNote ?: formatId
    }

    fun getFileSizeFormatted(): String? {
        filesize ?: return null
        return when {
            filesize >= 1_073_741_824 -> "%.1f GB".format(filesize / 1_073_741_824.0)
            filesize >= 1_048_576     -> "%.1f MB".format(filesize / 1_048_576.0)
            filesize >= 1_024         -> "%.1f KB".format(filesize / 1_024.0)
            else                      -> "$filesize B"
        }
    }
}

// ─── Video Info ───────────────────────────────────────────────────────────────

@Parcelize
data class VideoInfo(
    val id: String,
    val title: String,
    val description: String?,
    val duration: Long,
    val thumbnail: String?,
    val uploader: String?,
    val uploadDate: String?,
    val viewCount: Long?,
    val formats: List<VideoFormat>,
    val url: String
) : Parcelable {
    fun getDurationFormatted(): String {
        val h = duration / 3600
        val m = (duration % 3600) / 60
        val s = duration % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }
}

// ─── Quality Presets ──────────────────────────────────────────────────────────

enum class QualityPreset(val label: String, val formatSelector: String) {
    BEST_VIDEO("Best Quality",   "bestvideo+bestaudio/best"),
    VIDEO_1080P("1080p",         "bestvideo[height<=1080]+bestaudio/best[height<=1080]"),
    VIDEO_720P("720p",           "bestvideo[height<=720]+bestaudio/best[height<=720]"),
    VIDEO_480P("480p",           "bestvideo[height<=480]+bestaudio/best[height<=480]"),
    VIDEO_360P("360p",           "bestvideo[height<=360]+bestaudio/best[height<=360]"),
    AUDIO_ONLY_MP3("Audio MP3",  "bestaudio/best"),
    AUDIO_ONLY_M4A("Audio M4A",  "bestaudio[ext=m4a]/bestaudio/best"),
    CUSTOM("Custom Format",      "")
}

// ─── Download Record (Room Entity) ───────────────────────────────────────────

@Parcelize
@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String?,
    val formatId: String,
    val qualityLabel: String,
    val filePath: String?,
    val fileSize: Long,
    val duration: Long,
    val status: DownloadStatus,
    val progress: Int,
    val errorMessage: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val uploader: String?
) : Parcelable

enum class DownloadStatus {
    QUEUED, FETCHING_INFO, DOWNLOADING, MERGING, COMPLETED, FAILED, CANCELLED
}

// ─── Download Request ─────────────────────────────────────────────────────────

data class DownloadRequest(
    val url: String,
    val format: VideoFormat?,
    val qualityPreset: QualityPreset,
    val videoInfo: VideoInfo
)

// ─── UI State ─────────────────────────────────────────────────────────────────

sealed class UiState<out T> {
    object Idle    : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : UiState<Nothing>()
}

// ─── Download Progress ────────────────────────────────────────────────────────

data class DownloadProgress(
    val downloadId: Long,
    val progress: Int,
    val speedBytesPerSec: Long,
    val etaSeconds: Long?,
    val status: DownloadStatus,
    val message: String?
) {
    fun getSpeedFormatted(): String = when {
        speedBytesPerSec >= 1_048_576 -> "%.1f MB/s".format(speedBytesPerSec / 1_048_576.0)
        speedBytesPerSec >= 1_024     -> "%.0f KB/s".format(speedBytesPerSec / 1_024.0)
        else                          -> "$speedBytesPerSec B/s"
    }

    fun getEtaFormatted(): String? {
        etaSeconds ?: return null
        val m = etaSeconds / 60
        val s = etaSeconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
