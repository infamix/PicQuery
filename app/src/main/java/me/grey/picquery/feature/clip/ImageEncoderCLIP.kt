// FILE: app/src/main/java/me/grey/picquery/feature/clip/ImageEncoderCLIP.kt
package me.grey.picquery.feature.clip

import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineDispatcher
import me.grey.picquery.feature.ImageEncoderONNX
import java.util.Collections

class ImageEncoderCLIP(
    context: Context,
    private val preprocessor: PreprocessorCLIP,
    private val dispatcher: CoroutineDispatcher
) : ImageEncoderONNX(
    224, "clip-image-int8.ort", context, preprocessor, dispatcher
) {

    companion object {
        const val INPUT = 224
    }

    /**
     * Per-image encoding: avoids the batch FloatBuffer copy + split that used
     * to double-allocate the input buffer. Each image is preprocessed into a
     * single FloatBuffer and consumed directly.
     */
    override suspend fun encodeBatch(bitmaps: List<Bitmap>): List<FloatArray> {
        if (bitmaps.isEmpty()) return emptyList()

        val session = ortSession
            ?: throw IllegalStateException("ORT session is not initialized")
        val inputName = session.inputNames.iterator().next()
        val shape = longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())

        val res = ArrayList<FloatArray>(bitmaps.size)
        for (bitmap in bitmaps) {
            // `preprocessor` is typed as the concrete PreprocessorCLIP, whose
            // override returns FloatBuffer directly (covariant return type),
            // so no cast is required.
            val buffer = preprocessor.preprocess(bitmap)
            OnnxTensor.createTensor(ortEnv, buffer, shape).use { tensor ->
                session.run(Collections.singletonMap(inputName, tensor)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val rawOutput = ((output.get(0).value) as Array<FloatArray>)[0]
                    res.add(rawOutput)
                }
            }
        }
        return res
    }
}