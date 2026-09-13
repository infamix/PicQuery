// FILE: app/src/main/java/me/grey/picquery/common/ImageUtil.kt
package me.grey.picquery.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Size
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import me.grey.picquery.common.Constants.DIM
import me.grey.picquery.data.model.Photo

private const val TAG = "ImageUtil"

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }

    return inSampleSize
}

fun decodeSampledBitmapFromFile(
    pathName: String,
    size: Size,
): Bitmap? {
    return try {
        BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(pathName, this)
            inSampleSize = calculateInSampleSize(this, size.width, size.height)
            inJustDecodeBounds = false
            BitmapFactory.decodeFile(pathName, this)
        }
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Failed to decode file: $pathName, ${e.message}")
        null
    }
}

val IMAGE_INPUT_SIZE = Size(DIM, DIM)

suspend fun loadThumbnail(context: Context, photo: Photo, size: Size = IMAGE_INPUT_SIZE): Bitmap? {
    return flow<Bitmap?> {
        emit(context.contentResolver.loadThumbnail(photo.uri, size, null))
    }.catch {
        emit(
            coroutineScope {
                Glide.with(context)
                    .asBitmap()
                    .load(photo.path)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .downsample(DownsampleStrategy.FIT_CENTER)
                    .override(DIM)
                    .skipMemoryCache(true)
                    .submit().get()
            }
        )
    }.catch {
        emit(decodeSampledBitmapFromFile(photo.path, size))
    }.first()
}