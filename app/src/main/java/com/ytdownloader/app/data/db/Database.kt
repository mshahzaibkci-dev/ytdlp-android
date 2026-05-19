package com.ytdownloader.app.data.db

import androidx.room.*
import com.ytdownloader.app.data.model.DownloadRecord
import com.ytdownloader.app.data.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

// ─── Type Converters ──────────────────────────────────────────────────────────

class Converters {
    @TypeConverter
    fun fromStatus(status: DownloadStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)
}

// ─── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','FETCHING_INFO','DOWNLOADING','MERGING') ORDER BY createdAt ASC")
    fun getActiveDownloads(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun getCompletedDownloads(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE status = 'FAILED' ORDER BY createdAt DESC")
    fun getFailedDownloads(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: Long): DownloadRecord?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeDownload(id: Long): Flow<DownloadRecord?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(record: DownloadRecord): Long

    @Update
    suspend fun updateDownload(record: DownloadRecord)

    @Query("UPDATE downloads SET progress = :progress, status = :status WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Int, status: DownloadStatus)

    @Query("""UPDATE downloads SET 
        status = :status, 
        progress = :progress, 
        filePath = :filePath, 
        fileSize = :fileSize, 
        completedAt = :completedAt, 
        errorMessage = :errorMessage
        WHERE id = :id""")
    suspend fun updateDownloadResult(
        id: Long,
        status: DownloadStatus,
        progress: Int,
        filePath: String?,
        fileSize: Long,
        completedAt: Long?,
        errorMessage: String?
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: Long)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("DELETE FROM downloads WHERE status IN ('FAILED', 'CANCELLED')")
    suspend fun clearFailed()

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED','FETCHING_INFO','DOWNLOADING','MERGING')")
    fun getActiveCount(): Flow<Int>
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [DownloadRecord::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
}
