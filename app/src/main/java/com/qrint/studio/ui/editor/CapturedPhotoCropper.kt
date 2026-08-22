package com.qrint.studio.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.qrint.studio.data.CapturedMediaStore
import com.qrint.studio.render.ImageLoader
import kotlin.math.max
import kotlin.math.roundToInt

/** Crops the same EXIF-oriented pixels shown by [PhotoCropSheet]. */
internal object CapturedPhotoCropper {
    private const val EDITING_MAX_DIMENSION = 1_600

    fun loadPreview(context: Context, uri: Uri): Bitmap? {
        val dimensions = ImageLoader.dimensions(context, uri.toString()) ?: return null
        val scale = (EDITING_MAX_DIMENSION.toFloat() / max(dimensions.width, dimensions.height))
            .coerceAtMost(1f)
        return ImageLoader.load(
            context,
            uri.toString(),
            (dimensions.width * scale).roundToInt().coerceAtLeast(1),
            (dimensions.height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    fun crop(context: Context, uri: Uri, region: CameraScanRegion): Result<Uri> = runCatching {
        val source = loadPreview(context, uri) ?: error("无法读取拍摄的照片")
        var cropped: Bitmap? = null
        try {
            val bounds = region.toPixelCrop(source.width, source.height)
            cropped = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height)
            CapturedMediaStore.saveBitmap(context, cropped, "photo-crop").getOrThrow()
        } finally {
            cropped?.takeIf { it !== source && !it.isRecycled }?.recycle()
            if (!source.isRecycled) source.recycle()
        }
    }
}
