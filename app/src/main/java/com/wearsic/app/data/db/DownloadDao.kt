package com.wearsic.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloadsFlow(): Flow<List<WearsicDownloadEntity>>

    @Query("SELECT * FROM downloads WHERE downloadState = 'COMPLETED' ORDER BY createdAt DESC")
    fun getCompletedDownloadsFlow(): Flow<List<WearsicDownloadEntity>>

    @Query("SELECT * FROM downloads WHERE downloadState = 'COMPLETED' AND autoCached = 1 ORDER BY createdAt DESC")
    suspend fun getAutoCachedCompleted(): List<WearsicDownloadEntity>

    @Query("SELECT * FROM downloads WHERE downloadState = 'COMPLETED' ORDER BY createdAt DESC")
    suspend fun getCompletedDownloads(): List<WearsicDownloadEntity>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId LIMIT 1")
    fun getDownloadFlowById(trackId: String): Flow<WearsicDownloadEntity?>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId LIMIT 1")
    suspend fun getDownloadById(trackId: String): WearsicDownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun getAllDownloads(): List<WearsicDownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: WearsicDownloadEntity)

    @Query("UPDATE downloads SET progress = :progress, downloadState = :state, fileSizeBytes = :fileSizeBytes WHERE trackId = :trackId")
    suspend fun updateProgress(trackId: String, progress: Int, state: String, fileSizeBytes: Long)

    @Query("UPDATE downloads SET downloadState = :state, errorMessage = :errorMessage WHERE trackId = :trackId")
    suspend fun updateState(trackId: String, state: String, errorMessage: String? = null)

    /** Completion write that also records the final ownership (AUTO vs MANUAL). */
    @Query("UPDATE downloads SET progress = 100, downloadState = 'COMPLETED', fileSizeBytes = :fileSizeBytes, autoCached = :autoCached WHERE trackId = :trackId")
    suspend fun markCompleted(trackId: String, fileSizeBytes: Long, autoCached: Boolean)

    /** Ownership flip (AUTO -> MANUAL promotion reuses the same physical file). */
    @Query("UPDATE downloads SET autoCached = :autoCached WHERE trackId = :trackId")
    suspend fun updateOwnership(trackId: String, autoCached: Boolean)

    /** Rows left QUEUED/DOWNLOADING by a killed process (no live job owns them). */
    @Query("SELECT * FROM downloads WHERE downloadState IN ('QUEUED', 'DOWNLOADING')")
    suspend fun getIncompleteDownloads(): List<WearsicDownloadEntity>

    /** Legacy rows from builds whose cancel path left the row behind. */
    @Query("SELECT * FROM downloads WHERE downloadState = 'CANCELLED'")
    suspend fun getCancelledDownloads(): List<WearsicDownloadEntity>

    /** Completed AUTO rows with a persisted deferred-deletion request. */
    @Query("SELECT * FROM downloads WHERE pendingDeletion = 1 AND autoCached = 1 AND downloadState = 'COMPLETED'")
    suspend fun getPendingDeletionDownloads(): List<WearsicDownloadEntity>

    /** Records or clears the deferred-deletion intent for a row. */
    @Query("UPDATE downloads SET pendingDeletion = :pending WHERE trackId = :trackId")
    suspend fun markDeletionPending(trackId: String, pending: Boolean)

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun deleteById(trackId: String)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}
