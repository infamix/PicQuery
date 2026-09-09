package me.grey.picquery.feature.clip

import android.graphics.Bitmap
import android.graphics.Canvas
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
     * Converts a single bitmap to a FloatBuffer in CLIP format.
     * Steps:
     * 1. Compute scale so the larger dimension becomes 224.
     * 2. Scale the bitmap preserving aspect ratio.
     * 3. Create a 224x224 canvas filled with black.
     * 4. Draw the scaled bitmap centered.
     * 5. Extract pixels, normalize, and store in channel-major order (R, G, B blocks).
     */
    fun bitmapToFloatBuffer(bm: Bitmap): FloatBuffer {
        val width = bm.width
        val height = bm.height

        // Scale so that the larger side becomes INPUT
        val scale = if (width >= height) {
            INPUT.toFloat() / width
        } else {
            INPUT.toFloat() / height
        }
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        // Resize the bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(bm, newWidth, newHeight, true)

        // Create a 224x224 black canvas
        val paddedBitmap = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBitmap)
        canvas.drawARGB(255, 0, 0, 0)  // fill with black

        // Center the scaled bitmap
        val offsetX = (INPUT - newWidth) / 2f
        val offsetY = (INPUT - newHeight) / 2f
        canvas.drawBitmap(scaledBitmap, offsetX, offsetY, null)

        // Extract pixels
        val stride = INPUT
        val pixels = IntArray(INPUT * INPUT)
        paddedBitmap.getPixels(pixels, 0, stride, 0, 0, INPUT, INPUT)

        // Allocate FloatBuffer (CHANNELS * INPUT * INPUT)
        val imgData = FloatBuffer.allocate(CHANNELS * INPUT * INPUT)
        imgData.rewind()

        val totalPixels = INPUT * INPUT
        for (i in 0 until totalPixels) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            // Normalize and store in channel-major order
            imgData.put(i, (r - NORM_MEAN[0]) / NORM_STD[0])
            imgData.put(i + totalPixels, (g - NORM_MEAN[1]) / NORM_STD[1])
            imgData.put(i + totalPixels * 2, (b - NORM_MEAN[2]) / NORM_STD[2])
        }

        imgData.rewind()
        return imgData
    }

    /**
     * Concatenates multiple single-image buffers into one batch buffer.
     */
    fun bitmapsToFloatBuffer(bitmaps: List<Bitmap>): FloatBuffer {
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
