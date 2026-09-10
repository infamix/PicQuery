@file:OptIn(ExperimentalPermissionsApi::class)

package me.grey.picquery.domain

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grey.picquery.PicQueryApplication.Companion.context
import me.grey.picquery.R
import me.grey.picquery.common.showToast
import me.grey.picquery.data.data_source.AlbumRepository
import me.grey.picquery.data.data_source.EmbeddingRepository
import me.grey.picquery.data.data_source.PhotoRepository
import me.grey.picquery.data.model.Album
import me.grey.picquery.domain.worker.AlbumIndexWorker
import me.grey.picquery.ui.albums.IndexingAlbumState
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AlbumManager(
    private val albumRepository: AlbumRepository,
    private val photoRepository: PhotoRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val imageSearcher: ImageSearcher,
    private val ioDispatcher: CoroutineDispatcher
) {

    companion object {
        private const val TAG = "AlbumManager"
    }

    val indexingAlbumState = mutableStateOf(IndexingAlbumState())

    /**
     * True whenever ANY encoding job (foreground UI-driven or background worker)
     * is in progress. Used to prevent concurrent encoders from colliding.
     */
    val isEncoderBusy: Boolean
        get() = indexingAlbumState.value.isBusy || encodingJobRunning.get()

    private val encodingJobRunning = AtomicBoolean(false)

    private val albumList = mutableStateListOf<Album>()

    private val _searchableAlbumList = MutableStateFlow<List<Album>>(emptyList())
    private val _unsearchableAlbumList = MutableStateFlow<List<Album>>(emptyList())
    val searchableAlbumList: StateFlow<List<Album>> = _searchableAlbumList.asStateFlow()
    val unsearchableAlbumList: StateFlow<List<Album>> = _unsearchableAlbumList.asStateFlow()

    val albumsToEncode = mutableStateListOf<Album>()

    private fun searchableAlbumFlow() = albumRepository.getSearchableAlbumFlow()

    private var initialized = false
    private var dataFlowStarted = false

    fun getAlbumList() = albumList

    private val managerScope = CoroutineScope(
        SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, exception ->
                    Timber.tag(TAG).e(exception, "Coroutine error")
                }
    )

    fun processAlbums(snapshot: List<Album>) {
        managerScope.launch {
            encodeAlbums(snapshot)
        }
    }

    suspend fun initAllAlbumList() {
        if (initialized) return
        withContext(ioDispatcher) {
            // 本机中的相册
            val albums = albumRepository.getAllAlbums()
            albumList.addAll(albums)
            Timber.tag(TAG).d("ALL albums: ${albums.size}")
            this@AlbumManager.initialized = true
        }
        initDataFlow()

        // App just opened (with media permission granted):
        // kick off the robust background sync which
        //  1) resumes any indexing interrupted by a crash / process kill, and
        //  2) incrementally indexes NEW photos added to already-selected albums.
        scheduleBackgroundIndexSync()
    }

    /**
     * Enqueues a unique WorkManager job. Safe to call repeatedly:
     * [androidx.work.ExistingWorkPolicy.KEEP] guarantees at most one
     * sync is queued/running, and failed runs are retried by WorkManager
     * (survives process death), which makes background indexing robust.
     */
    fun scheduleBackgroundIndexSync() {
        try {
            AlbumIndexWorker.enqueue(context)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to schedule background index sync")
        }
    }

    fun initDataFlow() {
        if (dataFlowStarted) return
        dataFlowStarted = true
        managerScope.launch {
            searchableAlbumFlow().collect {
                // 从数据库中检索已经索引的相册
                // 有些相册可能已经索引但已被删除，因此要从全部相册中筛选，而不能直接返回数据库的结果
                val res = it.toMutableList().sortedByDescending { album: Album -> album.count }
                _searchableAlbumList.update { res }
                Timber.tag(TAG).d("Searchable albums: ${it.size}")

                // 从全部相册减去已经索引的ID，就是未索引的相册
                val unsearchable = albumList.filter { all -> !it.contains(all) }
                _unsearchableAlbumList.update { (unsearchable.toMutableList().sortedByDescending { album: Album -> album.count }) }
                Timber.tag(TAG).d("Unsearchable albums: ${unsearchable.size}")
            }
        }
    }

    fun toggleAlbumSelection(album: Album) {
        if (albumsToEncode.contains(album)) {
            albumsToEncode.remove(album)
        } else {
            albumsToEncode.add(album)
        }
    }

    fun toggleSelectAllAlbums() {
        if (albumsToEncode.size != unsearchableAlbumList.value.size) {
            albumsToEncode.clear()
            albumsToEncode.addAll(unsearchableAlbumList.value)
        } else {
            albumsToEncode.clear()
        }
    }

    /**
     * FOREGROUND indexing of user-selected albums, with progress UI state.
     *
     * Key robustness change: albums are persisted as searchable BEFORE any
     * encoding starts. The database row is the checkpoint — if encoding is
     * interrupted for any reason (crash, process kill, OOM, user swipe-away),
     * the next app open detects these albums via [AlbumIndexWorker] and
     * resumes encoding from exactly where it left off (photos that already
     * have an embedding row are skipped).
     */
    suspend fun encodeAlbums(albums: List<Album>) {
        if (albums.isEmpty()) {
            showToast(context.getString(R.string.no_album_selected))
            return
        }
        if (isEncoderBusy) {
            showToast(context.getString(R.string.busy_when_add_album_toast))
            return
        }

        indexingAlbumState.value =
            IndexingAlbumState(status = IndexingAlbumState.Status.Loading)
        try {
            // 1. SAVE-FIRST: persist the album selection up front so that an
            //    interrupted run is resumable on the next app start.
            withContext(ioDispatcher) {
                albumRepository.addAllSearchableAlbum(albums)
            }

            // 2. Encode only the photos that don't have an embedding yet.
            val success = runEncodingJob(albums, updateUiState = true)

            if (success) {
                Timber.tag(TAG).i("Indexed ${albums.size} album(s) successfully!")
                withContext(ioDispatcher) {
                    refreshAlbumMetadata(albums)
                }
                indexingAlbumState.value = indexingAlbumState.value.copy(
                    status = IndexingAlbumState.Status.Finish
                )
            } else {
                // Not fatal: the albums are already saved, the background
                // worker will resume the remaining photos on next app open.
                Timber.tag(TAG).w("Encoding incomplete; it will resume on next app start.")
                indexingAlbumState.value = indexingAlbumState.value.copy(
                    status = IndexingAlbumState.Status.Error
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error encoding albums")
            indexingAlbumState.value = indexingAlbumState.value.copy(
                status = IndexingAlbumState.Status.Error
            )
        }
    }

    /**
     * BACKGROUND entry point, invoked by [AlbumIndexWorker] on every app open
     * (and on WorkManager retries).
     *
     * Re-scans every album already marked searchable and encodes only photos
     * whose embeddings are missing. This handles both:
     *  - resuming indexing interrupted by a crash / process death, and
     *  - automatically indexing NEW photos added to selected albums.
     *
     * @return true when everything pending was encoded successfully.
     */
    suspend fun syncSearchableAlbumsInBackground(): Boolean {
        if (encodingJobRunning.get()) {
            Timber.tag(TAG).w("A foreground encoding job is running, skip background sync.")
            return false
        }

        val albums = withContext(ioDispatcher) {
            albumRepository.getSearchableAlbums()
        }
        if (albums.isEmpty()) {
            Timber.tag(TAG).d("No searchable albums to sync.")
            return true
        }

        // Ensure the in-memory list of all device albums is available
        // (needed for metadata refresh; the worker may run before UI init).
        if (albumList.isEmpty()) {
            withContext(ioDispatcher) {
                albumList.addAll(albumRepository.getAllAlbums())
            }
        }

        val success = runEncodingJob(albums, updateUiState = false)
        if (success) {
            withContext(ioDispatcher) {
                refreshAlbumMetadata(albums)
            }
            Timber.tag(TAG).i("Background sync finished for ${albums.size} album(s).")
        } else {
            Timber.tag(TAG).w("Background sync incomplete; will retry.")
        }
        return success
    }

    /**
     * Mutual-exclusion wrapper around the shared resumable encoding core.
     * Guarantees only one encoding job (foreground OR background) runs at
     * any time in this process.
     */
    private suspend fun runEncodingJob(albums: List<Album>, updateUiState: Boolean): Boolean {
        if (!encodingJobRunning.compareAndSet(false, true)) {
            Timber.tag(TAG).w("Another encoding job is already running, skip.")
            return false
        }
        return try {
            encodePendingPhotos(albums, updateUiState)
        } finally {
            encodingJobRunning.set(false)
        }
    }

    /**
     * The resumable encoding core shared by foreground and background paths.
     *
     * For each album it computes the diff between the photos currently in the
     * album (streamed page by page, memory-friendly) and the photo ids that
     * already have an embedding row, then encodes ONLY the missing ones.
     * Because embeddings are flushed to the DB in batches as they are
     * produced, an interruption at any point loses at most the current
     * in-flight batch — the next run picks up from there.
     *
     * @return true if every pending photo was encoded successfully.
     */
    private suspend fun encodePendingPhotos(
        albums: List<Album>,
        updateUiState: Boolean
    ): Boolean = withContext(ioDispatcher) {
        var allSuccess = true

        var totalPhotos = 0
        albums.forEach { album ->
            totalPhotos += photoRepository.getImageCountInAlbum(album.id)
        }
        val processedPhotos = AtomicInteger(0)

        for (album in albums) {
            // Resume point: ids of photos already embedded for this album.
            val encodedIds = embeddingRepository.getEncodedPhotoIds(album.id).toHashSet()
            Timber.tag(TAG).d(
                "Album '${album.label}': ${encodedIds.size} photo(s) already indexed."
            )

            photoRepository.getPhotoListByAlbumIdPaginated(album.id).collect { chunk ->
                val pending = chunk.filterNot { photo -> encodedIds.contains(photo.id) }
                val skipped = chunk.size - pending.size
                if (skipped > 0) {
                    processedPhotos.addAndGet(skipped)
                }

                if (pending.isNotEmpty()) {
                    val chunkSuccess = imageSearcher.encodePhotoListV2(pending) { cur, _, cost ->
                        processedPhotos.addAndGet(cur)
                        if (updateUiState) {
                            indexingAlbumState.value = indexingAlbumState.value.copy(
                                current = processedPhotos.get().coerceAtMost(totalPhotos),
                                total = totalPhotos,
                                cost = cost,
                                status = IndexingAlbumState.Status.Indexing
                            )
                        }
                    }
                    if (!chunkSuccess) {
                        allSuccess = false
                        Timber.tag(TAG)
                            .w("Failed to encode chunk in album '${album.label}', size: ${pending.size}")
                    }
                } else if (updateUiState) {
                    // Pure resume/skip pass: still reflect progress in the UI.
                    indexingAlbumState.value = indexingAlbumState.value.copy(
                        current = processedPhotos.get().coerceAtMost(totalPhotos),
                        total = totalPhotos,
                        status = IndexingAlbumState.Status.Indexing
                    )
                }
            }
        }

        if (allSuccess) {
            Timber.tag(TAG).i(
                "Indexing pass complete: $totalPhotos photo(s) across ${albums.size} album(s)."
            )
        }
        allSuccess
    }

    /**
     * Refresh the stored searchable-album metadata (count / timestamp / cover)
     * after a successful indexing pass, so the Index Manager no longer flags
     * these albums as "update needed".
     */
    private fun refreshAlbumMetadata(albums: List<Album>) {
        if (albumList.isEmpty()) return
        val updates = albums.mapNotNull { saved ->
            val fresh = albumList.find { it.id == saved.id } ?: return@mapNotNull null
            saved.copy(
                label = fresh.label,
                coverPath = fresh.coverPath,
                timestamp = fresh.timestamp,
                count = fresh.count
            )
        }
        if (updates.isNotEmpty()) {
            albumRepository.addAllSearchableAlbum(updates)
        }
    }

    fun clearIndexingState() {
        indexingAlbumState.value = IndexingAlbumState()
    }

    fun removeSingleAlbumIndex(album: Album) {
        embeddingRepository.removeByAlbum(album)
        albumRepository.removeSearchableAlbum(album)
    }
}
