package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.WearsicDownloadRepository
import com.example.data.db.WearsicDownloadEntity
import com.example.media.download.WearsicDownloadManager
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel responsible for download management.
 * Handles download state, progress, and storage statistics.
 */
class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadRepository = WearsicDownloadRepository(application.applicationContext)
    private val downloadManager = WearsicDownloadManager(application.applicationContext, downloadRepository)

    val allDownloads: StateFlow<List<WearsicDownloadEntity>> = downloadManager.allDownloadsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedTracks: StateFlow<List<Track>> = downloadManager.completedTracksFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    data class StorageStats(
        val autoCount: Int = 0,
        val autoMb: Double = 0.0,
        val manualCount: Int = 0,
        val manualMb: Double = 0.0
    ) {
        val totalMb: Double get() = autoMb + manualMb
    }

    init {
        // Startup hygiene (once per process): drop rows/bytes left behind by a
        // killed process and retry any deferred AUTO deletions that were
        // persisted before the process died. Runs off the main thread because
        // it touches Room and the filesystem.
        viewModelScope.launch(Dispatchers.IO) {
            downloadManager.cleanupInterruptedDownloads()
            downloadManager.flushPendingDeletions()
        }
    }

    // ============================================================================
    // Download Operations
    // ============================================================================

    fun startDownload(track: Track) {
        downloadManager.startDownload(track)
    }

    fun startDownload(track: Track, autoCached: Boolean) {
        downloadManager.startDownload(track, autoCached)
    }

    fun cancelDownload(trackId: String) {
        downloadManager.cancelDownload(trackId)
    }

    fun deleteDownload(trackId: String) {
        downloadManager.deleteDownload(trackId)
    }

    fun clearAllDownloads() {
        downloadManager.clearAllDownloads()
    }

    fun clearAutoCachedDownloads() {
        downloadManager.clearAutoCachedDownloads()
    }

    fun flushPendingDeletions() {
        downloadManager.flushPendingDeletions()
    }

    // ============================================================================
    // Storage Management
    // ============================================================================

    fun refreshStorageStats() {
        viewModelScope.launch {
            val breakdown = withContext(Dispatchers.IO) {
                downloadRepository.computeLocalStorageBreakdown()
            }
            _storageStats.value = StorageStats(
                autoCount = breakdown.autoCount,
                autoMb = breakdown.autoBytes / (1024.0 * 1024.0),
                manualCount = breakdown.manualCount,
                manualMb = breakdown.manualBytes / (1024.0 * 1024.0)
            )
        }
    }

    fun isDownloading(trackId: String): Boolean {
        return downloadManager.isDownloading(trackId)
    }

    // ============================================================================
    // Auto-Cache Management
    // ============================================================================

    fun setMaxAutoCachedTracks(max: Int) {
        downloadManager.maxAutoCachedTracks = max.coerceIn(5, 200)
    }

    fun trimAutoCache() {
        downloadManager.trimAutoCache()
    }

    // ============================================================================
    // Lifecycle
    // ============================================================================

    override fun onCleared() {
        downloadManager.release()
        super.onCleared()
    }
}

