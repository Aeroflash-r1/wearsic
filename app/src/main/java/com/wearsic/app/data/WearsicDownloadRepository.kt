package com.wearsic.app.data

import android.content.Context
import com.wearsic.app.data.db.DownloadDao
import com.wearsic.app.data.db.DownloadState
import com.wearsic.app.data.db.WearsicDatabase
import com.wearsic.app.data.db.WearsicDownloadEntity
import com.wearsic.app.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File

class WearsicDownloadRepository(
    private val context: Context,
    private val downloadDao: DownloadDao = WearsicDatabase.getInstance(context).downloadDao()
) {

    val allDownloadsFlow: Flow<List<WearsicDownloadEntity>> = downloadDao.getAllDownloadsFlow()

    // File existence checks are disk IO; keep them off the collector (main) thread.
    val completedTracksFlow: Flow<List<Track>> = downloadDao.getCompletedDownloadsFlow().map { entities ->
        entities.filter { entity ->
            val file = File(entity.localFilePath)
            file.exists() && file.length() > 0
        }.map { it.toDomainTrack() }
    }.flowOn(Dispatchers.IO)

    fun getDownloadFlow(trackId: String): Flow<WearsicDownloadEntity?> {
        return downloadDao.getDownloadFlowById(trackId)
    }

    /** Raw row (any state) — single source of truth for ownership decisions. */
    suspend fun getDownloadEntity(trackId: String): WearsicDownloadEntity? {
        return downloadDao.getDownloadById(trackId)
    }

    suspend fun getDownloadedTrack(trackId: String): Track? {
        val entity = getDownloadEntity(trackId) ?: return null
        if (entity.downloadState == DownloadState.COMPLETED.name) {
            val file = File(entity.localFilePath)
            if (file.exists() && file.length() > 0) {
                return entity.toDomainTrack()
            }
        }
        return null
    }

    suspend fun isTrackDownloaded(trackId: String): Boolean {
        return getDownloadedTrack(trackId) != null
    }

    /** Rows whose job died with the process (QUEUED/DOWNLOADING, no live job). */
    suspend fun getIncompleteDownloads(): List<WearsicDownloadEntity> {
        return downloadDao.getIncompleteDownloads()
    }

    /** Legacy rows from builds whose cancel path left the row behind. */
    suspend fun getCancelledDownloads(): List<WearsicDownloadEntity> {
        return downloadDao.getCancelledDownloads()
    }

    /** Completed AUTO rows whose deletion was deferred (file in use / promotion). */
    suspend fun getPendingDeletionDownloads(): List<WearsicDownloadEntity> {
        return downloadDao.getPendingDeletionDownloads()
    }

    /** Records or clears the persisted deferred-deletion intent for a row. */
    suspend fun markDeletionPending(trackId: String, pending: Boolean) {
        downloadDao.markDeletionPending(trackId, pending)
    }

    suspend fun recordQueued(track: Track, localFilePath: String, autoCached: Boolean = false) {
        val entity = WearsicDownloadEntity(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artworkUrl,
            durationMs = track.durationMs,
            localFilePath = localFilePath,
            originalStreamUrl = track.mediaUri,
            downloadState = DownloadState.QUEUED.name,
            progress = 0,
            fileSizeBytes = 0L,
            errorMessage = null,
            autoCached = autoCached
        )
        downloadDao.insertOrUpdate(entity)
    }

    suspend fun getAutoCachedCompleted(): List<WearsicDownloadEntity> {
        return downloadDao.getAutoCachedCompleted()
    }

    suspend fun updateProgress(trackId: String, progress: Int, sizeBytes: Long) {
        downloadDao.updateProgress(
            trackId = trackId,
            progress = progress.coerceIn(0, 100),
            state = DownloadState.DOWNLOADING.name,
            fileSizeBytes = sizeBytes
        )
    }

    /**
     * Marks the download COMPLETED and records its final ownership in the
     * same write. Only called AFTER the completed file exists on disk.
     */
    suspend fun markCompleted(trackId: String, sizeBytes: Long, autoCached: Boolean = false) {
        downloadDao.markCompleted(trackId, sizeBytes, autoCached)
    }

    /** AUTO -> MANUAL promotion: ownership flip only, the file is untouched. */
    suspend fun updateOwnership(trackId: String, autoCached: Boolean) {
        downloadDao.updateOwnership(trackId, autoCached)
    }

    suspend fun markFailed(trackId: String, error: String) {
        downloadDao.updateState(
            trackId = trackId,
            state = DownloadState.FAILED.name,
            errorMessage = error
        )
    }

    suspend fun deleteDownload(trackId: String) {
        val entity = downloadDao.getDownloadById(trackId)
        if (entity != null) {
            try {
                val file = File(entity.localFilePath)
                if (file.exists()) file.delete()
                val partFile = File("${entity.localFilePath}.part")
                if (partFile.exists()) partFile.delete()
            } catch (_: Exception) {}
            downloadDao.deleteById(trackId)
        }
    }

    suspend fun clearAllDownloads() {
        val all = downloadDao.getAllDownloads()
        for (entity in all) {
            try {
                val file = File(entity.localFilePath)
                if (file.exists()) file.delete()
                val partFile = File("${entity.localFilePath}.part")
                if (partFile.exists()) partFile.delete()
            } catch (_: Exception) {}
        }
        downloadDao.deleteAll()
    }

    /**
     * Storage accounting for the UI: physical bytes of COMPLETED files that
     * actually exist, partitioned by ownership. Every file appears exactly
     * once (auto XOR manual), so total = auto + manual with no double-count.
     */
    suspend fun computeLocalStorageBreakdown(): LocalStorageBreakdown {
        var autoCount = 0
        var autoBytes = 0L
        var manualCount = 0
        var manualBytes = 0L
        for (entity in downloadDao.getCompletedDownloads()) {
            val file = File(entity.localFilePath)
            if (!file.isFile || file.length() <= 0L) continue
            if (entity.autoCached) {
                autoCount++
                autoBytes += file.length()
            } else {
                manualCount++
                manualBytes += file.length()
            }
        }
        return LocalStorageBreakdown(autoCount, autoBytes, manualCount, manualBytes)
    }
}

data class LocalStorageBreakdown(
    val autoCount: Int = 0,
    val autoBytes: Long = 0L,
    val manualCount: Int = 0,
    val manualBytes: Long = 0L
)
