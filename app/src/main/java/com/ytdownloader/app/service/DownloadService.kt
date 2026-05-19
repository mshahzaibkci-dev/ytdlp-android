package com.ytdownloader.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ytdownloader.app.R
import com.ytdownloader.app.YTDownloaderApp
import com.ytdownloader.app.data.model.DownloadStatus
import com.ytdownloader.app.data.repository.DownloadRepository
import com.ytdownloader.app.ui.main.MainActivity
import com.ytdownloader.app.util.StorageHelper
import com.ytdownloader.app.util.YtDlpManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : LifecycleService() {

    @Inject lateinit var repository: DownloadRepository
    @Inject lateinit var ytDlpManager: YtDlpManager
    @Inject lateinit var storageHelper: StorageHelper

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    // Map of downloadId → running Process
    private val activeProcesses = mutableMapOf<Long, Process>()
    private val activeJobs = mutableMapOf<Long, Job>()

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_START = "com.ytdownloader.ACTION_START"
        const val ACTION_CANCEL = "com.ytdownloader.ACTION_CANCEL"
        const val ACTION_PROGRESS = "com.ytdownloader.ACTION_PROGRESS"

        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FORMAT_ID = "extra_format_id"
        const val EXTRA_IS_AUDIO = "extra_is_audio"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_THUMBNAIL = "extra_thumbnail"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_ETA = "extra_eta"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_ERROR = "extra_error"

        private const val NOTIFICATION_ID_SERVICE = 1001

        fun startDownload(
            context: Context,
            downloadId: Long,
            url: String,
            formatId: String,
            isAudio: Boolean,
            title: String,
            thumbnail: String?
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FORMAT_ID, formatId)
                putExtra(EXTRA_IS_AUDIO, isAudio)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_THUMBNAIL, thumbnail)
            }
            context.startForegroundService(intent)
        }

        fun cancelDownload(context: Context, downloadId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID_SERVICE, buildServiceNotification("Ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val formatId = intent.getStringExtra(EXTRA_FORMAT_ID) ?: return START_NOT_STICKY
                val isAudio = intent.getBooleanExtra(EXTRA_IS_AUDIO, false)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Download"
                val thumbnail = intent.getStringExtra(EXTRA_THUMBNAIL)
                if (downloadId != -1L) {
                    startDownloadJob(downloadId, url, formatId, isAudio, title)
                }
            }
            ACTION_CANCEL -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) cancelJob(downloadId)
            }
        }
        return START_STICKY
    }

    private fun startDownloadJob(
        downloadId: Long,
        url: String,
        formatId: String,
        isAudio: Boolean,
        title: String
    ) {
        val job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                repository.updateProgress(downloadId, 0, DownloadStatus.DOWNLOADING)
                broadcastProgress(downloadId, 0, 0L, null, DownloadStatus.DOWNLOADING, "Starting…")

                val ext = if (isAudio) "mp3" else "mp4"
                val outputPath = storageHelper.getOutputFilePath(title, ext)
                // Use yt-dlp output template for actual filename detection
                val outputTemplate = outputPath.removeSuffix(".$ext") + ".%(ext)s"

                val args = ytDlpManager.buildDownloadArgs(url, formatId, outputTemplate, isAudio)
                Log.d(TAG, "Running: ${args.joinToString(" ")}")

                val process = ProcessBuilder(args)
                    .redirectErrorStream(false)
                    .start()

                activeProcesses[downloadId] = process

                // Read stderr in background
                val stderrJob = launch {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        Log.w(TAG, "yt-dlp stderr: $line")
                    }
                }

                // Read stdout for progress
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.v(TAG, "yt-dlp: $line")
                    val progress = ytDlpManager.parseProgressLine(line)
                    if (progress != null) {
                        val status = if (line.contains("Merging") || line.contains("ffmpeg"))
                            DownloadStatus.MERGING else DownloadStatus.DOWNLOADING
                        lifecycleScope.launch(Dispatchers.IO) {
                            repository.updateProgress(downloadId, progress.percent, status)
                        }
                        broadcastProgress(
                            downloadId,
                            progress.percent,
                            progress.speedBytesPerSec,
                            progress.etaSeconds,
                            status,
                            null
                        )
                        updateProgressNotification(downloadId, title, progress.percent)
                    }
                }

                stderrJob.join()
                val exitCode = process.waitFor()
                activeProcesses.remove(downloadId)

                if (!isActive) {
                    // Was cancelled
                    repository.markCancelled(downloadId)
                    broadcastProgress(downloadId, 0, 0, null, DownloadStatus.CANCELLED, null)
                } else if (exitCode == 0) {
                    // Find the actual output file
                    val actualFile = findOutputFile(outputPath.removeSuffix(".$ext"), isAudio)
                    val finalPath = if (actualFile != null) {
                        storageHelper.publishToMediaStore(
                            actualFile.absolutePath,
                            title,
                            storageHelper.getMimeType(actualFile.extension),
                            isAudio
                        )
                    } else outputPath

                    val fileSize = if (actualFile?.exists() == true) actualFile.length()
                    else storageHelper.getFileSize(finalPath)

                    repository.markCompleted(downloadId, finalPath, fileSize)
                    broadcastProgress(downloadId, 100, 0, null, DownloadStatus.COMPLETED, null)
                    showCompletionNotification(downloadId, title)
                } else {
                    val error = "yt-dlp exited with code $exitCode"
                    repository.markFailed(downloadId, error)
                    broadcastProgress(downloadId, 0, 0, null, DownloadStatus.FAILED, error)
                    showErrorNotification(downloadId, title, error)
                }
            } catch (e: CancellationException) {
                activeProcesses[downloadId]?.destroyForcibly()
                activeProcesses.remove(downloadId)
                repository.markCancelled(downloadId)
                broadcastProgress(downloadId, 0, 0, null, DownloadStatus.CANCELLED, null)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                activeProcesses[downloadId]?.destroyForcibly()
                activeProcesses.remove(downloadId)
                val msg = e.message ?: "Unknown error"
                repository.markFailed(downloadId, msg)
                broadcastProgress(downloadId, 0, 0, null, DownloadStatus.FAILED, msg)
                showErrorNotification(downloadId, title, msg)
            } finally {
                activeJobs.remove(downloadId)
                if (activeJobs.isEmpty()) {
                    updateServiceNotification("All downloads complete")
                }
            }
        }
        activeJobs[downloadId] = job
        updateServiceNotification("Downloading (${ activeJobs.size } active)")
    }

    private fun cancelJob(downloadId: Long) {
        activeProcesses[downloadId]?.destroyForcibly()
        activeProcesses.remove(downloadId)
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        lifecycleScope.launch(Dispatchers.IO) {
            repository.markCancelled(downloadId)
        }
    }

    private fun findOutputFile(basePath: String, isAudio: Boolean): File? {
        val extensions = if (isAudio) listOf("mp3", "m4a", "opus", "aac", "webm")
        else listOf("mp4", "mkv", "webm", "mov")
        for (ext in extensions) {
            val f = File("$basePath.$ext")
            if (f.exists()) return f
        }
        // Also search by glob in parent dir
        val dir = File(basePath).parentFile ?: return null
        return dir.listFiles()?.firstOrNull { f ->
            f.nameWithoutExtension == File(basePath).name &&
                    (if (isAudio) listOf("mp3","m4a","opus","aac") else listOf("mp4","mkv","webm"))
                        .contains(f.extension.lowercase())
        }
    }

    // ─── Broadcasts ───────────────────────────────────────────────────────────

    private fun broadcastProgress(
        downloadId: Long,
        progress: Int,
        speedBps: Long,
        etaSecs: Long?,
        status: DownloadStatus,
        message: String?
    ) {
        val intent = Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_SPEED, speedBps)
            etaSecs?.let { putExtra(EXTRA_ETA, it) }
            putExtra(EXTRA_STATUS, status.name)
            message?.let { putExtra(EXTRA_ERROR, it) }
        }
        sendBroadcast(intent)
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun buildServiceNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, YTDownloaderApp.CHANNEL_DOWNLOAD)
            .setContentTitle("YT Downloader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        notificationManager.notify(NOTIFICATION_ID_SERVICE, buildServiceNotification(text))
    }

    private fun updateProgressNotification(id: Long, title: String, progress: Int) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, id.toInt(),
            Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DOWNLOAD_ID, id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, YTDownloaderApp.CHANNEL_DOWNLOAD)
            .setContentTitle(title)
            .setContentText("Downloading… $progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        notificationManager.notify((NOTIFICATION_ID_SERVICE + id).toInt(), notification)
    }

    private fun showCompletionNotification(id: Long, title: String) {
        notificationManager.cancel((NOTIFICATION_ID_SERVICE + id).toInt())
        val notification = NotificationCompat.Builder(this, YTDownloaderApp.CHANNEL_COMPLETE)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        notificationManager.notify((2000 + id).toInt(), notification)
    }

    private fun showErrorNotification(id: Long, title: String, error: String) {
        notificationManager.cancel((NOTIFICATION_ID_SERVICE + id).toInt())
        val notification = NotificationCompat.Builder(this, YTDownloaderApp.CHANNEL_ERROR)
            .setContentTitle("Download failed")
            .setContentText("$title: $error")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        notificationManager.notify((3000 + id).toInt(), notification)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        activeProcesses.values.forEach { it.destroyForcibly() }
        activeProcesses.clear()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        super.onDestroy()
    }
}
