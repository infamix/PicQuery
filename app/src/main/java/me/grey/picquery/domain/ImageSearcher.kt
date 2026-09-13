// FILE: app/src/main/java/me/grey/picquery/domain/ImageSearcher.kt
package me.grey.picquery.domain

import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import me.grey.picquery.PicQueryApplication.Companion.context
import me.grey.picquery.R
import me.grey.picquery.common.calculateSimilarity
import me.grey.picquery.common.encodeProgressCallback
import me.grey.picquery.common.loadThumbnail
import me.grey.picquery.common.showToast
import me.grey.picquery.data.data_source.EmbeddingRepository
import me.grey.picquery.data.model.Album
import me.grey.picquery.data.model.Photo
import me.grey.picquery.data.model.PhotoBitmap
import me.grey.picquery.data.model.toFloatArray
import me.grey.picquery.domain.EmbeddingUtils.saveBitmapsToEmbedding
import me.grey.picquery.feature.base.ImageEncoder
import me.grey.picquery.feature.base.TextEncoder
import timber.log.Timber
import java.util.TreeSet
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
    private val translator: MLKitTranslator,
    private val dispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ImageSearcher"
        const val DEFAULT_MATCH_THRESHOLD = 0.20f
        const val DEFAULT_TOP_K = 30
        private const val SEARCH_BATCH_SIZE = 1000
    }

    val searchRange = mutableStateListOf<Album>()
    var isSearchAll = mutableStateOf(true)
    var searchTarget = mutableStateOf(SearchTarget.Image)

    val searchResultIds = mutableStateListOf<Long>()

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

    suspend fun getBaseLine(): FloatArray {
        val whiteBenchmark = ResourcesCompat.getDrawable(context.resources, R.drawable.white_benchmark, null)?.toBitmap()!!
        return imageEncoder.encodeBatch(listOf(whiteBenchmark)).first()
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

    private var encodingLock = false
    private var searchingLock = false

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun encodePhotoListV2(
        photos: List<Photo>,
        progressCallback: encodeProgressCallback? = null,
    ): Boolean {
        if (encodingLock) {
            Timber.tag(TAG).w("encodePhotoListV2: Already encoding!")
            return false
        }
        Timber.tag(TAG).i("encodePhotoListV2 started.")
        encodingLock = true

        try {
            withContext(dispatcher) {
                val cur = AtomicInteger(0)
                Timber.tag(TAG).d("start: ${photos.size}")

                photos.asFlow()
                    .map { photo ->
                        val thumbnailBitmap = loadThumbnail(context, photo)
                        if (thumbnailBitmap == null) {
                            Timber.tag(TAG).w("Unsupported file: '${photo.path}', skip encoding it.")
                            return@map null
                        }
                        PhotoBitmap(photo, thumbnailBitmap)
                    }
                    .filterNotNull()
                    .buffer(1000)
                    .chunked(100)
                    .onEach { Timber.tag(TAG).d("onEach: ${it.size}") }
                    .onCompletion {
                        embeddingRepository.updateCache()
                    }
                    .collect {
                        val loops = 1
                        val batchSize = it.size / loops
                        val cost = measureTimeMillis {
                            val deferreds = (0 until loops).map { index ->
                                async {
                                    val start = index * batchSize
                                    if (start >= it.size) return@async
                                    val end = start + batchSize
                                    saveBitmapsToEmbedding(
                                        it.slice(start until end),
                                        imageEncoder,
                                        embeddingRepository
                                    )
                                }
                            }
                            deferreds.awaitAll()
                        }
                        cur.set(it.size)

                        progressCallback?.invoke(
                            cur.get(),
                            photos.size,
                            cost / it.size,
                        )
                        Timber.tag(TAG).d("cost: ${cost}")
                    }
            }
        } finally {
            encodingLock = false
        }
        return true
    }

    suspend fun searchText(
        text: String,
        range: List<Album> = searchRange
    ): MutableSet<MutableMap.MutableEntry<Double, Long>> {
        return try {
            val translated = translator.translateSuspend(text)
            searchWithRange(translated, range)
        } catch (e: Exception) {
            Timber.tag("MLTranslator").e(e, "Translation failed, fallback to original")
            searchWithRange(text, range)
        }
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
            if (searchingLock) return@withContext mutableSetOf()
            searchingLock = true
            try {
                val textFeat = textEncoder.encode(text)
                searchWithVector(range, textFeat)
            } finally {
                searchingLock = false
            }
        }
    }

    private suspend fun searchWithRange(
        image: Bitmap,
        range: List<Album>
    ): MutableSet<MutableMap.MutableEntry<Double, Long>> {
        return withContext(dispatcher) {
            if (searchingLock) return@withContext mutableSetOf()
            searchingLock = true
            try {
                val bitmapFeats = imageEncoder.encodeBatch(mutableListOf(image))
                searchWithVector(range, bitmapFeats[0])
            } finally {
                searchingLock = false
            }
        }
    }

    private suspend fun searchWithVector(
        range: List<Album>,
        queryFeat: FloatArray
    ): MutableSet<MutableMap.MutableEntry<Double, Long>> {
        val sortedSet = TreeSet<SimilarityEntry>(
            compareByDescending<SimilarityEntry> { it.score }
                .thenByDescending { it.photoId }
        )

        val embeddings = if (range.isEmpty() || isSearchAll.value) {
            Timber.tag(TAG).d("Search from all album")
            embeddingRepository.getAllEmbeddingsPaginated(SEARCH_BATCH_SIZE)
        } else {
            Timber.tag(TAG).d("Search from: [${range.joinToString { it.label }}]")
            embeddingRepository.getEmbeddingsByAlbumIdsPaginated(range.map { it.id }, SEARCH_BATCH_SIZE)
        }

        val maxResults = topK.value
        var totalProcessed = 0
        embeddings.collect { chunk ->
            Timber.tag(TAG).d("Processing chunk: ${chunk.size}")
            totalProcessed += chunk.size

            for (emb in chunk) {
                val sim = calculateSimilarity(emb.data.toFloatArray(), queryFeat)
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