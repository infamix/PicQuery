// FILE: app/src/main/java/me/grey/picquery/domain/EmbeddingUtils.kt
package me.grey.picquery.domain

import android.util.Log
import kotlinx.coroutines.coroutineScope
import me.grey.picquery.common.l2Normalize
import me.grey.picquery.data.data_source.EmbeddingRepository
import me.grey.picquery.data.model.Embedding
import me.grey.picquery.data.model.PhotoBitmap
import me.grey.picquery.data.model.toByteArray
import me.grey.picquery.feature.base.ImageEncoder
import kotlin.system.measureTimeMillis

object EmbeddingUtils {

    val TAG = "EmbeddingUtils"

    suspend fun saveBitmapsToEmbedding(
        items: List<PhotoBitmap?>,
        imageEncoder: ImageEncoder,
        embeddingRepository: EmbeddingRepository
    ) {
        coroutineScope {
            val valid = items.filterNotNull()
            if (valid.isEmpty()) return@coroutineScope

            Log.d(TAG, "saveBitmapsToEmbeddings Start encoding for embedding...")
            Log.d(TAG, "${System.currentTimeMillis()} Start encoding image...")

            val time = measureTimeMillis {
                val embeddings = imageEncoder.encodeBatch(valid.map { it.bitmap })

                // Guard against encoders silently dropping or duplicating outputs.
                if (embeddings.size != valid.size) {
                    Log.e(
                        TAG,
                        "Encoder output count mismatch: got ${embeddings.size}, " +
                                "expected ${valid.size}. Skipping batch to avoid corrupt index."
                    )
                    return@coroutineScope
                }

                Log.d(TAG, "${System.currentTimeMillis()} end encoding image...")

                embeddings.forEachIndexed { index, feat ->
                    // L2-normalize before storing so search can use a fast dot product.
                    val normalized = l2Normalize(feat)
                    embeddingRepository.updateList(
                        Embedding(
                            photoId = valid[index].photo.id,
                            albumId = valid[index].photo.albumID,
                            data = normalized.toByteArray()
                        )
                    )
                }
            }
            val costSec = (time / 1000f).coerceAtLeast(0.1f)
            Log.d(
                TAG,
                "Encode[v2] done! cost: $costSec s, speed: ${valid.size / costSec} pic/s"
            )
        }
    }
}