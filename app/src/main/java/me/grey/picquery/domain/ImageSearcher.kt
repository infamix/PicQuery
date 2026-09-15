// FILE: app/src/main/java/me/grey/picquery/domain/ImageSearcher.kt
package me.grey.picquery.domain

import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import me.grey.picquery.PicQueryApplication.Companion.context
import me.grey.picquery.R
import me.grey.picquery.common.dotProduct
import me.grey.picquery.common.encodeProgressCallback
import me.grey.picquery.common.l2Normalize
import me.grey.picquery.common.loadThumbnail
import me.grey.picquery.data.data_source.EmbeddingRepository
import me.grey.picquery.data.model.Album
import me.grey.picquery.data.model.Photo
import me.grey.picquery.data.model.PhotoBitmap
import me.grey.picquery.data.model.toFloatArray
import me.grey.picquery.domain.EmbeddingUtils.saveBitmapsToEmbedding
import me.grey.picquery.feature.base.ImageEncoder
import me.grey.picquery.feature.base.TextEncoder
import timber.log.Timber
import java.util.Random
import java.util.TreeSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

enum class SearchTarget(val labelResId: Int, val icon: ImageVector) {
    Image(R.string.search_target_image, Icons.Outlined.ImageSearch),
    Text(R.string.search_target_text, Icons.Outlined.Translate),
}

