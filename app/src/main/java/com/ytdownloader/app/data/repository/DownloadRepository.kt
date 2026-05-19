package com.ytdownloader.app.data.repository

import com.ytdownloader.app.data.db.DownloadDao
import com.ytdownloader.app.data.model.*
import com.ytdownloader.app.util.YtDlpManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadDao,
    private val ytDlpManager: YtDlpManager
) {
    fun getAllDownloads(): Flow<List<DownloadRecord>> = dao.getAllDownloads()
    fun getActiveDownloads(): Flow<List<DownloadRecord>> = dao.getActiveDownloads()
    fun getCompletedDownloads(): Flow<List<DownloadRecord>> = dao.getCompletedDownloads()
    fun getFailedDownloads(): Flow<List<DownloadRecord>> = dao.getFailedDownloads()
    fun getActiveCount(): Flow<Int> = dao.getActiveCount()
    fun observeDownload(id: Long): Flow<DownloadRecord?> = dao.observeDownload(id)

    suspend fun getDownloadById(id: Long): DownloadRecord? = dao.getDownloadById(id)

    suspend fun createDownloadRecord(request: DownloadRequest): Long {
        val isAudio = !request.format!!.hasVideo
        val record = DownloadRecord(
            videoId = request.videoInfo.id,
            title = request.videoInfo.title,
            url = request.url,
            thumbnailUrl = request.videoInfo.thumbnail,
            formatId = request.format.formatId,
            qualityLabel = request.format.getDisplayName(),
            filePath = null,
            fileSize = 0L,
            duration = request.videoInfo.duration,
            status = DownloadStatus.QUEUED,
            progress = 0,
            errorMessage = null,
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            uploader = request.videoInfo.uploader
        )
        return dao.insertDownload(record)
    }

    suspend fun updateProgress(id: Long, progress: Int, status: DownloadStatus) {
        dao.updateProgress(id, progress, status)
    }

    suspend fun markCompleted(id: Long, filePath: String, fileSize: Long) {
        dao.updateDownloadResult(
            id = id,
            status = DownloadStatus.COMPLETED,
            progress = 100,
            filePath = filePath,
            fileSize = fileSize,
            completedAt = System.currentTimeMillis(),
            errorMessage = null
        )
    }

    suspend fun markFailed(id: Long, error: String) {
        dao.updateDownloadResult(
            id = id,
            status = DownloadStatus.FAILED,
            progress = 0,
            filePath = null,
            fileSize = 0L,
            completedAt = null,
            errorMessage = error
        )
    }

    suspend fun markCancelled(id: Long) {
        dao.updateDownloadResult(
            id = id,
            status = DownloadStatus.CANCELLED,
            progress = 0,
            filePath = null,
            fileSize = 0L,
            completedAt = null,
            errorMessage = "Cancelled by user"
        )
    }

    suspend fun deleteRecord(id: Long) = dao.deleteDownload(id)
    suspend fun clearCompleted() = dao.clearCompleted()
    suspend fun clearFailed() = dao.clearFailed()

    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> =
        ytDlpManager.fetchVideoInfo(url)

    suspend fun ensureBinariesReady(): Boolean =
        ytDlpManager.ensureBinariesReady()
}
