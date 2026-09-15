// FILE: app/src/main/java/me/grey/picquery/feature/clip/PreprocessorCLIP.kt
package me.grey.picquery.feature.clip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import me.grey.picquery.feature.base.Preprocessor
import java.nio.FloatBuffer

class PreprocessorCLIP : Preprocessor {

    companion object {
        const val INPUT = 224
        private const val CHANNELS = 3
        private val NORM_MEAN = floatArrayOf(0.48145467f, 0.4578275f, 0.40821072f)
        private val NORM_STD = floatArrayOf(0.26862955f, 0.2613026f, 0.2757771f)
    }

    override suspend fun preprocessBatch(input: List<Bitmap>): FloatBuffer {
        return bitmapsToFloatBuffer(input)
    }

    override suspend fun preprocess(input: Bitmap): FloatBuffer {
        return bitmapToFloatBuffer(input)
    }

    /**
     * Resize preserving aspect ratio so the LONGER side becomes INPUT, then
     * center-pad with black to a square INPUT x INPUT canvas. No cropping.
     *
     * Uses a single output bitmap and a Matrix to scale directly onto the
     * canvas — avoids the intermediate `createScaledBitmap` allocation.
     */
    fun bitmapToFloatBuffer(bm: Bitmap): FloatBuffer {
        val width = bm.width
        val height = bm.height

        val scale = if (width >= height) {
            INPUT.toFloat() / width
        } else {
            INPUT.toFloat() / height
        }
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        val paddedBitmap = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBitmap)
        canvas.drawARGB(255, 0, 0, 0)

        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((INPUT - newWidth) / 2f, (INPUT - newHeight) / 2f)
        }
        canvas.drawBitmap(bm, matrix, null)

        val pixels = IntArray(INPUT * INPUT)
        paddedBitmap.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)

        val totalPixels = INPUT * INPUT
        val imgData = FloatBuffer.allocate(CHANNELS * totalPixels)

        for (i in 0 until totalPixels) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            imgData.put(i, (r - NORM_MEAN[0]) / NORM_STD[0])
            imgData.put(i + totalPixels, (g - NORM_MEAN[1]) / NORM_STD[1])
            imgData.put(i + totalPixels * 2, (b - NORM_MEAN[2]) / NORM_STD[2])
        }

        imgData.rewind()
        paddedBitmap.recycle()
        return imgData
    }

    fun bitmapsToFloatBuffer(bitmaps: List<Bitmap>): FloatBuffer {
        if (bitmaps.isEmpty()) return FloatBuffer.allocate(0)
        val totalSize = bitmaps.size * CHANNELS * INPUT * INPUT
        val combinedBuffer = FloatBuffer.allocate(totalSize)

        for (bitmap in bitmaps) {
            val floatBuffer = bitmapToFloatBuffer(bitmap)
            combinedBuffer.put(floatBuffer)
        }

        combinedBuffer.flip()
        return combinedBuffer
    }
}