class ImageSearcher(
    private val imageEncoder: ImageEncoder,
    private val textEncoder: TextEncoder,
    private val embeddingRepository: EmbeddingRepository,
    private val dispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ImageSearcher"
        const val DEFAULT_MATCH_THRESHOLD = 0.20f
        const val DEFAULT_TOP_K = 30
        private const val SEARCH_BATCH_SIZE = 1000
        private const val ENCODE_CHUNK_SIZE = 100
    }

    // CopyOnWriteArrayList: modified from UI, read from IO during search /
    // display. Iteration is snapshot-based, so no ConcurrentModification.
    val searchRange: MutableList<Album> = CopyOnWriteArrayList()
    var isSearchAll = mutableStateOf(true)
    var searchTarget = mutableStateOf(SearchTarget.Image)

    val searchResultIds: MutableList<Long> = CopyOnWriteArrayList()

    private val _matchThreshold = mutableFloatStateOf(DEFAULT_MATCH_THRESHOLD)
    val matchThreshold: State<Float> = _matchThreshold

    private val _topK = mutableIntStateOf(DEFAULT_TOP_K)
    val topK: State<Int> = _topK

    private val resultLock = Any()

    fun updateRange(range: List<Album>, searchAll: Boolean) {
        searchRange.clear()
        searchRange.addAll(range.sortedByDescending { it.count })
        isSearchAll.value = searchAll
    }

    /**
     * Baseline used to bucket embeddings before the O(n^2) union-find pass.
     *
     * The previous implementation encoded a white image, which produces a
     * fixed but semantically meaningless direction in embedding space. We
     * replace it with a deterministic random unit vector. For L2-normalized
     * embeddings, the dot product against a random unit vector is a
     * locality-sensitive sketch: nearby embeddings tend to have similar
     * projections, so `similarityDelta` bucketing actually correlates with
     * visual similarity.
     */
    suspend fun getBaseLine(): FloatArray {
        // Determine the embedding dimension by encoding a tiny dummy bitmap.
        val dummy = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val dim = try {
            imageEncoder.encodeBatch(listOf(dummy)).first().size
        } finally {
            dummy.recycle()
        }
        val random = Random(42L)
        val randomVector = FloatArray(dim) { random.nextFloat() * 2f - 1f }
        return l2Normalize(randomVector)
    }

    fun updateTarget(target: SearchTarget) {
        searchTarget.value = target
    }

    suspend fun hasEmbedding(): Boolean {
        return withContext(dispatcher) {
            val total = embeddingRepository.getTotalCount()
            Timber.tag(TAG).d("Total embedding count $total")
            total > 0
        }
    }

    private val encodingLock = AtomicBoolean(false)
    private val searchingLock = AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun encodePhotoListV2(
        photos: List<Photo>,
        progressCallback: encodeProgressCallback? = null,
    ): Boolean {
        if (!encodingLock.compareAndSet(false, true)) {
            Timber.tag(TAG).w("encodePhotoListV2: Already encoding!")
            return false
        }
        Timber.tag(TAG).i("encodePhotoListV2 started.")

        return try {
            withContext(dispatcher) {
                val cur = AtomicInteger(0)
                val failedCount = AtomicInteger(0)
                Timber.tag(TAG).d("start: ${photos.size}")

                photos.asFlow()
                    .map { photo ->
                        val thumbnailBitmap = loadThumbnail(context, photo)
                        if (thumbnailBitmap == null) {
                            Timber.tag(TAG).w("Unsupported file: '${photo.path}', skip encoding it.")
                            failedCount.incrementAndGet()
                            return@map null
                        }
                        PhotoBitmap(photo, thumbnailBitmap)
                    }
                    .filterNotNull()
                    // No buffer(1000): that allowed up to 1000 PhotoBitmap
                    // instances (~200 MB for CLIP) to accumulate in memory.
                    // chunked(ENCODE_CHUNK_SIZE) provides enough look-ahead.
                    .chunked(ENCODE_CHUNK_SIZE)
                    .onEach { Timber.tag(TAG).d("onEach: ${it.size}") }
                    .onCompletion {
                        embeddingRepository.updateCache()
                    }
                    .collect { chunk ->
                        // Encoding is CPU-bound; run it on Default. DB
                        // writes inside saveBitmapsToEmbedding stay on the
                        // caller's IO dispatcher.
                        val startTime = System.currentTimeMillis()
                        withContext(Dispatchers.Default) {
                            saveBitmapsToEmbedding(
                                chunk,
                                imageEncoder,
                                embeddingRepository
                            )
                        }
                        val cost = System.currentTimeMillis() - startTime

                        // Bitmaps are no longer needed after encoding.
                        chunk.forEach { it.bitmap.recycle() }

                        cur.set(chunk.size)
                        progressCallback?.invoke(
                            cur.get(),
                            photos.size,
                            cost / chunk.size,
                        )
                        Timber.tag(TAG).d("cost: ${cost}")
                    }

                // If any photo failed to load, report the batch as
                // incomplete so the caller can resume on the next pass.
                failedCount.get() == 0
            }
        } finally {
            encodingLock.set(false)
        }
    }

    suspend fun searchText(
        text: String,
        range: List<Album> = searchRange
    ): MutableSet<MutableMap.MutableEntry<Double, Long>> {
        return searchWithRange(text, range)
    }

    suspend fun searchImage(
        image: Bitmap,
        range: List<Album> = searchRange
    ): MutableSet<MutableMap.MutableEntry<Double, Long>> {
        return searchWithRange(image, range)
    }

    private suspend fun searchWithRange(
        text: String,
        range: List<Album>
    ): MutableSet<MutableMap.MutableEntry<Double, Long>> {
        return withContext(dispatcher) {
            if (!searchingLock.compareAndSet(false, true)) return@withContext mutableSetOf()
            try {
                val textFeat = l2Normalize(textEncoder.encode(text))
                searchWithVector(range, textFeat)
            } finally {
                searchingLock.set(false)
            }
        }
    }

    private suspend fun searchWithRange(
        image: Bitmap,
        range: List<Album>
    ): MutableSet<MutableMap.MutableEntry<Double, Long>> {
        return withContext(dispatcher) {
            if (!searchingLock.compareAndSet(false, true)) return@withContext mutableSetOf()
            try {
                val bitmapFeats = imageEncoder.encodeBatch(mutableListOf(image))
                val queryFeat = l2Normalize(bitmapFeats[0])
                searchWithVector(range, queryFeat)
            } finally {
                searchingLock.set(false)
            }
        }
    }

    private suspend fun searchWithVector(
        range: List<Album>,
        queryFeat: FloatArray
    ): MutableSet<MutableMap.MutableEntry<Double, Long>> {
        // Snapshot the range so concurrent UI updates during iteration
        // cannot cause a ConcurrentModificationException.
        val rangeSnapshot: List<Album> = range.toList()
        val searchAll = isSearchAll.value

        val sortedSet = TreeSet<SimilarityEntry>(
            compareByDescending<SimilarityEntry> { it.score }
                .thenByDescending { it.photoId }
        )

        val embeddings = if (rangeSnapshot.isEmpty() || searchAll) {
            Timber.tag(TAG).d("Search from all album")
            embeddingRepository.getAllEmbeddingsPaginated(SEARCH_BATCH_SIZE)
        } else {
            Timber.tag(TAG).d("Search from: [${rangeSnapshot.joinToString { it.label }}]")
            embeddingRepository.getEmbeddingsByAlbumIdsPaginated(
                rangeSnapshot.map { it.id },
                SEARCH_BATCH_SIZE
            )
        }

        val maxResults = topK.value
        var totalProcessed = 0
        embeddings.collect { chunk ->
            Timber.tag(TAG).d("Processing chunk: ${chunk.size}")
            totalProcessed += chunk.size

            for (emb in chunk) {
                // Stored embeddings are L2-normalized, query is L2-normalized,
                // so dot product == cosine similarity (much faster).
                val sim = dotProduct(emb.data.toFloatArray(), queryFeat)
                if (sim >= matchThreshold.value) {
                    val entry = SimilarityEntry(sim, emb.photoId)
                    synchronized(resultLock) {
                        sortedSet.add(entry)
                        while (sortedSet.size > maxResults) {
                            sortedSet.pollLast()
                        }
                    }
                }
            }
        }

        Timber.tag(TAG).d("Search Finish: Processed $totalProcessed embeddings")
        Timber.tag(TAG).d("Search result: found ${sortedSet.size} pics")

        searchResultIds.clear()
        val result = mutableSetOf<MutableMap.MutableEntry<Double, Long>>()
        synchronized(resultLock) {
            for (entry in sortedSet) {
                result.add(entry)
                searchResultIds.add(entry.photoId)
            }
        }
        Timber.tag(TAG).d("Search result: ${result.joinToString(",")}")
        return result
    }

    fun updateSearchConfiguration(newMatchThreshold: Float, newTopK: Int) {
        _matchThreshold.floatValue = newMatchThreshold.coerceIn(0.1f, 0.5f)
        _topK.intValue = newTopK.coerceIn(10, 100)
        Timber.tag(TAG).d(
            "Search configuration updated: " +
                    "matchThreshold=${_matchThreshold.floatValue}, topK=${_topK.intValue}"
        )
    }
}

data class SimilarityEntry(
    val score: Double,
    val photoId: Long
) : MutableMap.MutableEntry<Double, Long> {
    override val key: Double get() = score
    override val value: Long get() = photoId
    override fun setValue(newValue: Long): Long = throw UnsupportedOperationException("Immutable entry")
}