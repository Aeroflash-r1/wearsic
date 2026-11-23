package com.wearsic.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.wearsic.app.model.Track

enum class DownloadState {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(tableName = "downloads")
data class WearsicDownloadEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val durationMs: Long,
    val localFilePath: String,
    val originalStreamUrl: String,
    val downloadState: String, // from DownloadState enum
    val progress: Int = 0, // 0..100
    val fileSizeBytes: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val autoCached: Boolean = false,
    /**
     * TRUE only for a COMPLETED AUTO row whose deletion was REQUESTED while
     * its file was in use by playback (or racing a MANUAL promotion). The row
     * and file stay until the deletion is retried safely; persisted so the
     * intent survives process death. Never set for MANUAL rows.
     */
    @ColumnInfo(defaultValue = "0")
    val pendingDeletion: Boolean = false
    ) {
    fun toDomainTrack(): Track {
        return Track(
            id = trackId,
            title = title,
            artist = artist,
            album = album ?: "Unknown Album",
            durationMs = durationMs,
            mediaUri = localFilePath,
            artworkUrl = artworkUrl
        )
    }

    fun isCompleted(): Boolean = downloadState == DownloadState.COMPLETED.name
    fun isDownloading(): Boolean = downloadState == DownloadState.DOWNLOADING.name || downloadState == DownloadState.QUEUED.name
}
