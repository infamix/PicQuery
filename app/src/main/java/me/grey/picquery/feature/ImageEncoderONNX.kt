// FILE: app/src/main/java/me/grey/picquery/feature/ImageEncoderONNX.kt
package me.grey.picquery.feature

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.grey.picquery.common.AssetUtil
import me.grey.picquery.feature.base.ImageEncoder
import me.grey.picquery.feature.base.Preprocessor
import java.nio.FloatBuffer
import java.util.Collections

open class ImageEncoderONNX(
    private val dim: Long,
    modelPath: String,
    context: Context,
    private val preprocessor: Preprocessor,
    private val dispatcher: CoroutineDispatcher
) : ImageEncoder {

    private val TAG = this::class.java.simpleName

    // Global, process-wide ORT environment. Do NOT close it per-encode.
    // `protected` so subclasses (e.g. ImageEncoderCLIP) can reuse it.
    protected val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    @Volatile
    protected var ortSession: OrtSession? = null

    init {
        val options = OrtSession.SessionOptions().apply {
            addConfigEntry("session.load_model_format", "ORT")
        }
        try {
            ortSession = ortEnv.createSession(
                AssetUtil.assetFilePath(context, modelPath),
                options
            )
        } finally {
            options.close()
        }
        Log.d(TAG, "Init $TAG")
    }

    fun clearSession() {
        ortSession?.close()
        ortSession = null
    }

    override suspend fun encodeBatch(bitmaps: List<Bitmap>): List<FloatArray> =
        withContext(dispatcher) {
            if (bitmaps.isEmpty()) return@withContext emptyList()

            val session = ortSession
                ?: throw IllegalStateException("ORT session is not initialized")

            val floatBuffer = preprocessor.preprocessBatch(bitmaps) as FloatBuffer

            val inputName = session.inputNames.iterator().next()
            val shape: LongArray = longArrayOf(bitmaps.size.toLong(), 3, dim, dim)

            OnnxTensor.createTensor(ortEnv, floatBuffer, shape).use { tensor ->
                session.run(Collections.singletonMap(inputName, tensor)).use { output ->
                    val outputTensor = output.get(0) as OnnxTensor

                    // Derive embedding size and batch count from model output.
                    val outputShape = outputTensor.info.shape
                    val embeddingSize = outputShape.last().toInt()
                    val numEmbeddings = outputShape
                        .dropLast(1)
                        .fold(1L) { acc, d -> acc * d }
                        .toInt()

                    val feat = outputTensor.floatBuffer
                    val embeddings = ArrayList<FloatArray>(numEmbeddings)
                    for (i in 0 until numEmbeddings) {
                        val start = i * embeddingSize
                        val embeddingArray = FloatArray(embeddingSize)
                        feat.position(start)
                        feat.get(embeddingArray, 0, embeddingSize)
                        embeddings.add(embeddingArray)
                    }
                    return@withContext embeddings
                }
            }
        }
}