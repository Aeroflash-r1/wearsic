package com.wearsic.app.media.download

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.wearsic.app.data.WearsicDownloadRepository
import com.wearsic.app.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext

/**
 * ONE-PHYSICAL-FILE-PER-TRACK download manager.
 *
 * Every unique track id maps to exactly one completed file:
 * `wearsic_downloads/<sanitized-trackId>.m4a`. Ownership (AUTO = evictable,
 * MANUAL = permanent) lives ONLY in the Room row's `autoCached` flag — never
 * in separate directories or duplicated files.
 *
 * Ownership transitions:
 *  - AUTO -> MANUAL ("Download" on an auto-cached song): metadata-only flip,
 *    the existing file is reused, 0 bytes written, no second HTTP request.
 *  - MANUAL -> AUTO (auto-cache on a manual song): refused — manual is never
 *    downgraded back to evictable.
 *  - Download while an AUTO download is in flight: the SAME job is upgraded so
 *    its completed file lands as MANUAL (no second download).
 *
 * Eviction (15/50/100-song AutoCache cap) only ever touches rows that are
 * still AUTO, not currently downloading and not the currently-playing local
 * file — re-checked under a mutex so a concurrent promotion cannot race a
 * delete. Manual downloads are never evicted.
 */
class WearsicDownloadManager(
    private val context: Context,
    private val repository: WearsicDownloadRepository = WearsicDownloadRepository(context),
    // Derived from the shared pool: longer read tolerance and NO whole-
    // call budget. OkHttp's callTimeout spans reading the response body,
    // so the base client's 45s cap would abort a multi-megabyte song
    // download partway through (a slow-but-steady watch connection keeps
    // each read well under 30s; stalled sockets fail fast so the
    // resumable download retries instead of hanging).
    private val okHttpClient: OkHttpClient = com.wearsic.app.network.WearsicHttp.client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Active download per track id. The value is mutable so a MANUAL request
     * can upgrade an in-flight AUTO job (the final row is then written MANUAL)
     * without ever launching a second HTTP request for the same track.
     */
    private class ActiveDownload(
        @Volatile var autoCached: Boolean,
        var job: Job? = null
    )

    private val activeJobs = ConcurrentHashMap<String, ActiveDownload>()

    /**
     * Track ids for which a MANUAL "Download" request was admitted but the
     * promotion write has not landed yet. Set SYNCHRONOUSLY on the calling
     * thread before any coroutine work, so eviction/Clear-auto that runs a
     * moment later treats the row as MANUAL-intent and never deletes the file
     * out from under a promotion. Cleared once the flip has been applied.
     */
    private val manualPromotionRequests = ConcurrentHashMap.newKeySet<String>()

    /**
     * Serializes every ownership decision + eviction delete. Downloading the
     * bytes happens OUTSIDE the lock; only DB transitions (queue, complete,
     * promote, evict) and the protected-file checks run inside, so eviction
     * can never delete a file whose row was promoted to MANUAL mid-eviction.
     *
     * DEFERRED DELETION is persisted on the row itself (`pendingDeletion`, see
     * the entity): a COMPLETED AUTO row whose deletion was requested while its
     * file was in use keeps the row + file and carries the flag, so the intent
     * survives process death and is deterministically discovered on restart.
     * There is deliberately NO separate in-memory registry to drift or lose.
     */
    private val stateMutex = Mutex()

    companion object {
        private const val DOWNLOAD_DIR_NAME = "wearsic_downloads"
        private const val MIN_REQUIRED_STORAGE_BYTES = 15L * 1024L * 1024L // 15 MB
        private const val MAX_CONCURRENT_DOWNLOADS = 2
    }

    /** How many auto-cached songs to keep; evicted oldest-first. Configurable
     *  from Settings ("Offline Audio"), synced by the ViewModel. Only AUTO
     *  songs count toward this — manual downloads are never evicted. */
    @Volatile
    var maxAutoCachedTracks: Int = 15

    /**
     * Called before evicting an AUTO file; return true for a track that must
     * not be deleted right now (e.g. it is the currently-playing local file).
     * Wired by the ViewModel to the playback controller.
     */
    @Volatile
    var isTrackProtected: (String) -> Boolean = { false }

    val allDownloadsFlow = repository.allDownloadsFlow
    val completedTracksFlow = repository.completedTracksFlow

    fun getDownloadDir(): File {
        return File(context.filesDir, DOWNLOAD_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    /** Deterministic, collision-safe physical path for a track (stable id). */
    private fun getTargetFile(trackId: String): File {
        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        return File(getDownloadDir(), "$sanitizedId.m4a")
    }

    private fun getPartFile(targetFile: File): File {
        return File(getDownloadDir(), "${targetFile.name}.part")
    }

    fun isDownloading(trackId: String): Boolean {
        return activeJobs.containsKey(trackId)
    }

    /**
     * Startup hygiene, invoked by the ViewModel once the manager exists:
     *
     * 1. QUEUED/DOWNLOADING rows with no live job (a killed process left them)
     *    are removed with their orphaned .part bytes, so the Downloads list
     *    never shows a frozen download and no dead partial file keeps
     *    consuming storage.
     * 2. Legacy CANCELLED rows (older builds whose cancel path left the row
     *    behind) are reconciled: a CANCELLED row that still has a real
     *    completed file is RESCUED to COMPLETED (its ownership flag is kept) —
     *    valid media is never deleted; a CANCELLED row with no file is dead
     *    and is removed along with any .part leftover.
     *
     * Idempotent and safe to run repeatedly. Every live download has already
     * registered its activeJobs entry (admission happens before the job body
     * can write a row), so a real download is never touched. AUTO, MANUAL,
     * COMPLETED, FAILED (retryable) and in-progress rows are never deleted.
     */
    suspend fun cleanupInterruptedDownloads() {
        try {
            stateMutex.withLock {
                // 1. Crashed-process leftovers.
                repository.getIncompleteDownloads().forEach { row ->
                    if (!activeJobs.containsKey(row.trackId)) {
                        repository.deleteDownload(row.trackId)
                    }
                }
                // 2. Legacy CANCELLED rows.
                repository.getCancelledDownloads().forEach { row ->
                    if (activeJobs.containsKey(row.trackId)) return@forEach
                    val file = File(row.localFilePath)
                    if (file.isFile && file.length() > 0L) {
                        // The full file exists (cancel/complete race in old
                        // builds): preserve it — the row becomes COMPLETED
                        // with its original ownership (AUTO/MANUAL) intact.
                        repository.markCompleted(row.trackId, file.length(), row.autoCached)
                    } else {
                        // Dead record: no media behind it. Remove row + part.
                        repository.deleteDownload(row.trackId)
                    }
                }
            }
        } catch (_: Exception) {
            // Startup cleanup is best-effort.
        }
    }

    /**
     * Requests a download for [track].
     *
     * [autoCached] describes the REQUEST's intent (true = AutoCache, false =
     * manual "Download"). The resulting file's ownership is decided as:
     *
     *  request      existing state                        action
     *  -----------  ------------------------------------  -------------------------
     *  MANUAL       already MANUAL (completed file)       no-op (no re-download)
     *  MANUAL       already AUTO (completed file)         PROMOTE: flag flip only
     *  MANUAL       AUTO download in flight               upgrade the same job
     *  MANUAL       nothing / incomplete row              new MANUAL download
     *  AUTO         already AUTO or MANUAL (file exists)  no-op (manual never downgraded)
     *  AUTO         nothing / incomplete row              new AUTO download
     */
    fun startDownload(track: Track, autoCached: Boolean = false) {
        val existing = activeJobs[track.id]
        if (existing != null) {
            if (!autoCached) {
                // Upgrade an in-flight AUTO job to MANUAL — and repair the row
                // if it already completed as AUTO before this request landed.
                applyManualPromotion(track.id)
            }
            return
        }
        if (activeJobs.size >= MAX_CONCURRENT_DOWNLOADS) return

        if (!autoCached) {
            // Record the promotion intent on the CALLING thread, before any
            // coroutine runs: an eviction/Clear-auto racing this tap must see
            // MANUAL intent for this track and defer/refuse the delete.
            manualPromotionRequests.add(track.id)
        }

        val holder = ActiveDownload(autoCached = autoCached)
        val targetFile = getTargetFile(track.id)
        val partFile = getPartFile(targetFile)
        // Register BEFORE launching the body: any QUEUED/DOWNLOADING row that
        // appears in the DB is then guaranteed to have this entry, so startup
        // cleanup can never mistake a live download for a crashed one.
        activeJobs[track.id] = holder

        val job = scope.launch {
            // Whether the completed file must be marked AUTO. Starts from the
            // request intent, but a MANUAL request arriving mid-download can
            // flip it (holder.autoCached) — the completion write reads it
            // under the state mutex so the flip cannot race the DB write.
            var effectiveAutoCached = autoCached

            try {
                stateMutex.withLock {
                    // 0. Ownership check BEFORE any network or disk work.
                    //    A completed file may already exist from an earlier
                    //    session (or finished while this job was queued).
                    val row = repository.getDownloadEntity(track.id)
                    val completedFile = row?.let { entity ->
                        if (entity.downloadState == "COMPLETED") {
                            val file = File(entity.localFilePath)
                            if (file.isFile && file.length() > 0L) file else null
                        } else null
                    }
                    if (completedFile != null) {
                        if (!autoCached && row!!.autoCached) {
                            // AUTO -> MANUAL promotion: reuse the file, flip
                            // ownership. 0 new bytes, 0 new HTTP requests. A
                            // persisted deferred deletion is cancelled — a
                            // MANUAL file is never evictable or deletable by
                            // auto cleanup.
                            repository.updateOwnership(track.id, autoCached = false)
                            repository.markDeletionPending(track.id, false)
                        }
                        // else: already MANUAL (manual request = no-op) or
                        // AUTO request on any completed file (manual is never
                        // downgraded). Nothing to do either way.
                        if (!autoCached) manualPromotionRequests.remove(track.id)
                        return@launch
                    }

                    // 1. Queue the row. If a COMPLETED row exists but its file
                    //    is gone (corrupted/evicted externally), re-download
                    //    WITHOUT losing the user's ownership intent: a MANUAL
                    //    track that lost its file still comes back MANUAL.
                    if (row?.downloadState == "COMPLETED") {
                        effectiveAutoCached = autoCached && row.autoCached
                    }
                    // A fresh download is queued (nothing to promote), so the
                    // eviction-guard intent is no longer needed.
                    if (!autoCached) manualPromotionRequests.remove(track.id)
                    repository.recordQueued(track, targetFile.absolutePath, effectiveAutoCached)
                }

                // 2. Storage safety check (manual downloads may never blindly
                //    consume the watch's storage).
                val stat = StatFs(getDownloadDir().path)
                val availableBytes = stat.availableBytes
                if (availableBytes < MIN_REQUIRED_STORAGE_BYTES) {
                    repository.markFailed(track.id, "Storage full (<15MB free)")
                    return@launch
                }

                // 3. Download (HTTP with resume, or local Android Resource)
                val uri = Uri.parse(track.mediaUri)
                val scheme = uri.scheme?.lowercase()

                if (scheme == "http" || scheme == "https") {
                    downloadWithResume(track, partFile)
                } else {
                    // Local resource/test stream
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open stream from $uri")
                    if (partFile.exists()) partFile.delete()
                    FileOutputStream(partFile).use { outputStream ->
                        inputStream.use { it.copyTo(outputStream, 8192) }
                    }
                }

                // 4. Atomic completion: rename temp -> final, only then mark
                //    COMPLETED in Room. A failed/cancelled download never
                //    leaves a COMPLETED record without a real file.
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    // Fallback copy if rename fails
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }

                stateMutex.withLock {
                    // Read the (possibly promoted mid-flight) ownership under
                    // the lock that promotion also uses.
                    val finalAuto = holder.autoCached && effectiveAutoCached
                    repository.markCompleted(track.id, targetFile.length(), finalAuto)
                    if (finalAuto) {
                        // Keep the auto-cache footprint within its cap.
                        evictLocked(maxAutoCachedTracks)
                    }
                }
            } catch (e: Exception) {
                if (partFile.exists()) partFile.delete()
                // A cancelled job must not scribble FAILED over the row the
                // cancel path is removing.
                if (coroutineContext.isActive) {
                    repository.markFailed(track.id, e.message ?: "Download failed")
                }
            } finally {
                activeJobs.remove(track.id)
            }
        }

        holder.job = job
    }

    /**
     * MANUAL request while a job is (or just finished) active: upgrade the
     * in-flight job and repair a row that already completed as AUTO.
     */
    private fun applyManualPromotion(trackId: String) {
        scope.launch {
            try {
                stateMutex.withLock {
                    activeJobs[trackId]?.autoCached = false
                    val row = repository.getDownloadEntity(trackId)
                    val file = row?.let {
                        if (it.downloadState == "COMPLETED") {
                            val f = File(it.localFilePath)
                            if (f.isFile && f.length() > 0L) f else null
                        } else null
                    }
                    if (row != null && file != null && row.autoCached) {
                        repository.updateOwnership(trackId, autoCached = false)
                        repository.markDeletionPending(trackId, false)
                    }
                    manualPromotionRequests.remove(trackId)
                    // A promotion that just landed may unblock a deferred
                    // deletion (the row is now MANUAL and must never be cut).
                    flushPendingDeletionsLocked()
                }
            } catch (_: Exception) {
                // Best-effort promotion; the download path re-checks ownership.
            }
        }
    }

    /**
     * Streams the remote track into [partFile], resuming from any bytes already
     * written via open-ended Range requests. Tunnel connections drop frequently,
     * so each failure restarts the request from the last written offset instead
     * of restarting the whole download.
     */
    private suspend fun downloadWithResume(track: Track, partFile: File) {
        var downloaded = if (partFile.exists()) partFile.length() else 0L
        var totalLength = (track.durationMs * 16).coerceAtLeast(1L)
        var consecutiveFailures = 0

        while (coroutineContext.isActive) {
            var response: Response? = null
            try {
                val request = Request.Builder()
                    .url(track.mediaUri)
                    .header("Range", "bytes=$downloaded-")
                    .get()
                    .build()

                response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw Exception("HTTP error ${response.code}")
                }

                // Total length from Content-Range: "bytes a-b/total"
                response.header("Content-Range")?.let { contentRange ->
                    contentRange.substringAfter('/').trim().toLongOrNull()?.let { total ->
                        if (total > 0) totalLength = total
                    }
                }

                val body = response.body ?: throw Exception("Empty response body")

                FileOutputStream(partFile, true).use { outputStream ->
                    // 32KB buffer (was 8KB): 4x fewer write() syscalls per
                    // song (~125 vs ~500 for 4MB), less CPU wake per MB.
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var lastReportedProgress = 0
                    var lastReportTime = System.currentTimeMillis()

                    body.byteStream().use { inputStream ->
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (!coroutineContext.isActive) {
                                withContext(NonCancellable) {
                                    inputStream.close()
                                    repository.deleteDownload(track.id)
                                }
                                return
                            }

                            outputStream.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            val currentProgress = if (totalLength > 0) {
                                ((downloaded * 100) / totalLength).toInt().coerceIn(0, 99)
                            } else {
                                50
                            }

                            // Battery & performance friendly UI update throttle (every 10% or 500ms)
                            if (currentProgress - lastReportedProgress >= 10 || (now - lastReportTime) >= 500) {
                                repository.updateProgress(track.id, currentProgress, downloaded)
                                lastReportedProgress = currentProgress
                                lastReportTime = now
                            }
                        }
                    }
                    outputStream.flush()
                }

                // Full file written
                return
            } catch (e: Exception) {
                response?.close()
                if (!coroutineContext.isActive) return
                if (consecutiveFailures >= 3) throw e
                consecutiveFailures++
                delay(1000L * consecutiveFailures)
                // Resume from the offset already written on the next attempt
            }
        }
    }

    /**
     * Re-checks the cap now (used when the user lowers 100 -> 15, or after an
     * AUTO download completes). Never touches MANUAL rows.
     */
    fun trimAutoCache() {
        scope.launch {
            try {
                stateMutex.withLock {
                    evictLocked(maxAutoCachedTracks)
                }
            } catch (_: Exception) {}
        }
    }

    /** "Clear auto-saved": removes every AUTO song (file + row), keeping all
     *  MANUAL downloads. Runs under the state mutex with per-row re-checks so
     *  a song promoted to MANUAL mid-operation survives. */
    fun clearAutoCachedDownloads() {
        scope.launch {
            try {
                stateMutex.withLock {
                    evictLocked(cap = 0)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Evicts AUTO tracks beyond [cap] (0 = evict everything AUTO), oldest
     * first. Caller must hold [stateMutex]. Every victim is re-checked
     * immediately before deletion:
     *  - still COMPLETED + AUTO (a concurrent promotion keeps the file),
     *  - not an active download,
     *  - not protected (currently-playing local file) and not the target of a
     *    just-admitted MANUAL promotion — those are DEFERRED instead, and the
     *    deletion is retried once the file is released.
     * Manual files never appear in the AUTO query, so they are unreachable
     * from eviction by construction.
     */
    private suspend fun evictLocked(cap: Int) {
        if (cap < 0) return
        // getAutoCachedCompleted() is newest-first (ORDER BY createdAt DESC):
        // dropping the cap keeps the newest `cap` songs, evicting the oldest.
        val victims = repository.getAutoCachedCompleted()
            .let { if (cap == 0) it else it.drop(cap) }

        for (entity in victims) {
            val live = repository.getDownloadEntity(entity.trackId) ?: continue
            if (!live.autoCached) continue
            if (live.downloadState != "COMPLETED") continue
            if (activeJobs.containsKey(live.trackId)) continue
            if (isTrackProtected(live.trackId) || manualPromotionRequests.contains(live.trackId)) {
                // File currently in use by playback (or a promotion is about to
                // make it MANUAL): keep row + file for now, persist the
                // deferred-deletion intent on the row, delete after release.
                repository.markDeletionPending(live.trackId, true)
                continue
            }
            repository.deleteDownload(live.trackId)
        }
        // Retry any deferred deletions whose file has been released meanwhile.
        flushPendingDeletionsLocked()
    }

    /**
     * Retries deferred deletions. Caller must hold [stateMutex]. The pending
     * set lives in the DATABASE (`pendingDeletion` on COMPLETED AUTO rows), so
     * a deferred request survives process death and this pass is safe to run
     * any number of times. A flagged row is only deleted when it is STILL AUTO
     * + COMPLETED, its file still exists, and it is neither protected,
     * downloading, nor under a fresh MANUAL-promotion intent. Anything else
     * resolves the flag without deleting:
     *  - row promoted to MANUAL   -> flag cleared, deletion cancelled (manual
     *    survives — deletion is never re-attempted against it),
     *  - row gone / file missing  -> nothing left to delete,
     *  - row downloading again    -> flag kept until the download settles,
     *  - still protected/in use    -> flag kept, retried at the next release.
     */
    private suspend fun flushPendingDeletionsLocked() {
        val pending = repository.getPendingDeletionDownloads()
        if (pending.isEmpty()) return
        for (entity in pending) {
            val live = repository.getDownloadEntity(entity.trackId) ?: continue
            if (!live.autoCached || live.downloadState != "COMPLETED") {
                // Promoted to MANUAL (or mid-download): never cut it. If it
                // became MANUAL, the flag is meaningless — clear it.
                if (!live.autoCached) repository.markDeletionPending(live.trackId, false)
                continue
            }
            if (activeJobs.containsKey(live.trackId)) continue
            if (isTrackProtected(live.trackId) || manualPromotionRequests.contains(live.trackId)) continue
            val file = File(live.localFilePath)
            if (!file.isFile || file.length() <= 0L) {
                // File already gone; drop the stale AUTO row too.
                repository.deleteDownload(live.trackId)
                continue
            }
            repository.deleteDownload(live.trackId)
        }
    }

    /**
     * Attempts every deferred deletion now (called when playback releases a
     * local file: track change, queue clear, or after startup once playback
     * state is known). Idempotent — flagged rows whose file is still in use
     * simply keep their flag for the next pass.
     */
    fun flushPendingDeletions() {
        scope.launch {
            try {
                stateMutex.withLock {
                    flushPendingDeletionsLocked()
                }
            } catch (_: Exception) {}
        }
    }

    /** Cancels an in-progress download and removes its row + partial bytes. */
    fun cancelDownload(trackId: String) {
        activeJobs.remove(trackId)?.job?.cancel()
        scope.launch {
            repository.deleteDownload(trackId)
        }
    }

    /**
     * User delete. MANUAL files and incomplete downloads are removed
     * immediately. A COMPLETED AUTO file currently in use by playback is
     * DEFERRED — the persisted `pendingDeletion` flag is set on the row and
     * the deletion is retried once the file is released (same playback-safety
     * rule as eviction and Clear auto-saved, and survives process death).
     */
    fun deleteDownload(trackId: String) {
        activeJobs.remove(trackId)?.job?.cancel()
        scope.launch {
            try {
                stateMutex.withLock {
                    val row = repository.getDownloadEntity(trackId)
                    val isPlayingAuto = row != null &&
                        row.downloadState == "COMPLETED" &&
                        row.autoCached &&
                        (isTrackProtected(trackId) || manualPromotionRequests.contains(trackId))
                    if (isPlayingAuto) {
                        repository.markDeletionPending(trackId, true)
                    } else {
                        repository.deleteDownload(trackId)
                        manualPromotionRequests.remove(trackId)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun clearAllDownloads() {
        activeJobs.values.forEach { it.job?.cancel() }
        activeJobs.clear()
        scope.launch {
            repository.clearAllDownloads()
        }
    }

    fun release() {
        activeJobs.values.forEach { it.job?.cancel() }
        activeJobs.clear()
        scope.cancel()
    }
}
