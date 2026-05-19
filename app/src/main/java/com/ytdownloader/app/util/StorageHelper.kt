package com.ytdownloader.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val DOWNLOAD_DIR_NAME = "YTDownloader"
    }

    /**
     * Returns the output path for yt-dlp.
     * On Android 10+ this writes to app-specific external storage, then we
     * copy to MediaStore after completion for visibility in gallery.
     * On Android 9- writes directly to Downloads/YTDownloader.
     */
    fun getOutputFilePath(title: String, ext: String): String {
        val sanitized = sanitizeFilename(title)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Write to app-specific cache first, then move to MediaStore
            val cacheDir = File(context.externalCacheDir, "downloads").apply { mkdirs() }
            File(cacheDir, "$sanitized.$ext").absolutePath
        } else {
            // Legacy: write directly to Downloads
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val appDir = File(downloadsDir, DOWNLOAD_DIR_NAME).apply { mkdirs() }
            File(appDir, "$sanitized.$ext").absolutePath
        }
    }

    /**
     * On Android 10+, copies the file to MediaStore Downloads collection
     * so it appears in the Files/Downloads app and is accessible to other apps.
     * Returns the final MediaStore URI string or the original path.
     */
    fun publishToMediaStore(
        filePath: String,
        title: String,
        mimeType: String,
        isAudio: Boolean
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return filePath

        val file = File(filePath)
        if (!file.exists()) return filePath

        val collection = if (isAudio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val relativeDir = if (isAudio)
            "${Environment.DIRECTORY_MUSIC}/$DOWNLOAD_DIR_NAME"
        else
            "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_DIR_NAME"

        val values = ContentValues().apply {
            put(if (isAudio) MediaStore.Audio.Media.DISPLAY_NAME
                else MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(if (isAudio) MediaStore.Audio.Media.MIME_TYPE
                else MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(if (isAudio) MediaStore.Audio.Media.RELATIVE_PATH
                else MediaStore.Video.Media.RELATIVE_PATH, relativeDir)
            put(if (isAudio) MediaStore.Audio.Media.IS_PENDING
                else MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return filePath

        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            values.clear()
            values.put(if (isAudio) MediaStore.Audio.Media.IS_PENDING
                else MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            // Clean up the cache copy
            file.delete()

            return uri.toString()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return filePath
        }
    }

    fun getMimeType(ext: String): String = when (ext.lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(200)
    }

    fun getFileSize(path: String): Long {
        return File(path).takeIf { it.exists() }?.length() ?: 0L
    }

    /**
     * Returns the template path for yt-dlp output (using %(title)s etc.)
     */
    fun getOutputTemplate(isAudio: Boolean): String {
        val cacheDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(context.externalCacheDir, "downloads").apply { mkdirs() }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            File(downloadsDir, DOWNLOAD_DIR_NAME).apply { mkdirs() }
        }
        val ext = if (isAudio) ".%(ext)s" else ".%(ext)s"
        return File(cacheDir, "%(title)s_%(id)s$ext").absolutePath
    }
}
