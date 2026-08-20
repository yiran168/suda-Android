package com.qrint.studio.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import kotlin.math.max

object ImageLoader {
    data class SourceDimensions(val width: Int, val height: Int) {
        val aspectRatio: Float get() = width.toFloat() / height.coerceAtLeast(1)
    }

    /** Reads only encoded bounds and EXIF orientation; it never allocates the full source bitmap. */
    fun dimensions(context: Context, uriText: String): SourceDimensions? {
        if (uriText.isBlank()) return null
        val uri = Uri.parse(uriText)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = readOrientation(context, uri)
        val swapsAxes = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        return if (swapsAxes) SourceDimensions(bounds.outHeight, bounds.outWidth)
        else SourceDimensions(bounds.outWidth, bounds.outHeight)
    }

    fun load(context: Context, uriText: String, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        if (uriText.isBlank()) return null
        val uri = Uri.parse(uriText)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, requestedWidth, requestedHeight)
        }
        val decoded = open(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = readOrientation(context, uri)
        return applyOrientation(decoded, orientation)
    }

    private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
        open(context, uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun open(context: Context, uri: Uri): InputStream? = runCatching {
        when (uri.scheme?.lowercase()) {
            "content", "file", "android.resource" -> context.contentResolver.openInputStream(uri)
            null -> java.io.File(uri.path.orEmpty()).inputStream()
            else -> null
        }
    }.getOrNull()

    private fun sampleSize(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int, requestedHeight: Int): Int {
        // The destination itself is printer-dot resolution; decoding at 2x only wastes heap and
        // cannot add printable detail on a 203 dpi monochrome head.
        val targetW = max(1, requestedWidth)
        val targetH = max(1, requestedHeight)
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetW && sourceHeight / (sample * 2) >= targetH) sample *= 2
        return sample
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }.getOrDefault(bitmap)
    }
}
